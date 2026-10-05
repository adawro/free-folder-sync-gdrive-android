package pl.adamw.drivesync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

data class LocalFile(
    /** Ścieżka względem wybranego folderu, np. "2026/signal-2026-10-05.backup". */
    val path: String,
    val uri: Uri,
    val size: Long,
    val mtime: Long,
    val mime: String,
)

/** Lista plików w folderze wybranym przez SAF (rekurencyjnie, przez DocumentsContract - szybciej niż DocumentFile). */
object LocalFiles {
    private val COLUMNS = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    )

    fun list(context: Context, tree: Uri): List<LocalFile> {
        val out = mutableListOf<LocalFile>()
        walk(context, tree, DocumentsContract.getTreeDocumentId(tree), "", out)
        return out
    }

    private fun walk(context: Context, tree: Uri, docId: String, prefix: String, out: MutableList<LocalFile>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        context.contentResolver.query(children, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                val mime = c.getString(2) ?: "application/octet-stream"
                if (name.startsWith(".")) continue  // pliki tymczasowe/ukryte
                if (mime == Document.MIME_TYPE_DIR) {
                    walk(context, tree, id, "$prefix$name/", out)
                } else {
                    out += LocalFile(
                        "$prefix$name", DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        c.getLong(3), c.getLong(4), mime,
                    )
                }
            }
        }
    }

    /** Nazwa wybranego folderu (do wyświetlenia i jako domyślny folder na Drive). */
    fun treeName(context: Context, tree: Uri): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return context.contentResolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }
}
