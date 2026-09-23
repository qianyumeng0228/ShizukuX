package af.shizuku.manager.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Records restore operation history for audit and rollback reference.
 *
 * Each entry captures: timestamp, package name, app label, restore scope
 * (internal/external/both), success/failure, whether it was a rollback,
 * and the snapshot timestamp used (for rollbacks).
 *
 * Storage: SharedPreferences file "restore_history", key "entries",
 * value = JSON array string (newest first).
 */
object RestoreHistoryManager {

    private const val PREFS_NAME = "restore_history"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 100
    private val DISPLAY_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    data class Entry(
        val id: String,
        val timestamp: Long,
        val packageName: String,
        val appLabel: String,
        val scope: String,        // "internal", "external", "both"
        val success: Boolean,
        val isRollback: Boolean,
        val snapshotTimestamp: String? = null  // only for rollbacks
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("timestamp", timestamp)
            put("packageName", packageName)
            put("appLabel", appLabel)
            put("scope", scope)
            put("success", success)
            put("isRollback", isRollback)
            put("snapshotTimestamp", snapshotTimestamp ?: "")
        }

        fun formatTime(): String = DISPLAY_FORMAT.format(Date(timestamp))

        fun scopeDisplay(): String = when (scope) {
            "internal" -> "Internal only"
            "external" -> "External only"
            "both" -> "Internal + External"
            else -> scope
        }
    }

    private fun fromJson(obj: JSONObject): Entry = Entry(
        id = obj.optString("id", UUID.randomUUID().toString()),
        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
        packageName = obj.optString("packageName", ""),
        appLabel = obj.optString("appLabel", ""),
        scope = obj.optString("scope", "both"),
        success = obj.optBoolean("success", false),
        isRollback = obj.optBoolean("isRollback", false),
        snapshotTimestamp = obj.optString("snapshotTimestamp", "").ifEmpty { null }
    )

    /** Add a new restore history entry (newest first, capped at MAX_ENTRIES). */
    fun addEntry(context: Context, entry: Entry) {
        val entries = getEntries(context).toMutableList()
        entries.add(0, entry)
        val capped = entries.take(MAX_ENTRIES)
        saveEntries(context, capped)
    }

    /** Get all restore history entries, newest first. */
    fun getEntries(context: Context): List<Entry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Delete a single entry by id. */
    fun deleteEntry(context: Context, id: String) {
        val remaining = getEntries(context).filter { it.id != id }
        saveEntries(context, remaining)
    }

    /** Clear all history. */
    fun clearAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun saveEntries(context: Context, entries: List<Entry>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
