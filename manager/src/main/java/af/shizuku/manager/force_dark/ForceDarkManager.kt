package af.shizuku.manager.force_dark

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.Logger.LOGGER
import android.util.Base64
import rikka.shizuku.Shizuku

/**
 * Dark for the apps that never shipped one, for exactly as long as they are in front.
 *
 * An app's theme is its own, and nothing outside it can hand it a dark one. What can be done - what
 * every app of this kind does - is hold a device-wide switch while the app that needs it is on
 * screen, and let go when it leaves. Everything here is the bookkeeping around that sentence.
 *
 * Two mechanisms exist, and they are not equally good. Measured on this project's own test device
 * (Xiaomi 13 Pro, Android 16 / SDK 36):
 *
 *  - The force-dark property and settings ([switches]) are what the old developer option wrote. They
 *    were written, the system Settings app was force-stopped and reopened, and it came back
 *    pixel-identical to before. Force dark is off by default for apps targeting API 29 and up and
 *    every Material theme turns it off explicitly, so on a modern phone these switches turn on and
 *    change nothing. They are kept - a phone with older apps on it still answers - but they are
 *    offered as the secondary switch and off by default.
 *
 *  - The system's own night mode ([usesSystemTheme]) is what actually changes pixels here: `cmd
 *    uimode night yes` repaints the whole phone dark within a second, and `no` puts it back.
 *
 * The cost of the second one is stated on the screen rather than hidden: it darkens the entire
 * device, not just the app. That is the bargain, and it is the only one that works.
 *
 * Who holds the switch, though, is a separate question, and it was answered the hard way on this
 * phone. A watch kept inside the app process does not survive being left alone: once the app goes
 * to the background, the ROM freezes the whole process - every thread, foreground service included,
 * `cgroup.freeze` reads 1 while `dumpsys activity processes` still claims the process is not
 * frozen - and the polling loop stops mid-pass. Observed here twice: the app went dark while the
 * user was still looking at it, then never put the phone back because the process that was supposed
 * to do it had been frozen first. A foreground service and the Doze whitelist made no difference.
 *
 * So on a root Shizuku the switch is held by a script outside the app process, started through the
 * root shell and detached from it ([daemonDriven]) - the frozen app cannot stop it, and the
 * daemon reads the same config file the app writes. Where that is not available the in-process
 * watch is still used, which is the right trade on a device whose ROM leaves foreground services
 * alone.
 *
 * What the night mode was before is remembered, because "go back to light" is not the same thing.
 * Measured here as well: with no key written `settings get secure ui_night_mode` prints the string
 * `null` while the device is light, `no` is 1, `yes` is 2, and `auto` is 0 - and `auto` at 00:24 on
 * this phone computed to *dark*, because the custom window runs 19:00-07:00. A restore that writes
 * `auto` would therefore leave a light-mode user permanently dark after the first forced app.
 *
 * One more thing was measured and it matters for the restore: this ROM's `cmd uimode night` does
 * **not** write `ui_night_mode`. After `cmd uimode night yes` the dump says `mNightMode=2 (yes)` while
 * the key still prints `null`, so deleting that key on the way back leaves the phone dark. Absent is
 * therefore restored with `cmd uimode night no`, which is also what the phone was showing when the
 * key was never written.
 */
object ForceDarkManager {

    /**
     * Every key carries the feature's own prefix. These live in the shared settings file, next to
     * the rest of the app's preferences - a bare "enabled" here is the app's own service switch,
     * and writing it would start or stop Shizuku, not this feature.
     */
    private const val PREF_ENABLED = "force_dark_enabled"
    private const val PREF_APPS = "force_dark_apps"
    private const val PREF_SWITCHES = "force_dark_switches"
    private const val PREF_THEME = "force_dark_system_theme"
    private const val PREF_SAVED_NIGHT = "force_dark_saved_night"
    private const val PREF_APPLIED = "force_dark_applied"

    /** Stored value for "the key was not there at all", which is a state and not a value. */
    private const val ABSENT = "absent"

    /** Stored value for "the device was already dark, so nothing was switched". */
    private const val ALREADY_DARK = "already-dark"

