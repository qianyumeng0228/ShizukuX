package af.shizuku.manager.intents

import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuWait
import rikka.shizuku.Shizuku

/**
 * Starts components the normal launcher cannot reach, through the Shizuku user.
 *
 * This is the layer Android's own "Activities" browsers sit on: every app publishes intents, and
 * some of them (settings deep links, hidden activities) cannot be opened from an ordinary app at
 * all. Failure is reported, never swallowed: `am start` prints `Error:` and that output is what the
 * user sees back.
 */
object IntentManager {

    fun isAvailable(): Boolean = ShizukuWait.ping()

    /** Starts [packageName]'s launcher activity, or answers why it could not. */
    fun launch(packageName: String): String? {
        val resolved = shell("cmd package resolve-activity --brief $packageName | tail -n 1")
            ?: return null
        val component = resolved.lineSequence().lastOrNull { it.contains('/') }?.trim()
            ?: return "no launchable activity"
        return shell("am start -n $component")
    }

    /** Starts an explicit component such as `com.example/.MainActivity`. */
    fun launchComponent(component: String): String? =
        shell("am start -n ${quote(component)}")

    /**
     * Sends an intent built from the fields the user typed.
     *
     * Only the fields that were filled in are passed, so an empty box does not become an empty
     * argument; `-p` (package) and `-n` (component) are mutually exclusive in `am`, and the caller
     * is the one that decides which it wants to use.
     */
    fun send(action: String, data: String, packageName: String, component: String, extras: String): String? {
        val args = StringBuilder()
        if (action.isNotBlank()) args.append("-a ").append(quote(action.trim())).append(' ')
        if (data.isNotBlank()) args.append("-d ").append(quote(data.trim())).append(' ')
        if (component.isNotBlank()) args.append("-n ").append(quote(component.trim())).append(' ')
        else if (packageName.isNotBlank()) args.append("-p ").append(quote(packageName.trim())).append(' ')
        if (extras.isNotBlank()) args.append(extras.trim()).append(' ')
        if (args.isBlank()) return "empty intent"
        val cmd = "am start -f 0x10000000 ${args}"
        return shell(cmd)
    }

    /** A path or value as a single shell word. */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun shell(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", "{ $cmd ; echo ; } 2>&1"), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) out.ifEmpty { "ok" } else out.ifEmpty { null }
        } catch (t: Throwable) {
            LOGGER.w("intent shell failed: ${t.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // A dead binder makes destroy() throw as well; nothing left to clean up then.
            }
        }
    }
}
