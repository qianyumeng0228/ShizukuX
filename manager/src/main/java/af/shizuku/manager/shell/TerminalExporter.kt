package af.shizuku.manager.shell

import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Files the terminal guide copies into the user-selected directory. */
object TerminalAssets {
    const val SH_NAME = "rish"
    const val DEX_NAME = "rish_shizuku.dex"
    const val PLUS_NAME = "extra"
    const val SU_NAME = "su"

    val ALL = listOf(SH_NAME, DEX_NAME, PLUS_NAME, SU_NAME)
}

private const val TAG = "TerminalExporter"

/**
 * Copies the rish helper set into [tree], returning `successCount to totalCount`.
 *
 * SAF query + createDocument + asset streaming copy (the dex is several MB) can take hundreds of
 * milliseconds on slow storage, so the whole export runs on IO; callers only post the Toast back.
 */
suspend fun exportTerminalAssets(context: Context, tree: Uri): Pair<Int, Int> =
    withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val doc = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val child = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )

        // Drop stale copies first: createDocument refuses to overwrite, so a second export would
        // silently fail every file if the old ones stayed.
        runCatching {
            cr.query(
                child,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use {
                while (it.moveToNext()) {
                    val id = it.getString(0)
                    val name = it.getString(1)
                    if (name in TerminalAssets.ALL) {
                        DocumentsContract.deleteDocument(
                            cr,
                            DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        )
                    }
                }
            }
        }.onFailure { Timber.tag(TAG).e(it, "Failed to clean previous export") }

        fun writeToDocument(name: String): Boolean {
            val documentUri = try {
                DocumentsContract.createDocument(cr, doc, "application/octet-stream", name)
            } catch (e: Exception) {
                // The parent tree URI can be stale/revoked (removed SD card, cleared permission);
                // createDocument then throws FileNotFoundException (SHIZUKUPLUS-84). Don't crash.
                Timber.tag(TAG).e(e, "Failed to create document for $name (tree unavailable?)")
                return false
            }
            if (documentUri == null) {
                Timber.tag(TAG).e("Failed to create document for $name")
                return false
            }

            return try {
                cr.openOutputStream(documentUri)?.use { outputStream ->
                    context.assets.open(name).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to write $name to document")
                try {
                    DocumentsContract.deleteDocument(cr, documentUri)
                } catch (deleteEx: Exception) {
                    Timber.tag(TAG).e(deleteEx, "Failed to delete incomplete document for $name")
                }
                false
            }
        }

        val results = TerminalAssets.ALL.map { writeToDocument(it) }
        ShizukuSettings.setExportDirUri(tree.toString())
        results.count { it } to results.size
    }