    /** The five keys the force-dark developer option writes; a device ignores the ones it lacks. */
    private val FORCE_DARK_KEYS = listOf(
        "global force_dark_mode_on",
        "secure hwui_force_dark",
        "secure aosp_force_dark_mode",
        "secure op_force_dark_entire_world"
    )

    // ---- the detached watch ----------------------------------------------------------

    /**
     * Where the daemon and its files live. `/data/local/tmp` is readable and writable from the
     * Shizuku shell on every ROM, which an app-private directory is not guaranteed to be.
     */
    private const val DAEMON_DIR = "/data/local/tmp/shizukux"
    private const val DAEMON_SCRIPT = "$DAEMON_DIR/force_dark_watch.sh"
    private const val DAEMON_CONF = "$DAEMON_DIR/force_dark.conf"
    private const val DAEMON_STATE = "$DAEMON_DIR/force_dark.state"

    /** How often a running watch re-checks that the daemon is alive. */
    private const val SYNC_INTERVAL_MS = 30_000L

    private const val ASSET_SCRIPT = "force_dark_watch.sh"

    @Volatile private var lastSync = 0L

    /** Cached answer to "is the shell root?", reset whenever Shizuku is not reachable. */
    @Volatile private var rootShell: Boolean? = null

    private val prefs
        get() = ShizukuSettings.getPreferences()

    /** False when the Shizuku service is not running: every call below needs it. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    // ---- the rule ------------------------------------------------------------------

    /** Whether the feature is switched on. Off until somebody says otherwise. */
    fun isEnabled(): Boolean = prefs.getBoolean(PREF_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply()
        if (enabled) {
            rememberNightForStart()
            syncNow()
        } else {
            // Off: the config file is rewritten first so a running daemon lets go by itself, and
            // the restore is also run here because the daemon may not be running at all.
            syncNow()
            applyDirect(false)
        }
    }

    /** The apps to darken while they are open. */
    fun apps(): Set<String> = prefs.getStringSet(PREF_APPS, emptySet()).orEmpty().toSet()

    fun isForced(packageName: String): Boolean = packageName in apps()

    fun setForced(packageName: String, forced: Boolean) {
        if (packageName.isBlank()) return
        val apps = apps().toMutableSet()
        if (forced) apps.add(packageName) else apps.remove(packageName)
        prefs.edit().putStringSet(PREF_APPS, apps).apply()
        syncNow()
    }

    /** Whether anything has a reason to run: the mode is on and names at least one app. */
    fun hasWorkToDo(): Boolean = isEnabled() && apps().isNotEmpty()

    /**
     * Whether the system's night mode is used, which on this device is the only mechanism that
     * changes any pixels.
     */
    fun usesSystemTheme(): Boolean = prefs.getBoolean(PREF_THEME, true)

    fun setUsesSystemTheme(on: Boolean) {
        prefs.edit().putBoolean(PREF_THEME, on).apply()
        syncNow()
    }

    /**
     * Whether the force-dark property and settings are written too.
     *
     * Off by default and honestly labelled: on an Android 16 device they do nothing, and writing
     * four settings rows for nothing leaves somebody else to explain them.
     */
    fun writesSwitches(): Boolean = prefs.getBoolean(PREF_SWITCHES, false)

    /**
     * Turns the property switches on or off, and cleans up after itself.
     *
     * Turning them off deletes what they wrote instead of writing zeroes: zero is a value somebody
     * chose, and a device that never had these keys should not be left with four of them.
     */
    fun setWritesSwitches(on: Boolean) {
        prefs.edit().putBoolean(PREF_SWITCHES, on).apply()
        if (!on) clearSwitches()
    }

    /** Whether a switch is being held by this app, as far as this app is the one holding it. */
    fun isApplied(): Boolean =
        if (daemonDriven()) daemonApplied() else prefs.getBoolean(PREF_APPLIED, false)

    private fun setApplied(applied: Boolean) {
        prefs.edit().putBoolean(PREF_APPLIED, applied).apply()
    }

