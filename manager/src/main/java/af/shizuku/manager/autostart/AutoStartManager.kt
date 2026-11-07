package af.shizuku.manager.autostart

import android.content.Context
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

/**
 * Per-app autostart blocking, on the platform's own app-op record.
 *
 * The op is `RUN_ANY_IN_BACKGROUND`: denying it is what the platform's "restrict background
 * activity" does to an app, and it is the only lever a manager can pull without a module. Nothing
 * is remembered here that the platform can be asked for - the list of blocked apps is read out of
 * `cmd appops query-op` in one round trip, and every write is read back before it is reported,
 * because `cmd appops set` exits 0 for an app it did not change.
 *
 * The one thing this app does keep is the gate: the whole list as a single switch. Turning it off
 * allows every app the platform currently reports as denied and writes the names down, so turning
 * it back on puts exactly those apps back. Reporting the gate as off while the platform still
 * denies something is the failure mode the record exists to prevent.
 */
object AutoStartManager {

    /** The op behind autostart: the blunter half of RUN_IN_BACKGROUND. */
    const val OP = "RUN_ANY_IN_BACKGROUND"

    private const val PREF_GATE = "autostart_gate"
    private const val PREF_KEPT = "autostart_kept"

    /** False when the Shizuku service is not running: every call below needs it. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Whether the list is being applied. On until somebody says otherwise. */
    fun isGateOn(): Boolean =
        ShizukuSettings.getPreferences().getBoolean(PREF_GATE, true)

    private fun setGateOn(context: Context, on: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(PREF_GATE, on).apply()
    }

    private fun kept(context: Context): Set<String> =
        ShizukuSettings.getPreferences()
            .getStringSet(PREF_KEPT, emptySet())
            .orEmpty()
            .toSet()

    private fun setKept(context: Context, packages: Set<String>) {
        ShizukuSettings.getPreferences().edit().putStringSet(PREF_KEPT, packages.toSet()).apply()
    }

    /**
     * The installed packages [OP] is denied for, read in one command.
     *
     * `query-op` answers with the op service's own records, which can outlive the app they were
     * made for, so the names are intersected with what the device actually has. A count built
     * from records alone is how a screen says "3 blocked" over a list of one.
     */
    fun blocked(): Set<String> =
        parseReported(shell("cmd appops query-op $OP deny"))
            .intersect(installedPackages())

    /** The installed package names, in one command. */
    private fun installedPackages(): Set<String> =
        shell("pm list packages")
            ?.lineSequence()
            ?.map { it.trim().removePrefix("package:") }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

    /**
     * The names `query-op` printed, and only the ones that are names.
     *
     * A line that is not a package name is not a package: a name this platform does not have makes
     * the command print something else, and a package name never contains a space and never
     * starts with a dash.
     */
    private fun parseReported(output: String?): Set<String> =
        output
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it.none { c -> c.isWhitespace() } && !it.startsWith("-") }
            ?.toSet()
            ?: emptySet()

    /** Whether [packageName] is denied [OP], or null when the platform could not be asked. */
    fun isBlocked(packageName: String): Boolean? = readOp(packageName)

    /**
     * Sets [OP] for one app and answers whether it took.
     *
     * The read-back is the answer: a ROM can accept the verb and leave the record alone.
     */
    fun setBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        val mode = if (blocked) "deny" else "allow"
        shell("cmd appops set $packageName $OP $mode")
        val applied = readOp(packageName) == blocked
        // A write while the gate is off is a user saying "this one too". Turning the gate back on
        // re-applies the list it kept, which the switch's own label then claims is in force - so
        // the list has to actually be back, not just the flag.
        if (applied && blocked && !isGateOn()) setGate(context, true)
        return applied
    }

    /**
     * Arms or disarms the whole list, answering whether it ended up in the state that was asked
     * for.
     *
     * Called off the main thread: every app on the list is one shell call.
     */
    fun setGate(context: Context, on: Boolean): Boolean {
        if (on) {
            kept(context).forEach { app -> setBlockedQuietly(app, true) }
            setGateOn(context, true)
            return true
        }

        val denied = blocked()
        val stayed = denied.filterNot { app -> setBlockedQuietly(app, false) }
        return if (stayed.isEmpty()) {
            setKept(context, denied)
            setGateOn(context, false)
            true
        } else {
            // Some app the platform would not let go of: the list is still in force, and saying
            // otherwise would be the same lie the read-back exists to prevent.
            setGateOn(context, true)
            false
        }
    }

    private fun setBlockedQuietly(packageName: String, blocked: Boolean): Boolean {
        val mode = if (blocked) "deny" else "allow"
        shell("cmd appops set $packageName $OP $mode")
        return readOp(packageName) == blocked
    }

    /**
     * The mode [OP] holds for [packageName] as allow or deny, or null when it was not read.
     *
     * `appops get` prints `RUN_ANY_IN_BACKGROUND: deny; time=+5d7h... ago` on this platform and
     * `<OP>: mode=deny` on some others, so the mode is taken from between the colon and the first
     * semicolon rather than from a fixed offset. Leaving the timestamp attached is what makes a
     * successful write look like a failure.
     */
    private fun readOp(packageName: String): Boolean? {
        val line = shell("cmd appops get $packageName $OP")
            ?.lineSequence()
            ?.firstOrNull { it.contains(OP) }
            ?.trim()
            ?: return null
        val value = line.substringAfter(':', "")
            .substringBefore(';')
            .trim()
            .removePrefix("mode=")
            .trim()
        return when (value) {
            "deny", "ignore" -> true
            "allow" -> false
            else -> null
        }
    }

    /**
     * Runs one shell command as the Shizuku user and answers its stdout, or null when it did not
     * run or did not succeed.
     */
    private fun shell(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            process.errorStream.bufferedReader().readText()
            if (process.waitFor() == 0) out else null
        } catch (t: Throwable) {
            LOGGER.w("autostart shell failed: ${t.message}")
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
