package pl.adamw.drivesync

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

data class SyncSummary(val uploaded: Int, val failed: Int, val pending: Int, val alreadyThere: Int = 0)

/**
 * Kopiowanie w jedną stronę: telefon -> Drive. Nic nie jest usuwane na Drive.
 * Plik jest wysyłany, gdy jest nowy albo zmienił się rozmiar/data modyfikacji.
 */
class SyncEngine(
    private val context: Context,
    /** Wołane przed pierwszą wysyłką (worker włącza wtedy powiadomienie) i przy postępie. */
    private val onProgress: suspend (file: String, sent: Long, total: Long) -> Unit,
) {
    private val db = Db.get(context)
    private val settings = Settings(context)
    private val drive = DriveApi(context)
    val requests: Int get() = drive.requests

    suspend fun run(): SyncSummary = lock.withLock {
        val tree = settings.treeUri ?: throw IllegalStateException(context.getString(R.string.err_no_phone_folder))
        val target = settings.drivePath.ifEmpty { throw IllegalStateException(context.getString(R.string.err_no_drive_folder)) }

        val now = System.currentTimeMillis()
        val files = LocalFiles.list(context, tree)
        val todo = files.filter { f ->
            val r = db.file(f.path)
            r == null || r.uploadedAt == 0L || r.driveId == null || r.size != f.size || r.mtime != f.mtime
        }
        // Plik jeszcze zapisywany (np. Signal tworzy kopię) - poczekaj, aż przestanie się zmieniać
        val ready = todo.filter { now - it.mtime > STABLE_MS }
        if (ready.isEmpty()) {
            if (todo.isEmpty()) settings.lastSyncAt = now
            return SyncSummary(0, 0, todo.size)
        }

        checkTargetStillExists(target)
        var uploaded = 0
        var failed = 0
        var alreadyThere = 0
        for (f in ready) {
            try {
                if (uploadFile(f, target)) uploaded++ else alreadyThere++
            } catch (e: NeedsSignInException) {
                throw e
            } catch (e: DriveException) {
                // Limit Google: kolejne pliki dostałyby ten sam błąd - przerwij, worker ponowi później
                if (e.isRateLimit) throw e
                failed++
                db.log("${f.path}: ${e.message}", error = true)
            } catch (e: IOException) {
                failed++
                db.log("${f.path}: ${e.message}", error = true)
            }
        }
        // Tylko gdy wszystko się udało - inaczej ekran pokazywałby świeżą synchronizację mimo błędów
        if (failed == 0) settings.lastSyncAt = System.currentTimeMillis()
        return SyncSummary(uploaded, failed, todo.size - ready.size + failed, alreadyThere)
    }

    /** Ktoś usunął folder docelowy na Drive -> zapominamy stan i wysyłamy wszystko ponownie. */
    private fun checkTargetStillExists(target: String) {
        val id = db.folderId(target) ?: return
        if (!drive.exists(id)) {
            db.log(context.getString(R.string.log_target_gone, target))
            db.forgetDrive()
        }
    }

    /** Id folderu na Drive dla ścieżki "a/b/c" (od "Mój dysk"); brakujące foldery są tworzone. */
    private fun folderId(path: String): String {
        if (path.isEmpty()) return "root"
        db.folderId(path)?.let { return it }
        val parentPath = path.substringBeforeLast('/', "")
        val parent = folderId(parentPath)
        val name = path.substringAfterLast('/')
        val id = drive.findFolder(name, parent) ?: drive.createFolder(name, parent)
        db.saveFolder(path, id)
        return id
    }

    /** Zwraca false, gdy identyczny plik już był na Drive (powiązany bez wysyłania). */
    private suspend fun uploadFile(f: LocalFile, target: String): Boolean {
        val record = db.file(f.path)
        val dir = (target + "/" + f.path).substringBeforeLast('/')
        val parent = folderId(dir)
        val name = f.path.substringAfterLast('/')

        // Pliku nie ma w bazie (np. po reinstalacji), a na Drive leży identyczny - nie wysyłaj duplikatu
        if (record?.driveId == null && record?.sessionUri == null) {
            val same = drive.findFiles(name, parent).filter { it.size == f.size && it.md5 != null }
            if (same.isNotEmpty()) {
                val localMd5 = md5(f)
                same.firstOrNull { it.md5.equals(localMd5, ignoreCase = true) }?.let {
                    db.saveFile(FileRecord(f.path, f.size, f.mtime, it.id, localMd5, System.currentTimeMillis(), null, 0, 0))
                    return false
                }
            }
        }

        // Wznowienie sesji tylko dla dokładnie tej samej wersji pliku
        var session = record?.sessionUri?.takeIf { record.sessionSize == f.size && record.sessionMtime == f.mtime }
        var state: UploadState = UploadState.Partial(0)
        if (session != null) {
            // Sesja, na którą Google odpowiada błędem (np. 403), blokowałaby plik na zawsze - zacznij nową
            state = try {
                drive.queryUpload(session, f.size)
            } catch (e: DriveException) {
                UploadState.Expired
            }
            if (state is UploadState.Expired) session = null
            else if (state is UploadState.Partial && state.offset > 0) db.log(context.getString(R.string.log_resuming, f.path, mb(state.offset)))
        }
        if (session == null) {
            session = drive.startUpload(name, parent, record?.driveId, f.size, f.mime)
                ?: drive.startUpload(name, parent, null, f.size, f.mime)!!  // stary plik usunięty na Drive
            state = UploadState.Partial(0)
            db.saveFile(
                (record ?: FileRecord(f.path, 0, 0, null, null, 0, null, 0, 0))
                    .copy(sessionUri = session, sessionSize = f.size, sessionMtime = f.mtime)
            )
        }

        var stalled = 0
        while (state is UploadState.Partial) {
            onProgress(f.path, state.offset, f.size)
            val before = state.offset
            state = try {
                drive.uploadChunk(session, f.uri, state.offset, f.size, CHUNK)
            } catch (e: DriveException) {
                // Odrzucona sesja: zapomnij ją, następna próba zacznie od nowej
                db.saveFile((db.file(f.path) ?: FileRecord(f.path, 0, 0, null, null, 0, null, 0, 0)).copy(sessionUri = null))
                throw e
            }
            if (state is UploadState.Expired) throw IOException(context.getString(R.string.err_session_expired))
            // Serwer nie przyjmuje danych - nie wysyłaj w kółko tego samego fragmentu
            if (state is UploadState.Partial && state.offset <= before && ++stalled >= 3) {
                throw IOException(context.getString(R.string.err_upload_stalled))
            }
        }
        val done = (state as UploadState.Done).file
        onProgress(f.path, f.size, f.size)

        val localMd5 = md5(f)
        if (done.md5 != null && !done.md5.equals(localMd5, ignoreCase = true)) {
            db.saveFile(FileRecord(f.path, f.size, f.mtime, done.id, null, 0, null, 0, 0))
            throw IOException(context.getString(R.string.err_md5_mismatch))
        }
        db.saveFile(FileRecord(f.path, f.size, f.mtime, done.id, localMd5, System.currentTimeMillis(), null, 0, 0))
        db.log(context.getString(R.string.log_uploaded, f.path, mb(f.size)))
        return true
    }

    private fun md5(f: LocalFile): String {
        val digest = MessageDigest.getInstance("MD5")
        context.contentResolver.openFileDescriptor(f.uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val CHUNK = 8L * 1024 * 1024  // wielokrotność 256 KiB (wymóg Drive)
        private const val STABLE_MS = 2 * 60 * 1000L
        /** Okresowa i ręczna synchronizacja nie mogą wysyłać tego samego pliku równocześnie. */
        private val lock = Mutex()

        fun mb(bytes: Long) = "%.1f MB".format(bytes / 1024.0 / 1024.0)
    }
}