    /** Whether there is something the next pass would have to put back. */
    fun hasSavedTheme(): Boolean = prefs.getString(PREF_SAVED_NIGHT, null) != null

    private fun savedNight(): String? = prefs.getString(PREF_SAVED_NIGHT, null)

    private fun rememberNight(value: String) {
        prefs.edit().putString(PREF_SAVED_NIGHT, value).apply()
    }

    private fun forgetNight() {
        prefs.edit().remove(PREF_SAVED_NIGHT).apply()
    }

    /**
     * Records what to put back before the first switch, and never overwrites a record that is
     * already there.
     *
     * A missing record while the flag still says "we switched it" is a leftover from a pass that
     * was killed halfway. Treating that as "absent" is the only reading that cannot strand the
     * phone in dark: absent restores to light, which is what the phone showed before this feature
     * ever ran.
     */
    private fun rememberNightForStart() {
        if (hasSavedTheme()) return
        if (!isAvailable()) return
        val origin = when {
            isApplied() -> ABSENT
            isDarkNow() -> ALREADY_DARK
            else -> currentNight()
        }
        rememberNight(origin)
        LOGGER.i("force dark saved the original night mode as $origin")
    }

    /**
     * One pass of the rule: the app in front decides.
     *
     * Derived from the foreground every time rather than tracked as state, so a watch that died
     * mid-switch cannot leave the phone dark with nothing left that thinks it has anything to undo.
     */
    fun applyFor(foreground: String?) {
        if (daemonDriven()) {
            // The switch is the daemon's job; this side only keeps the daemon supplied and alive.
            if (hasWorkToDo()) ensureDaemon() else syncNow()
            return
        }
        val wanted = hasWorkToDo() && foreground != null && foreground in apps()
        if (wanted == isApplied()) return
        if (!applyDirect(wanted)) {
            LOGGER.w("force dark for ${foreground ?: "nothing"} did not take")
        }
    }

    /** Puts everything back, but only when this app is the one holding it. */
    fun restore() {
        if (daemonDriven()) return
        if (!isApplied() && !hasSavedTheme()) return
        applyDirect(false)
    }

    /** Puts everything back whether or not this app set it - for the switch that turns it off. */
    fun restoreAlways() {
        if (daemonDriven()) {
            // The daemon reads the rewritten config and restores by itself; running it here too
            // only makes the phone light a second sooner.
            syncNow()
        }
        applyDirect(false)
    }

    /**
     * Restarts an app so it reads the switches again.
     *
     * Only the property path needs this: those switches are read when a process starts, while the
     * night mode repaints live. Called only when [writesSwitches] is on, so a user toggling an app
     * is not closed out from under themselves for a mechanism that would not have worked anyway.
     */
    fun restart(packageName: String) {
        if (packageName.isBlank() || !writesSwitches()) return
        shell("am force-stop $packageName")
        LOGGER.i("restarted $packageName so it picks the force-dark switches up")
    }

    // ---- the detached watch ----------------------------------------------------------

    /**
     * Whether the shell Shizuku hands out is root, which is what a detached watch needs.
     *
     * Also true for an adb-user Shizuku in principle, but that shell cannot write
     * `/data/local/tmp/shizukux` and cannot outlive the app, so only uid 0 counts here.
     */
    fun daemonAvailable(): Boolean {
        if (!isAvailable()) {
            rootShell = null
            return false
        }
        return rootShell ?: run {
            val root = shell("id -u")?.trim() == "0"
            rootShell = root
            LOGGER.i("force dark detached watch available: $root")
            root
        }
    }

    /** Whether the switch is held outside this process, which is the only mode that survives freezing. */
    fun daemonDriven(): Boolean = usesSystemTheme() && daemonAvailable()

    /** Whether the in-process watch still has work: false once the daemon drives the switch. */
    fun needsInProcessWatch(): Boolean = hasWorkToDo() && !daemonDriven()

    /** What the daemon last reported, for the screen that has to show something true. */
    fun daemonState(): String? =
        if (daemonAvailable()) shell("cat $DAEMON_STATE") else null

