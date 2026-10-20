package pl.adamw.drivesync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

/** pending = czekające na wysyłkę (w tym nieudane), waiting = z nich te jeszcze zapisywane (za świeże). */
data class SyncSummary(val uploaded: Int, val failed: Int, val pending: Int, val alreadyThere: Int = 0, val waiting: Int = 0) {
    operator fun plus(o: SyncSummary) = SyncSummary(uploaded + o.uploaded, failed + o.failed, pending + o.pending,
        alreadyThere + o.alreadyThere, waiting + o.waiting)
}

/**
 * Kopiowanie w jedną stronę: telefon -> Drive, folder po folderze (Settings.pairs). Nic nie jest usuwane na Drive.
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

    /** only = id folderów do sprawdzenia (przycisk przy folderze, wysyłka od razu); null = wszystkie. */
    suspend fun run(only: Set<Long>? = null): SyncSummary = lock.withLock {
        val pairs = settings.pairs.ifEmpty { throw IllegalStateException(context.getString(R.string.err_no_phone_folder)) }
            .filter { only == null || it.id in only }
        var total = SyncSummary(0, 0, 0)
        for (pair in pairs) {
            val s = try {
                runPair(pair)
            } catch (e: NeedsSignInException) {
                throw e
            } catch (e: CancellationException) {
                throw e  // worker zatrzymany przez system - to nie błąd folderu
            } catch (e: DriveException) {
                if (e.isRateLimit) throw e
                db.log("${label(pair)}: ${e.message}", error = true)
                SyncSummary(0, 1, 0)
            } catch (e: Exception) {
                // Np. folder w telefonie usunięty albo cofnięty dostęp - pozostałe foldery idą dalej
                db.log(context.getString(R.string.log_folder_failed, label(pair), e.message ?: e.toString()), error = true)
                SyncSummary(0, 1, 0)
            }
            total += s
        }
        // Tylko bez błędów - inaczej ekran pokazywałby świeżą synchronizację mimo błędów. Same pliki
        // jeszcze zapisywane nie przeszkadzają, o ile coś innego poszło (jak przy jednym folderze).
        // Tylko po sprawdzeniu wszystkich folderów: budzik nadrabia wysyłkę, gdy lastSyncAt jest starsze.
        if (only == null && total.failed == 0 && (total.pending == 0 || total.uploaded + total.alreadyThere > 0)) {
            settings.lastSyncAt = System.currentTimeMillis()
        }
        return total
    }

    /** Nazwa folderu w dzienniku i powiadomieniu: ostatni człon ścieżki na Drive. */
    private fun label(pair: FolderPair) = pair.drivePath.substringAfterLast('/').ifEmpty { "?" }

    private suspend fun runPair(pair: FolderPair): SyncSummary {
        val target = pair.drivePath.ifEmpty { throw IllegalStateException(context.getString(R.string.err_no_drive_folder)) }
        val now = System.currentTimeMillis()
        val files = LocalFiles.list(context, pair.treeUri)
        val todo = files.filter { f ->
            val r = db.file(pair.id, f.path)
            r == null || r.uploadedAt == 0L || r.driveId == null || r.size != f.size || r.mtime != f.mtime
        }
        // Plik jeszcze zapisywany (np. Signal tworzy kopię) - poczekaj, aż przestanie się zmieniać
        val ready = todo.filter { now - it.mtime > STABLE_MS }
        if (ready.isEmpty()) return SyncSummary(0, 0, todo.size, waiting = todo.size)

        checkTargetStillExists(pair)
        var uploaded = 0
        var failed = 0
        var alreadyThere = 0
        for (f in ready) {
            try {
                if (uploadFile(pair, f)) uploaded++ else alreadyThere++
            } catch (e: NeedsSignInException) {
                throw e
            } catch (e: DriveException) {
                // Limit Google: kolejne pliki dostałyby ten sam błąd - przerwij, worker ponowi później
                if (e.isRateLimit) throw e
                failed++
                db.log("${label(pair)}/${f.path}: ${e.message}", error = true)
            } catch (e: IOException) {
                failed++
                db.log("${label(pair)}/${f.path}: ${e.message}", error = true)
            }
        }
        return SyncSummary(uploaded, failed, todo.size - ready.size + failed, alreadyThere, todo.size - ready.size)
    }

    /** Ktoś usunął folder docelowy na Drive -> zapominamy stan i wysyłamy wszystko ponownie. */
    private fun checkTargetStillExists(pair: FolderPair) {
        val id = db.folderId(pair.drivePath) ?: return
        if (!drive.exists(id)) {
            db.log(context.getString(R.string.log_target_gone, pair.drivePath))
            db.forgetDrive(pair.id)
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
    private suspend fun uploadFile(pair: FolderPair, f: LocalFile): Boolean {
        val record = db.file(pair.id, f.path)
        val shown = "${label(pair)}/${f.path}"
        val dir = (pair.drivePath + "/" + f.path).substringBeforeLast('/')
        val parent = folderId(dir)
        val name = f.path.substringAfterLast('/')

        // Pliku nie ma w bazie (np. po reinstalacji), a na Drive leży identyczny - nie wysyłaj duplikatu
        if (record?.driveId == null && record?.sessionUri == null) {
            val same = drive.findFiles(name, parent).filter { it.size == f.size && it.md5 != null }
            if (same.isNotEmpty()) {
                val localMd5 = md5(f)
                same.firstOrNull { it.md5.equals(localMd5, ignoreCase = true) }?.let {
                    db.saveFile(FileRecord(pair.id, f.path, f.size, f.mtime, it.id, localMd5, System.currentTimeMillis(), null, 0, 0))
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
            else if (state is UploadState.Partial && state.offset > 0) db.log(context.getString(R.string.log_resuming, shown, mb(state.offset)))
        }
        if (session == null) {
            session = drive.startUpload(name, parent, record?.driveId, f.size, f.mime)
                ?: drive.startUpload(name, parent, null, f.size, f.mime)!!  // stary plik usunięty na Drive
            state = UploadState.Partial(0)
            db.saveFile(
                (record ?: FileRecord(pair.id, f.path, 0, 0, null, null, 0, null, 0, 0))
                    .copy(sessionUri = session, sessionSize = f.size, sessionMtime = f.mtime)
            )
        }

        var stalled = 0
        while (state is UploadState.Partial) {
            onProgress(shown, state.offset, f.size)
            val before = state.offset
            state = try {
                drive.uploadChunk(session, f.uri, state.offset, f.size, CHUNK)
            } catch (e: DriveException) {
                // Odrzucona sesja: zapomnij ją, następna próba zacznie od nowej
                db.saveFile((db.file(pair.id, f.path) ?: FileRecord(pair.id, f.path, 0, 0, null, null, 0, null, 0, 0)).copy(sessionUri = null))
                throw e
            }
            if (state is UploadState.Expired) throw IOException(context.getString(R.string.err_session_expired))
            // Serwer nie przyjmuje danych - nie wysyłaj w kółko tego samego fragmentu
            if (state is UploadState.Partial && state.offset <= before && ++stalled >= 3) {
                throw IOException(context.getString(R.string.err_upload_stalled))
            }
        }
        val done = (state as UploadState.Done).file
        onProgress(shown, f.size, f.size)

        val localMd5 = md5(f)
        if (done.md5 != null && !done.md5.equals(localMd5, ignoreCase = true)) {
            db.saveFile(FileRecord(pair.id, f.path, f.size, f.mtime, done.id, null, 0, null, 0, 0))
            throw IOException(context.getString(R.string.err_md5_mismatch))
        }
        db.saveFile(FileRecord(pair.id, f.path, f.size, f.mtime, done.id, localMd5, System.currentTimeMillis(), null, 0, 0))
        db.log(context.getString(R.string.log_uploaded, shown, mb(f.size)))
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
