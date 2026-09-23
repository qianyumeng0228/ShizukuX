package af.shizuku.manager.backup

import android.content.Context
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
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /** Generate a timestamp string for the current time. */
    fun generateTimestamp(): String = TIMESTAMP_FORMAT.format(Date())

    /** Format a timestamp for display (e.g. "2026-09-24 15:30"). */
    fun formatForDisplay(timestamp: String): String {
        return try {
            val date = TIMESTAMP_FORMAT.parse(timestamp)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
        } catch (_: Exception) {
            timestamp
        }
    }

    /** Build the snapshot file name for a given data type and timestamp. */
    fun getSnapshotFileName(type: String, timestamp: String): String {
        return "$type.pre-restore.$timestamp.tar.gz"
    }

    /** Record a new snapshot timestamp for a package (newest first, capped at MAX). */
    fun recordSnapshot(context: Context, packageName: String, timestamp: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getSnapshots(context, packageName).toMutableList()
        existing.add(0, timestamp)
        // Remove duplicates and cap
        val deduped = existing.distinct().take(MAX_SNAPSHOTS_PER_APP)
        prefs.edit().putString(packageName, deduped.joinToString(",")).apply()
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