    private fun daemonApplied(): Boolean =
        daemonState()?.lineSequence()?.any { it.trim() == "applied=1" } == true

    /** Writes the config now instead of waiting for the next scheduled sync. */
    fun syncNow() {
        lastSync = 0
        ensureDaemon()
    }

    /**
     * Makes sure the script and its config are on disk and one copy of it is running.
     *
     * Idempotent and throttled: this is called from the polling loop, and a shell round trip on
     * every pass would cost more than the switch it drives.
     */
    private fun ensureDaemon() {
        if (!isAvailable()) return
        if (!daemonAvailable()) {
            // Without root there is no detached watch; the in-process path handles everything.
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastSync < SYNC_INTERVAL_MS && lastSync != 0L) return
        lastSync = now

        if (!writeScript()) return
        writeConfig()

        // The script re-execs itself into a new session and returns immediately (see its
        // `--serve` branch). That hop exists because the Shizuku shell this runs in is destroyed
        // as soon as the app drops the handle, and a background child of it went down with it -
        // measured here: nothing survived and the script never even wrote its log. run and
        // destroy are both skipped for this one: it must outlive this call, and its own fork
        // already returned.
        shell("sh $DAEMON_SCRIPT >>$DAEMON_DIR/force_dark_launch.log 2>&1", destroy = false)
    }

    /** Puts the watch script from the APK onto the device; false when it could not be written. */
    private fun writeScript(): Boolean {
        val body = runCatching {
            ShizukuApplication.appContext.assets.open(ASSET_SCRIPT).use { it.readBytes() }
        }.getOrNull() ?: return false
        val encoded = Base64.encodeToString(body, Base64.NO_WRAP)
        return shell(
            "mkdir -p $DAEMON_DIR; " +
                "echo $encoded | base64 -d > $DAEMON_SCRIPT; " +
                "chmod 755 $DAEMON_SCRIPT"
        ) != null
    }

    /**
     * Writes what the daemon acts on: whether it should run at all, which apps are named, and what
     * to put back when it lets go.
     */
    private fun writeConfig() {
        val text = buildString {
            append("enabled=").append(if (hasWorkToDo()) "1" else "0").append('\n')
            append("apps=").append(apps().joinToString(" ")).append('\n')
            append("origin=").append(savedNight() ?: ABSENT).append('\n')
        }
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        shell("mkdir -p $DAEMON_DIR; echo $encoded | base64 -d > $DAEMON_CONF")
    }

    // ---- the switches ---------------------------------------------------------------

    /**
     * Brings the device into the state [on] asks for, and answers whether it got there.
     *
     * The read-back is the answer, the same rule the rest of this app follows: a shell command that
     * exited 0 is not a setting the platform is using, and reporting "dark" over a light screen is
     * the one failure this feature cannot afford - the user is looking at the evidence.
     */
    private fun applyDirect(on: Boolean): Boolean {
        if (!isAvailable()) return false

        val parts = mutableListOf<String>()
        if (writesSwitches()) parts += switches(on)

        if (usesSystemTheme()) {
            if (on) {
                if (isDarkNow()) {
                    // Already dark before anything was asked for: switching would only let a
                    // restore write "light" over a state this app never changed.
                    rememberNight(ALREADY_DARK)
                } else {
                    rememberNight(currentNight())
                    parts += "cmd uimode night yes"
                }
            } else {
                when (val saved = savedNight()) {
                    // The record is gone while the flag says this app switched the device. That
                    // combination can only come from a pass killed halfway, and the phone is
                    // almost certainly dark because of it, so it goes back to light. Writing
                    // nothing here - what the first version did - is what left the test device
                    // permanently dark.
                    null -> parts += "cmd uimode night no"
                    ALREADY_DARK -> Unit
                    ABSENT -> parts += "cmd uimode night no"
                    "0" -> parts += "cmd uimode night auto"
                    "2" -> parts += "cmd uimode night yes"
                    else -> parts += "cmd uimode night no"
                }
            }
        }

        if (parts.isNotEmpty()) {
            val output = shell(parts.joinToString("; "))
            if (output == null) {
                // Nothing was written, so the record that says what to put back must not survive
                // the attempt either.
                if (on) forgetNight()
                return false
            }
        }

        if (!usesSystemTheme()) {
            setApplied(on)
            return true
        }

        // Whatever was written, the platform decides whether the device is dark now.
        val reached = if (on) isDarkNow() else !isDarkNow()
        if (reached) {
            setApplied(on)
            // A restore that did not reach light keeps its record, so the next pass tries again
            // instead of leaving a phone nobody remembers how to un-darken.
            if (!on) forgetNight()
        }
        return reached
    }

