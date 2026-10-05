package pl.adamw.drivesync

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class DriveException(val code: Int, message: String) : IOException("Drive HTTP $code: $message")

/** Plik po zakończonej wysyłce (pola z odpowiedzi Drive). */
data class DriveFile(val id: String, val md5: String?, val size: Long)

/** Postęp sesji resumable: albo wysłano już `offset` bajtów, albo plik jest kompletny. */
sealed class UploadState {
    data class Partial(val offset: Long) : UploadState()
    data class Done(val file: DriveFile) : UploadState()
    data object Expired : UploadState()
}

/** Minimalny klient Drive API v3 (REST) - tylko to, czego potrzeba do kopiowania plików. */
class DriveApi(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private var token: String? = null

    private fun currentToken(): String = token ?: runBlocking { Auth.token(context) }.also { token = it }

    /** Wykonuje żądanie z tokenem; po 401 raz odświeża token. Wywołujący zamyka Response. */
    private fun call(build: Request.Builder.() -> Unit): Response {
        repeat(2) { attempt ->
            val t = currentToken()
            val response = http.newCall(Request.Builder().apply(build).header("Authorization", "Bearer $t").build()).execute()
            if (response.code != 401 || attempt == 1) return response
            response.close()
            Auth.invalidate(context, t)
            token = null
        }
        error("unreachable")
    }

    private fun Response.jsonOrThrow(): JSONObject = use {
        val body = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw DriveException(it.code, body.take(300))
        JSONObject(body)
    }

    // ---------- Foldery ----------

    fun findFolder(name: String, parentId: String): String? {
        val q = "name = '${name.replace("\\", "\\\\").replace("'", "\\'")}' and '$parentId' in parents " +
            "and mimeType = '$FOLDER_MIME' and trashed = false"
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("spaces", "drive")
            .build()
        val files: JSONArray = call { url(url) }.jsonOrThrow().getJSONArray("files")
        return if (files.length() > 0) files.getJSONObject(0).getString("id") else null
    }

    fun createFolder(name: String, parentId: String): String {
        val meta = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", JSONArray().put(parentId))
        return call { url("$API/files?fields=id"); post(meta.toString().toRequestBody(JSON)) }
            .jsonOrThrow().getString("id")
    }

    /** Czy plik/folder nadal istnieje i nie jest w koszu. */
    fun exists(id: String): Boolean = call { url("$API/files/$id?fields=id,trashed") }.use {
        when {
            it.code == 404 -> false
            !it.isSuccessful -> throw DriveException(it.code, it.body?.string().orEmpty().take(300))
            else -> !JSONObject(it.body!!.string()).optBoolean("trashed")
        }
    }

    // ---------- Wysyłka (resumable upload) ----------

    /**
     * Zaczyna sesję wysyłki. Z [existingId] - nowa wersja istniejącego pliku (bez duplikatu),
     * bez niego - nowy plik w [parentId]. Zwraca URI sesji albo null, gdy [existingId] już nie istnieje.
     */
    fun startUpload(name: String, parentId: String, existingId: String?, size: Long, mime: String): String? {
        val response = if (existingId != null) {
            call {
                url("$UPLOAD/files/$existingId?uploadType=resumable&fields=$FILE_FIELDS")
                patch("{}".toRequestBody(JSON))
                header("X-Upload-Content-Type", mime)
                header("X-Upload-Content-Length", size.toString())
            }
        } else {
            val meta = JSONObject().put("name", name).put("parents", JSONArray().put(parentId))
            call {
                url("$UPLOAD/files?uploadType=resumable&fields=$FILE_FIELDS")
                post(meta.toString().toRequestBody(JSON))
                header("X-Upload-Content-Type", mime)
                header("X-Upload-Content-Length", size.toString())
            }
        }
        response.use {
            if (existingId != null && it.code == 404) return null
            if (!it.isSuccessful) throw DriveException(it.code, it.body?.string().orEmpty().take(300))
            return it.header("Location") ?: throw IOException("Brak adresu sesji wysyłki")
        }
    }

    /** Ile bajtów serwer już ma w tej sesji (do wznowienia po przerwaniu). */
    fun queryUpload(session: String, total: Long): UploadState =
        call { url(session); put(ByteArray(0).toRequestBody()); header("Content-Range", "bytes */$total") }
            .use { parseUploadResponse(it) }

    /** Wysyła fragment pliku od [offset]; długość fragmentu to wielokrotność 256 KiB (poza ostatnim). */
    fun uploadChunk(session: String, uri: Uri, offset: Long, total: Long, chunk: Long): UploadState {
        val length = minOf(chunk, total - offset)
        val body = object : RequestBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        input.channel.position(offset)
                        val buf = ByteArray(64 * 1024)
                        var left = length
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw IOException("Plik skrócił się w trakcie wysyłki")
                            sink.write(buf, 0, n)
                            left -= n
                        }
                    }
                }
            }
        }
        val range = if (total == 0L) "bytes */0" else "bytes $offset-${offset + length - 1}/$total"
        return call { url(session); put(body); header("Content-Range", range) }.use { parseUploadResponse(it) }
    }

    private fun parseUploadResponse(r: Response): UploadState = when {
        r.code == 308 -> {
            // "Range: bytes=0-12345" - serwer ma bajty do 12345 włącznie; brak nagłówka = nic
            val last = r.header("Range")?.substringAfterLast('-')?.toLongOrNull()
            UploadState.Partial(if (last == null) 0 else last + 1)
        }
        r.code == 200 || r.code == 201 -> {
            val j = JSONObject(r.body!!.string())
            UploadState.Done(DriveFile(j.getString("id"), j.optString("md5Checksum").ifEmpty { null }, j.optLong("size")))
        }
        r.code == 404 || r.code == 410 -> UploadState.Expired
        else -> throw DriveException(r.code, r.body?.string().orEmpty().take(300))
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE_FIELDS = "id,md5Checksum,size"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
    }
}
