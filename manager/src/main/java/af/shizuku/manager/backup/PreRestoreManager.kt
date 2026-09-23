package af.shizuku.manager.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages pre-restore snapshot metadata.
 *
 * Snapshots are stored in SAF with timestamp-based names:
 *   data.pre-restore.20260924-1530.tar.gz
 *   external.pre-restore.20260924-1530.tar.gz
 *
 * This class records which timestamps exist per package (in SharedPreferences)
 * so the rollback UI can list them without scanning the SAF tree.
 *
 * Storage: SharedPreferences file "pre_restore_snapshots", key = packageName,
 * value = comma-separated timestamps (newest first).
 */
object PreRestoreManager {

    private const val PREFS_NAME = "pre_restore_snapshots"
    private const val MAX_SNAPSHOTS_PER_APP = 10
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US)
    private val LEGACY_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Generate a timestamp string for the current time. */
    fun generateTimestamp(): String = TIMESTAMP_FORMAT.format(Date())

    /** Format a timestamp for display (e.g. "2026-09-24 15:30"). */
    fun formatForDisplay(timestamp: String): String {
        return try {
            val date = try {
                TIMESTAMP_FORMAT.parse(timestamp)
            } catch (_: Exception) {
                LEGACY_FORMAT.parse(timestamp)  // backward compat with pre-k2026 second-precision timestamps
            }
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(date)
        } catch (_: Exception) {
            timestamp
        }
    }

    /** Build the snapshot file name for a given data type and timestamp. */
    fun getSnapshotFileName(type: String, timestamp: String): String {
        return "$type.pre-restore.$timestamp.tar.gz"
    }

    /**
     * Record a new snapshot timestamp for a package (newest first, capped at MAX).
     * When [safTreeUri] and [cr] are provided, evicted snapshots (beyond MAX) have their
     * SAF files best-effort deleted to avoid unbounded storage growth.
     */
    fun recordSnapshot(
        context: Context, packageName: String, timestamp: String,
        safTreeUri: Uri? = null, cr: ContentResolver? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getSnapshots(context, packageName).toMutableList()
        existing.add(0, timestamp)
        // Remove duplicates and cap; collect evicted timestamps for file deletion
        val dedupedAll = existing.distinct()
        val evicted = if (dedupedAll.size > MAX_SNAPSHOTS_PER_APP)
            dedupedAll.drop(MAX_SNAPSHOTS_PER_APP) else emptyList()
        val deduped = dedupedAll.take(MAX_SNAPSHOTS_PER_APP)
        prefs.edit().putString(packageName, deduped.joinToString(",")).apply()
        // Best-effort delete evicted snapshot files from SAF
        if (safTreeUri != null && cr != null && evicted.isNotEmpty()) {
            for (evictedTs in evicted) {
                deleteSnapshotFilesSaf(packageName, evictedTs, safTreeUri, cr)
            }
        }
    }

    /** Best-effort delete data+external snapshot files for a timestamp from SAF. */
    private fun deleteSnapshotFilesSaf(
        packageName: String, timestamp: String,
        safTreeUri: Uri, cr: ContentResolver
    ) {
        try {
            val treeId = DocumentsContract.getTreeDocumentId(safTreeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(safTreeUri, treeId)
            // Try per-package directory first, then flat-name fallback
            val pkgDirId = queryDocumentId(childrenUri, cr, packageName)
            val searchUris = if (pkgDirId != null) {
                listOf(DocumentsContract.buildChildDocumentsUriUsingTree(
                    safTreeUri, pkgDirId))
            } else {
                listOf(childrenUri)  // flat name: {pkg}_data.pre-restore.{ts}.tar.gz
            }
            for (searchUri in searchUris) {
                for (type in listOf("data", "external")) {
                    val fileName = if (pkgDirId != null)
                        getSnapshotFileName(type, timestamp)
                    else
                        "${packageName}_${getSnapshotFileName(type, timestamp)}"
                    val fileId = queryDocumentId(searchUri, cr, fileName)
                    if (fileId != null) {
                        try {
                            DocumentsContract.deleteDocument(cr,
                                DocumentsContract.buildDocumentUriUsingTree(safTreeUri, fileId))
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {
            // Best-effort: failure to delete old snapshot files is non-fatal
        }
    }

    /** Query a child document by displayName, returning its documentId or null. */
    private fun queryDocumentId(childrenUri: Uri, cr: ContentResolver, displayName: String): String? {
        return try {
            cr.query(childrenUri, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ), null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) == displayName) return@use cursor.getString(0)
                }
                null
            }
        } catch (_: Exception) { null }
    }

    /** Get all snapshot timestamps for a package, newest first. */
    fun getSnapshots(context: Context, packageName: String): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(packageName, null) ?: return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    /** Get the newest snapshot timestamp for a package, or null if none. */
    fun getNewestSnapshot(context: Context, packageName: String): String? {
        return getSnapshots(context, packageName).firstOrNull()
    }

    /** Clear all snapshot records for a package. */
    fun clearSnapshots(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(packageName).apply()
    }

    /** Remove a specific snapshot timestamp from a package's records. */
    fun removeSnapshot(context: Context, packageName: String, timestamp: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remaining = getSnapshots(context, packageName).filter { it != timestamp }
        if (remaining.isEmpty()) {
            prefs.edit().remove(packageName).apply()
        } else {
            prefs.edit().putString(packageName, remaining.joinToString(",")).apply()
        }
    }
}