    /**
     * Every force-dark key, in one shell line.
     *
     * One round trip instead of five: this runs on an app change, and a switch that lands half a
     * second late lands after the screen it was meant for has already drawn.
     */
    private fun switches(on: Boolean): String {
        val value = if (on) "1" else "0"
        val property = if (on) "true" else "false"
        return "setprop debug.hwui.force_dark $property; " +
            FORCE_DARK_KEYS.joinToString("; ") { key ->
                val (namespace, name) = key.split(' ')
                "settings put $namespace $name $value"
            }
    }

    /** Removes what [switches] wrote, so a device that never had these keys is not left with them. */
    private fun clearSwitches() {
        if (!isAvailable()) return
        shell(
            "setprop debug.hwui.force_dark false; " +
                FORCE_DARK_KEYS.joinToString("; ") { key ->
                    val (namespace, name) = key.split(' ')
                    "settings delete $namespace $name"
                }
        )
    }

    // ---- reading the night mode ------------------------------------------------------

    /**
     * What `ui_night_mode` says, with "the key is not there" kept apart from "the key says 0".
     *
     * `settings get` prints the string `null` for a key that was never written, and that state is
     * restored with `cmd uimode night no` rather than by writing `auto`: on this phone a light-mode
     * user has no key at all, `auto` computes to dark after 19:00, and writing it would hand them a
     * permanently dark phone after the first app they force-dark.
     */
    private fun currentNight(): String {
        val value = shell("settings get secure ui_night_mode")?.trim()
        return if (value.isNullOrBlank() || value == "null") ABSENT else value
    }

    /**
     * Whether the device is dark right now.
     *
     * `cmd uimode night` prints the configured mode; under `auto` that is not the answer, and the
     * computed one is what the screen shows - so the auto case asks the service for it.
     */
    private fun isDarkNow(): Boolean {
        val line = shell("cmd uimode night")?.lowercase() ?: return false
        if (line.contains("yes")) return true
        if (line.contains("no")) return false
        return shell("dumpsys uimode")?.contains("mComputedNightMode=true") == true
    }

    /**
     * The packages that have something to open, as one shell command reads them.
     *
     * The launch intent cannot be asked of the local PackageManager here - on this ROM it sees only
     * this app - so the platform is asked for the activities that answer MAIN/LAUNCHER and the
     * package names are taken out of that report. An app with nothing in the launcher is an app
     * nobody sits in front of, and this rule only acts on what is in front.
     *
     * Null when the platform could not be asked; callers then show everything rather than nothing.
     */
    fun launchable(): Set<String>? {
        val output = shell(
            "cmd package query-activities -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER | grep packageName= | " +
                "sed 's/.*packageName=//' | sort -u"
        ) ?: return null
        val names = output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.none { c -> c.isWhitespace() } }
            .toSet()
        return names.ifEmpty { null }
    }

    /**
     * Runs one shell command as the Shizuku user and answers its stdout, or null on failure.
     *
     * stderr is thrown away at the shell rather than read here: a command that writes enough of it
     * fills the pipe, and the read below would then wait for a process that is waiting for us.
     */
    private fun shell(cmd: String, destroy: Boolean = true): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", "{ $cmd ; } 2>/dev/null"), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) out else null
        } catch (t: Throwable) {
            LOGGER.w("force dark shell failed: ${t.message}")
            null
        } finally {
            if (destroy) {
                try {
                    process?.destroy()
                } catch (_: Throwable) {
                    // A dead binder makes destroy() throw as well; nothing left to clean up then.
                }
            }
        }
    }
}
