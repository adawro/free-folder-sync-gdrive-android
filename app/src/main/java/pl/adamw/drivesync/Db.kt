package pl.adamw.drivesync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stan wysłanego pliku. Ścieżka jest względna wobec wybranego folderu (FolderPair o id pair). */
data class FileRecord(
    val pair: Long,
    val path: String,
    val size: Long,
    val mtime: Long,
    val driveId: String?,
    val md5: String?,
    val uploadedAt: Long,
    /** Rozpoczęta, niedokończona sesja resumable uploadu (ważna tylko dla tego samego size/mtime). */
    val sessionUri: String?,
    val sessionSize: Long,
    val sessionMtime: Long,
)

data class LogEntry(val ts: Long, val error: Boolean, val message: String)

class Db private constructor(context: Context) : SQLiteOpenHelper(context, "drivesync.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        createFiles(db)
        db.execSQL("CREATE TABLE folders(path TEXT PRIMARY KEY, drive_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, error INTEGER, message TEXT)")
    }

    private fun createFiles(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE files(
            pair INTEGER NOT NULL, path TEXT NOT NULL, size INTEGER, mtime INTEGER, drive_id TEXT, md5 TEXT,
            uploaded_at INTEGER, session_uri TEXT, session_size INTEGER, session_mtime INTEGER,
            PRIMARY KEY(pair, path))"""
    )

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Wersja 1 miała jeden folder - jego pliki należą do folderu o id 1 (patrz Settings.pairs)
            db.execSQL("ALTER TABLE files RENAME TO files_v1")
            createFiles(db)
            db.execSQL("INSERT INTO files SELECT 1, * FROM files_v1")
            db.execSQL("DROP TABLE files_v1")
        }
    }

    fun file(pair: Long, path: String): FileRecord? =
        readableDatabase.rawQuery("SELECT * FROM files WHERE pair = ? AND path = ?", arrayOf(pair.toString(), path)).use { c ->
            if (!c.moveToFirst()) return null
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
            fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
            FileRecord(
                l("pair"), s("path"), l("size"), l("mtime"), s("drive_id"), s("md5"), l("uploaded_at"),
                s("session_uri"), l("session_size"), l("session_mtime"),
            )
        }

    fun saveFile(r: FileRecord) {
        writableDatabase.insertWithOnConflict("files", null, ContentValues().apply {
            put("pair", r.pair); put("path", r.path); put("size", r.size); put("mtime", r.mtime)
            put("drive_id", r.driveId); put("md5", r.md5); put("uploaded_at", r.uploadedAt)
            put("session_uri", r.sessionUri); put("session_size", r.sessionSize); put("session_mtime", r.sessionMtime)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Liczba wysłanych plików w każdym folderze (id -> liczba). */
    fun uploadedCounts(): Map<Long, Int> =
        readableDatabase.rawQuery("SELECT pair, COUNT(*) FROM files WHERE uploaded_at > 0 GROUP BY pair", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1)) }
        }

    fun folderId(path: String): String? =
        readableDatabase.rawQuery("SELECT drive_id FROM folders WHERE path = ?", arrayOf(path))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun saveFolder(path: String, driveId: String) {
        writableDatabase.insertWithOnConflict("folders", null, ContentValues().apply {
            put("path", path); put("drive_id", driveId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Folder docelowy zniknął z Drive (usunięty/w koszu) albo zmieniono jego ścieżkę - pliki tego folderu
     * trzeba wysłać od nowa. Pamięć id folderów na Drive czyścimy całą: mógł zniknąć też folder nadrzędny,
     * a odbudowa to tylko kilka zapytań.
     */
    fun forgetDrive(pair: Long) {
        writableDatabase.execSQL("DELETE FROM folders")
        writableDatabase.execSQL(
            "UPDATE files SET drive_id = NULL, md5 = NULL, uploaded_at = 0, session_uri = NULL WHERE pair = ?", arrayOf(pair))
    }

    /** Folder usunięty z aplikacji - zapominamy jego pliki (na Drive zostają). */
    fun forgetPair(pair: Long) {
        writableDatabase.execSQL("DELETE FROM files WHERE pair = ?", arrayOf(pair))
    }

    fun log(message: String, error: Boolean = false) {
        android.util.Log.println(if (error) android.util.Log.ERROR else android.util.Log.INFO, "DriveSync", message)
        writableDatabase.insert("log", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("error", if (error) 1 else 0); put("message", message)
        })
        writableDatabase.execSQL("DELETE FROM log WHERE id <= (SELECT MAX(id) - 300 FROM log)")
    }

    fun recentLog(limit: Int = 50): List<LogEntry> =
        readableDatabase.rawQuery("SELECT ts, error, message FROM log ORDER BY id DESC LIMIT $limit", null).use { c ->
            buildList { while (c.moveToNext()) add(LogEntry(c.getLong(0), c.getInt(1) != 0, c.getString(2))) }
        }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(context: Context): Db =
            instance ?: synchronized(this) { instance ?: Db(context.applicationContext).also { instance = it } }
    }
}
