package pl.adamw.drivesync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stan wysłanego pliku. Ścieżka jest względna wobec wybranego folderu. */
data class FileRecord(
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

class Db private constructor(context: Context) : SQLiteOpenHelper(context, "drivesync.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE files(
                path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, drive_id TEXT, md5 TEXT,
                uploaded_at INTEGER, session_uri TEXT, session_size INTEGER, session_mtime INTEGER)"""
        )
        db.execSQL("CREATE TABLE folders(path TEXT PRIMARY KEY, drive_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE log(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, error INTEGER, message TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun file(path: String): FileRecord? =
        readableDatabase.rawQuery("SELECT * FROM files WHERE path = ?", arrayOf(path)).use { c ->
            if (!c.moveToFirst()) return null
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
            fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
            FileRecord(
                s("path"), l("size"), l("mtime"), s("drive_id"), s("md5"), l("uploaded_at"),
                s("session_uri"), l("session_size"), l("session_mtime"),
            )
        }

    fun saveFile(r: FileRecord) {
        writableDatabase.insertWithOnConflict("files", null, ContentValues().apply {
            put("path", r.path); put("size", r.size); put("mtime", r.mtime)
            put("drive_id", r.driveId); put("md5", r.md5); put("uploaded_at", r.uploadedAt)
            put("session_uri", r.sessionUri); put("session_size", r.sessionSize); put("session_mtime", r.sessionMtime)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun uploadedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM files WHERE uploaded_at > 0", null)
            .use { it.moveToFirst(); it.getInt(0) }

    fun folderId(path: String): String? =
        readableDatabase.rawQuery("SELECT drive_id FROM folders WHERE path = ?", arrayOf(path))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    fun saveFolder(path: String, driveId: String) {
        writableDatabase.insertWithOnConflict("folders", null, ContentValues().apply {
            put("path", path); put("drive_id", driveId)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Folder docelowy zniknął z Drive (usunięty/w koszu) - wszystko trzeba wysłać od nowa. */
    fun forgetDrive() {
        writableDatabase.execSQL("DELETE FROM folders")
        writableDatabase.execSQL("UPDATE files SET drive_id = NULL, md5 = NULL, uploaded_at = 0, session_uri = NULL")
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
