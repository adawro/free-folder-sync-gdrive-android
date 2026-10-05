package pl.adamw.drivesync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Logowanie własnym klientem OAuth użytkownika (typ "Desktop app" w jego projekcie Google Cloud),
 * tak jak w rclone: przeglądarka -> zgoda -> przekierowanie na http://127.0.0.1:<port> w telefonie,
 * gdzie aplikacja odbiera kod i wymienia go na refresh token (PKCE).
 */
object CustomOAuth {
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val CLIENT_SECRET = "client_secret"
    private const val REFRESH_TOKEN = "refresh_token"

    private val http = OkHttpClient()
    /** Logowanie trwa, gdy użytkownik jest w przeglądarce - nie może zależeć od cyklu życia aktywności. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L

    fun clientSecret(context: Context) = SecretStore(context).get(CLIENT_SECRET)
    fun hasRefreshToken(context: Context) = SecretStore(context).get(REFRESH_TOKEN) != null

    fun saveClient(context: Context, clientId: String, clientSecret: String) {
        val s = Settings(context)
        if (s.customClientId != clientId.trim() || clientSecret(context) != clientSecret.trim()) {
            s.customClientId = clientId.trim()
            SecretStore(context).put(CLIENT_SECRET, clientSecret.trim())
            signOut(context)  // token był wydany innemu klientowi
        }
    }

    fun signOut(context: Context) {
        SecretStore(context).put(REFRESH_TOKEN, null)
        accessToken = null
        Settings(context).needsSignIn = true
    }

    /**
     * Otwiera przeglądarkę i czeka (do 5 min) na przekierowanie. [onDone] dostaje null przy sukcesie
     * albo opis błędu; wołane z wątku w tle.
     */
    fun signIn(context: Context, onDone: (String?) -> Unit) {
        val app = context.applicationContext
        val settings = Settings(app)
        val clientId = settings.customClientId
        val secret = clientSecret(app)
        if (clientId.isEmpty() || secret.isNullOrEmpty()) {
            onDone("Wpisz client ID i client secret")
            return
        }
        pending?.cancel()
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5 * 60 * 1000 }
        val redirect = "http://127.0.0.1:${server.localPort}"
        val verifier = randomString(64)
        val state = randomString(16)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        val url = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")  // zawsze zwraca refresh token
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build()

        pending = scope.launch {
            val error = try {
                val params = server.use { receiveRedirect(it) }
                when {
                    params["state"] != state -> "Niepoprawna odpowiedź (state)"
                    params["error"] != null -> "Google: ${params["error"]}"
                    params["code"] == null -> "Brak kodu autoryzacji"
                    else -> {
                        val json = tokenRequest(
                            "grant_type" to "authorization_code", "code" to params["code"]!!,
                            "client_id" to clientId, "client_secret" to secret,
                            "redirect_uri" to redirect, "code_verifier" to verifier,
                        )
                        val refresh = json.optString("refresh_token")
                        if (refresh.isEmpty()) "Google nie zwrócił refresh tokenu" else {
                            SecretStore(app).put(REFRESH_TOKEN, refresh)
                            remember(json)
                            settings.needsSignIn = false
                            null
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                "Przekroczono czas logowania"
            } catch (e: IOException) {
                "Błąd logowania: ${e.message}"
            }
            onDone(error)
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Odbiera jedno żądanie GET /?code=...&state=... i odpowiada stroną "wróć do aplikacji". */
    private fun receiveRedirect(server: ServerSocket): Map<String, String> {
        server.accept().use { socket ->
            val requestLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
            val target = requestLine.split(' ').getOrNull(1).orEmpty()
            val uri = Uri.parse("http://127.0.0.1$target")
            val ok = uri.getQueryParameter("code") != null
            val html = """<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
                |</head><body style="font-family:sans-serif;padding:24px">
                |<h2>${if (ok) "Połączono z Google Drive" else "Logowanie nieudane"}</h2>
                |<p>Możesz wrócić do aplikacji.</p></body></html>""".trimMargin()
            val body = html.toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
            return uri.queryParameterNames.associateWith { uri.getQueryParameter(it).orEmpty() }
        }
    }

    /** Ważny access token (odświeżany z refresh tokenu); NeedsSignInException, gdy trzeba zalogować się ponownie. */
    @Synchronized
    fun accessToken(context: Context): String {
        accessToken?.takeIf { System.currentTimeMillis() < expiresAt - 60_000 }?.let { return it }
        val settings = Settings(context)
        val refresh = SecretStore(context).get(REFRESH_TOKEN) ?: throw NeedsSignInException()
        val secret = clientSecret(context) ?: throw NeedsSignInException()
        val json = try {
            tokenRequest(
                "grant_type" to "refresh_token", "refresh_token" to refresh,
                "client_id" to settings.customClientId, "client_secret" to secret,
            )
        } catch (e: OAuthException) {
            // invalid_grant: token cofnięty albo wygasł (np. aplikacja w Google Cloud w trybie "Testing")
            if (e.error == "invalid_grant" || e.error == "invalid_client" || e.error == "unauthorized_client") {
                signOut(context)
                throw NeedsSignInException()
            }
            throw e
        }
        return remember(json)
    }

    fun invalidate() {
        accessToken = null
    }

    private fun remember(json: JSONObject): String {
        val token = json.getString("access_token")
        accessToken = token
        expiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        return token
    }

    private class OAuthException(val error: String, message: String) : IOException(message)

    private fun tokenRequest(vararg params: Pair<String, String>): JSONObject {
        val form = FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
            if (!r.isSuccessful) {
                val error = json.optString("error", "http_${r.code}")
                throw OAuthException(error, "Google: $error ${json.optString("error_description")}".trim())
            }
            return json
        }
    }

    private fun randomString(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
