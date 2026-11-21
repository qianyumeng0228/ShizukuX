package af.shizuku.manager.autostart

import android.content.Context
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.Logger.LOGGER
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import rikka.shizuku.Shizuku

/**
 * Per-app autostart blocking, on the platform's own app-op record.
 *
 * The op is `RUN_ANY_IN_BACKGROUND` on AOSP: denying it is what the platform's "restrict
 * background activity" does to an app. ROMs such as ColorOS keep a separate autostart switch with
 * its own op, `AUTO_START`; [op] resolves which record this device actually exposes. Nothing is
 * remembered here that the platform can be asked for - the list of blocked apps is read out of
 * `cmd appops query-op` in one round trip, and every write is read back before it is reported,
 * because `cmd appops set` exits 0 for an app it did not change.
 *
 * The one thing this app does keep is the gate: the whole list as a single switch. Turning it off
 * allows every app the platform currently reports as denied and writes the names down, so turning
 * it back on puts exactly those apps back. Reporting the gate as off while the platform still
 * denies something is the failure mode the record exists to prevent.
 */
object AutoStartManager {

    /**
     * The op behind autostart.
     *
     * `RUN_ANY_IN_BACKGROUND` is the AOSP half of it, but ColorOS (and several other vendor ROMs)
     * keep a separate autostart switch with its own op, `AUTO_START` - that is the one the phone's
     * settings screen reads and writes, and reading the AOSP op instead is how this screen once
     * reported "allowed" for an app the system had blocked. The op is therefore resolved on the
     * device instead of assumed: if `appops get` reports an `AUTO_START` record, that is the stock
     * switch and it wins.
     */
    const val OP = "RUN_ANY_IN_BACKGROUND"

    /** The vendor op, when this ROM has one. */
    private const val VENDOR_OP = "AUTO_START"

    /**
     * Resolved once per process, and only after a round trip that actually answered.
     *
     * The service can be absent when a page opens first. Caching the AOSP fallback then would
     * turn "could not ask yet" into "this ROM has no vendor op" for the rest of the process,
     * so the fallback is returned but not stored until [detectOp] has a real answer.
     */
    @Volatile
    private var resolvedOp: String? = null

    /** Reads a process stream without blocking the caller forever; daemon threads do not pin the app. */
    private val shellExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "autostart-shell").apply { isDaemon = true }
    }

    private const val SHELL_TIMEOUT_SECONDS = 10L

    /** The op this device reports for autostart, vendor one first. */
    fun op(): String = resolvedOp ?: detectOp()?.also { resolvedOp = it } ?: OP

    /** True when the screen is driving the ROM's own autostart record rather than the AOSP one. */
    fun usesVendorOp(): Boolean = resolvedOp == VENDOR_OP

    /**
     * Picks the op by asking the platform which ones it has, or null when the service could not
     * answer. A null result is deliberately not cached: a page that opened before the service
     * came up must be able to ask again and find `AUTO_START`.
     */
    private fun detectOp(): String? {
        if (!isAvailable()) return null
        val packages = installedPackages()
        val sample = packages.firstOrNull { it == "com.android.shell" }
            ?: packages.firstOrNull()
            ?: return null
        val listing = shell("cmd appops get $sample") ?: return null
        if (listing.lineSequence().any { it.trimStart().startsWith("$VENDOR_OP:") }) {
            return VENDOR_OP
        }
        // A build may print only the ops a package actually touched, so an absent line does not
        // prove the op is missing. Asking it directly separates "no record here" from "no such
        // op": an unknown name is answered with an error, an existing one with a (possibly empty)
        // list of packages.
        val probe = shell("cmd appops query-op $VENDOR_OP deny") ?: return null
        val known = !probe.contains("Unknown operation", ignoreCase = true) &&
            !probe.contains("Error", ignoreCase = true) &&
            !probe.contains("error", ignoreCase = true)
        return if (known) VENDOR_OP else OP
    }


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
     * The installed packages [op] is denied for, read in one command.
     *
     * `query-op` answers with the op service's own records, which can outlive the app they were
     * made for, so the names are intersected with what the device actually has. A count built
     * from records alone is how a screen says "3 blocked" over a list of one.
     */
    fun blocked(): Set<String> =
        parseReported(shell("cmd appops query-op ${op()} deny"))
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

    /** Whether [packageName] is denied [op], or null when the platform could not be asked. */
    fun isBlocked(packageName: String): Boolean? = readOp(packageName)

    /**
     * Sets [op] for one app and answers whether it took.
     *
     * The read-back is the answer: a ROM can accept the verb and leave the record alone.
     */
    fun setBlocked(context: Context, packageName: String, blocked: Boolean): Boolean {
        val mode = if (blocked) "deny" else "allow"
        shell("cmd appops set $packageName ${op()} $mode")
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
        shell("cmd appops set $packageName ${op()} $mode")
        return readOp(packageName) == blocked
    }

    /**
     * The mode [op] holds for [packageName] as allow or deny, or null when it was not read.
     *
     * `appops get` prints `RUN_ANY_IN_BACKGROUND: deny; time=+5d7h... ago` on AOSP and
     * `AUTO_START: deny; ...` on ROMs that expose the vendor switch, so the mode is taken from
     * between the colon and the first semicolon rather than from a fixed offset. Leaving the
     * timestamp attached is what makes a successful write look like a failure.
     */
    private fun readOp(packageName: String): Boolean? {
        val name = op()
        val line = shell("cmd appops get $packageName $name")
            ?.lineSequence()
            ?.firstOrNull { it.contains(name) }
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
     *
     * stdout and stderr are merged (`2>&1`), so a command that writes more stderr than the pipe
     * buffer holds cannot block before stdout reaches EOF. Reading the merged stream to EOF is
     * also the exit signal: this project already established that `ShizukuProcess.waitFor()` is
     * unreliable on some OEM builds, so it is deliberately not used here. A timeout destroys a
     * wedged process and returns null instead of leaving the page spinning forever.
     */
    private fun shell(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", "{ $cmd ; } 2>&1"), null, null)
                ?: throw IllegalStateException("Shizuku.newProcess returned null")
            process = p
            val read = shellExecutor.submit<String> {
                p.inputStream.bufferedReader().use { it.readText() }
            }
            try {
                read.get(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS).trim()
            } catch (_: TimeoutException) {
                p.destroy()
                // destroy() closes the stream on most builds; take whatever was already buffered,
                // then give up rather than block the caller on a process that never exits.
                try {
                    read.get(1, TimeUnit.SECONDS).trim()
                } catch (_: Throwable) {
                    null
                }
            }
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
