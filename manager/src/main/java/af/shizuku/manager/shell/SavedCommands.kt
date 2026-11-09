package af.shizuku.manager.shell

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Storage for the commands kept in the terminal console.
 *
 * The console runs a single command and is then done - whatever was typed disappears with the
 * output. Everything worth repeating ends up pasted from somewhere else every time, so the ones
 * that matter are kept here: a small ordered list in its own preference file, so it never mixes
 * with the settings blob (and survives a settings reset, which is the point of keeping them).
 */
object SavedCommands {

    private const val PREFS = "terminal_saved_commands"
    private const val KEY = "commands"

    /** Enough to cover real daily use without turning the console into a list screen. */
    const val MAX = 20

    data class Entry(val name: String, val command: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): MutableList<Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val command = o.optString("c")
                if (command.isEmpty()) continue
                out.add(Entry(o.optString("n").ifEmpty { command }, command))
            }
            out
        } catch (e: Throwable) {
            // A hand-edited or truncated file must not take the whole console down with it.
            mutableListOf()
        }
    }

    fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.take(MAX).forEach { entry ->
            arr.put(JSONObject().put("n", entry.name).put("c", entry.command))
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** Stores [command] under [name]; false when that exact command is already stored. */
    fun add(context: Context, name: String, command: String): Boolean {
        val list = load(context)
        if (list.any { it.command == command }) return false
        // Newest first: the last thing you saved is the one you are most likely to need next.
        list.add(0, Entry(name.ifBlank { command }, command))
        save(context, list)
        return true
    }

    fun remove(context: Context, command: String) {
        val list = load(context)
        list.removeAll { it.command == command }
        save(context, list)
    }

    fun rename(context: Context, command: String, newName: String) {
        val list = load(context)
        val index = list.indexOfFirst { it.command == command }
        if (index < 0) return
        list[index] = list[index].copy(name = newName.ifBlank { command })
        save(context, list)
    }
}
