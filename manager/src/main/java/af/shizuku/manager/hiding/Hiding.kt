package af.shizuku.manager.hiding

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.provider.Settings
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * The settings, what they were before, and the apps that say when to lie.
 *
 * Each signal keeps two things: what its keys said before this app touched them, and the apps it
 * is hidden for. The apps are a rule and not a state - a listed app means "hide this while that
 * app is in front" - and [reconcile] is the whole of the rule: read the app in the foreground,
 * hide the signals that name it, put back the rest.
 *
 * Nothing here trusts a write. Every change is read back and a signal counts as hidden only when
 * the device says it is, which is what lets a list say it did not take instead of showing a state
 * the platform never agreed to. What the values were is remembered per signal and restored
 * exactly, because the point is putting things back: an accessibility list is the user's own
 * services and not something to guess at, and deleting a key is the only honest way to restore
 * one that was never set at all.
 */
object Hiding {

    private const val PREF_NAME = "hiding"
    private const val PREFIX_ORIGINALS = "originals_"
    private const val PREFIX_APPS = "apps_"
    private const val PREF_PAUSED = "paused"
    private const val PREF_DISABLED = "disabled_signals"
    private const val PREF_HIDDEN_SINCE = "hidden_since"
    private const val PREF_RESTART_ON_OPEN = "restart_on_open"
    private const val PREF_VPN_CLIENT = "vpn_client"
    private const val PREF_VPN_CHOSEN = "vpn_chosen"

    const val ACTION_RESTORE_HIDING = "xyz.shizuku.extra.api.action.RESTORE_HIDING"
    const val ACTION_RESUME_HIDING = "xyz.shizuku.extra.api.action.RESUME_HIDING"

    /** Enough records to see past an app that was killed for memory and restarted. */
    private const val EXIT_RECORDS = 5

    private const val FIRST_LOOK_BACK_MILLIS = 10 * 60 * 1000L

    private val FOREGROUND = Regex("""\bu\d+\s+([A-Za-z0-9_.]+)/""")

    private val prefs: SharedPreferences
        get() = ShizukuApplication.appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun originalsPref(signal: Signal) = "$PREFIX_ORIGINALS${signal.name}"

    private fun appsPref(signal: Signal) = "$PREFIX_APPS${signal.name}"

    // ---- reading -----------------------------------------------------------------

    /**
     * What a key says now, or null when it was never set.
     *
     * The settings provider answers null for a key that is not there and an empty string for one
     * that is there and empty - which is exactly what an emptied accessibility list looks like -
     * so the two are kept apart here rather than two screens up.
     */
    fun readKey(key: SettingKey): String? = runCatching {
        when (key.namespace) {
            "global" -> Settings.Global.getString(ShizukuApplication.appContext.contentResolver, key.name)
            "secure" -> Settings.Secure.getString(ShizukuApplication.appContext.contentResolver, key.name)
            else -> null
        }
    }.getOrNull()

    /** Whether the shell can still be asked anything. */
    fun shellAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Whether the foreground can be read at all, which is whether the watch can watch.
     *
     * Usage access answers it with nothing running, so the watch survives Shizuku being down.
     * Without the grant the shell is the only answer, and a watch that cannot see must stand down
     * rather than guess.
     */
    fun canWatch(): Boolean = hasUsageAccess() || shellAvailable()

    /** A signal is hidden only when every one of its keys says so, or when the tunnel is down. */
    fun isHidden(signal: Signal): Boolean = when (signal) {
        Signal.VPN -> vpnTakenDown()
        else -> signal.keys.all { readKey(it) == it.hidden }
    }

    /** The apps this signal is hidden for. */
    fun appsFor(signal: Signal): Set<String> =
        prefs.getStringSet(appsPref(signal), emptySet()).orEmpty().toSet()

    /** When the settings were first hidden for the app in front, or 0 when nothing is hidden. */
    private fun hiddenSince(): Long = prefs.getLong(PREF_HIDDEN_SINCE, 0L)

    private fun forgetHiddenSince() {
        prefs.edit().remove(PREF_HIDDEN_SINCE).apply()
    }

    /**
     * Whether the user closed [packageName] since [sinceMillis].
     *
     * Needs DUMP: without it the platform answers only about this app, and the answer here is
     * quietly empty rather than wrong.
     */
    private fun closedByUserSince(packageName: String, sinceMillis: Long): Boolean {
        if (sinceMillis == 0L) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return runCatching {
            val manager = ShizukuApplication.appContext.getSystemService(ActivityManager::class.java)
                ?: return false
            manager.getHistoricalProcessExitReasons(packageName, 0, EXIT_RECORDS)
                .any { info ->
                    info.timestamp >= sinceMillis &&
                        info.reason == ApplicationExitInfo.REASON_USER_REQUESTED
                }
        }.getOrDefault(false)
    }

    /**
     * Whether any list is both switched on and has an app on it - which is when there is
     * something for the watch to do at all.
     */
    fun hasWorkToDo(): Boolean =
        Signal.entries.any { isSignalEnabled(it) && appsFor(it).isNotEmpty() }

    /** Whether hiding is doing anything: a mode is on, it names an app, and nobody suspended it. */
    fun isActive(): Boolean = hasWorkToDo() && !isPaused()

    /** Number of apps across every enabled list, for the home card and the page header. */
    fun listedCount(): Int =
        Signal.entries.filter { isSignalEnabled(it) }.sumOf { appsFor(it).size }

    /**
     * Whether a whole mode is switched on.
     *
     * Off is stored rather than on, so a list that existed before this switch did is on by
     * default and nothing has to be migrated.
     */
    fun isSignalEnabled(signal: Signal): Boolean = signal.name !in disabledSignals()

    fun setSignalEnabled(signal: Signal, enabled: Boolean) {
        val disabled = disabledSignals().toMutableSet()
        if (enabled) disabled.remove(signal.name) else disabled.add(signal.name)
        prefs.edit().putStringSet(PREF_DISABLED, disabled).apply()
    }

    private fun disabledSignals(): Set<String> =
        prefs.getStringSet(PREF_DISABLED, emptySet()).orEmpty().toSet()

    /**
     * Whether hiding is suspended.
     *
     * This is what the notification's Restore leaves behind: the list is a rule about an app and
     * not about this moment, and a user who asks for their settings back while the app is still
     * open means now.
     */
    fun isPaused(): Boolean = prefs.getBoolean(PREF_PAUSED, false)

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(PREF_PAUSED, paused).apply()
    }

    /**
     * Whether a listed app is restarted the moment it comes to the front.
     *
     * Off by default, because of what it costs: the app is force-stopped, so whatever it had open
     * is gone. It exists because a hiding list cannot help an app that reads its settings during
     * startup - the list is applied once the app is already in front, after its first read.
     */
    fun isRestartOnOpenEnabled(): Boolean = prefs.getBoolean(PREF_RESTART_ON_OPEN, false)

    fun setRestartOnOpen(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_RESTART_ON_OPEN, enabled).apply()
    }

    // ---- the rule ----------------------------------------------------------------

    /** Whether the platform will say which app is in front. */
    fun hasUsageAccess(): Boolean = runCatching {
        val appOps = ShizukuApplication.appContext.getSystemService(AppOpsManager::class.java)
            ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ShizukuApplication.appContext.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                ShizukuApplication.appContext.packageName
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /**
     * The app in the foreground, or null.
     *
     * Asked of the platform rather than the shell, because a shell read is the first thing to die
     * when the setting being hidden is the one carrying the shell. Usage events come from the
     * platform, need nothing running, and are answered while Shizuku is gone.
     *
     * Only the events since the last look are read, and the answer is remembered: an app can be
     * in front longer than any window one could query, so asking "what came to the front in the
     * last ten minutes" would decide nothing is in front exactly when the user has been sitting
     * in the banking app for eleven.
     */
    fun foregroundPackage(): String? =
        if (hasUsageAccess()) foregroundFromUsage() else foregroundFromShell()

    private var lastEventQuery = 0L
    private var cachedForeground: String? = null

    /**
     * The app the last [reconcile] held responsible, after the "user closed it" check.
     *
     * Kept so the other per-app rule on the same pass - force dark - asks about the same app this
     * pass already decided on instead of reading the foreground a second time and possibly reading
     * a different answer.
     */
    @Volatile
    var lastInFront: String? = null
        private set

    private fun foregroundFromUsage(): String? {
        val manager = ShizukuApplication.appContext.getSystemService(UsageStatsManager::class.java)
            ?: return cachedForeground

        val now = System.currentTimeMillis()
        val since = if (lastEventQuery == 0L) now - FIRST_LOOK_BACK_MILLIS else lastEventQuery
        val events = runCatching { manager.queryEvents(since, now) }.getOrNull()
            ?: return cachedForeground

        val event = UsageEvents.Event()
        var foreground = cachedForeground
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> foreground = event.packageName
                // Nothing is in front while an app is on its way out, which is the state a recents
                // swipe and a return to the home screen both pass through.
                UsageEvents.Event.ACTIVITY_PAUSED -> if (foreground == event.packageName) {
                    foreground = null
                }
            }
        }

        lastEventQuery = now
        cachedForeground = foreground
        return foreground
    }

    /**
     * The same question asked of the shell, for a device with no usage access.
     *
     * One dumpsys, filtered on the device so only the one line comes back over the binder.
     */
    private fun foregroundFromShell(): String? {
        val line = shell(
            "dumpsys activity activities 2>/dev/null | " +
                "grep -m1 -E 'topResumedActivity|mResumedActivity'"
        ) ?: return null
        // topResumedActivity=ActivityRecord{91967062 u0 com.example/.MainActivity t429}
        return FOREGROUND.find(line)?.groupValues?.get(1)
    }

    /**
     * One pass of the rule: the app in front decides.
     *
     * Everything is derived from the foreground each time rather than tracked as state, which is
     * what makes this recoverable: whatever is hidden and should not be is put back on the next
     * pass, including after the process was killed in the middle of a hide.
     */
    fun reconcile(): String? {
        val foreground = foregroundPackage()

        // An app the user closed counts as gone even when the platform still names it the last
        // app to come to the front: a swipe out of recents does not always leave a pause behind
        // it, and the watch would go on hiding for an app that is not there.
        val inFront = foreground?.takeIf { !closedByUserSince(it, hiddenSince()) }
        lastInFront = inFront

        var hidingFor: String? = null

        Signal.entries.forEach { signal ->
            // A mode somebody switched off is put back and left alone, list and all: turning it
            // off is a statement about now, not about the apps named on it.
            if (!isSignalEnabled(signal)) {
                if (isHidden(signal)) restore(signal)
                return@forEach
            }

            val apps = appsFor(signal)
            if (apps.isEmpty()) return@forEach

            val wanted = inFront != null && inFront in apps
            when {
                wanted -> {
                    if (!isHidden(signal)) hide(signal)
                    // Only a signal that is really hidden names the app, so the notification says
                    // what is true rather than what was asked for.
                    if (isHidden(signal)) hidingFor = inFront
                }

                // The values go back; the list stays, because the list is the rule and the reason
                // this happened, not a record of it.
                //
                // Without a record there is still no excuse for leaving it hidden, so the
                // signal's own visible value is written instead: something outside this app - the
                // platform, or a Shizuku restart - can hide a key this app never touched, and a
                // watch that knows a setting is hidden and has nothing to write would leave it
                // hidden for good.
                isHidden(signal) -> if (hasRecord(signal)) restore(signal) else reveal(signal)
            }
        }

        if (hidingFor == null) forgetHiddenSince()
        return hidingFor
    }

    // ---- changing ----------------------------------------------------------------

    /**
     * Puts one app on or off a signal's list.
     *
     * Membership is a rule, so it is written down whether or not the setting is hidden at this
     * moment: whether it should be hidden is a question only the foreground can answer.
     */
    fun setOnList(signal: Signal, packageName: String, listed: Boolean): Boolean {
        val apps = appsFor(signal).toMutableSet()
        if (listed) apps.add(packageName) else apps.remove(packageName)
        prefs.edit().putStringSet(appsPref(signal), apps).apply()
        // Changing a list is asking for the rule to run, so it also ends a suspension.
        setPaused(false)
        return appsFor(signal).contains(packageName) == listed
    }

    /** Takes an app off every list, which is what the notification's Restore does. */
    fun unlist(packageName: String): Boolean {
        var changed = false
        Signal.entries.forEach { signal ->
            if (setOnList(signal, packageName, false)) changed = true
        }
        return changed
    }

    /**
     * Hides one signal, remembering what each of its keys was first.
     *
     * The originals are written down before the first write and never overwritten, so hiding the
     * same thing twice cannot record the lie as the truth and leave restore with nothing to put
     * back.
     */
    fun hide(signal: Signal): Boolean {
        if (signal == Signal.VPN) return takeVpnDown()
        if (isHidden(signal)) return true

        if (hiddenSince() == 0L) {
            prefs.edit().putLong(PREF_HIDDEN_SINCE, System.currentTimeMillis()).apply()
        }

        val saved = originals(signal)
        signal.keys.forEach { key ->
            if (!saved.containsKey(key.id)) {
                val current = readKey(key)
                // What the setting says now is only an original if it is not already the lie.
                if (trustworthy(key, current)) saved[key.id] = current
            }
        }
        writeOriginals(signal, saved)

        var applied = true
        signal.keys.forEach { key ->
            if (!writeKey(key, key.hidden)) {
                LOGGER.w("hiding ${signal.name} would not take: ${key.id}")
                applied = false
            }
        }
        return applied
    }

    /**
     * Puts [signal] back the way it was found.
     *
     * A key that will not go back keeps its recorded value, so that a restore that did not take
     * is offered again rather than forgotten along with the value that would have put it back.
     */
    fun restore(signal: Signal): Boolean {
        if (signal == Signal.VPN) return bringVpnBack()

        val saved = originals(signal)
        var applied = true

        signal.keys.forEach { key ->
            if (!saved.containsKey(key.id)) return@forEach
            val value = saved.remove(key.id)

            if (!trustworthy(key, value)) {
                // Nothing is written: putting the lie back is not a restore, and the value the
                // setting really had was never recorded, so there is nothing to put back.
                LOGGER.w("not restoring ${key.id}: the remembered value is the hidden one")
                return@forEach
            }

            val put = if (value == null) deleteKey(key) else writeKey(key, value)
            if (!put) {
                LOGGER.w("restoring ${key.id} would not take")
                applied = false
                saved[key.id] = value
            }
        }

        writeOriginals(signal, saved)
        return applied
    }

    /** Puts everything back, which is what anything that stops hiding does. */
    fun restoreAll(): Boolean = Signal.entries.fold(true) { all, signal -> restore(signal) && all }

    /**
     * Whether a remembered value can be trusted as an original.
     *
     * A value equal to the hidden one is the lie itself rather than what the setting used to be:
     * the only way to remember it is to read the setting while it was already hidden, and writing
     * that back is how a device ends up with a setting that is neither what it had nor what was
     * asked of it.
     */
    private fun trustworthy(key: SettingKey, value: String?): Boolean = value != key.hidden

    /** Whether a record of what to put back is being held for this signal. */
    private fun hasRecord(signal: Signal): Boolean = when (signal) {
        Signal.VPN -> rememberedVpnClient() != null
        else -> originals(signal).isNotEmpty()
    }

    /**
     * Restarts [packageName] so it starts again with the hidden values.
     *
     * The hide has already been applied by the pass that noticed the app, so the app in front is
     * one that has already read the visible settings; only a new process can read the hidden
     * ones. Force-stop and start are both done through the shell: a stopped app cannot be
     * launched by this app under the platform's background-activity rules.
     */
    fun restartForLaunch(packageName: String): Boolean {
        if (!shellAvailable()) {
            LOGGER.w("not restarting $packageName: there is no shell to do it with")
            return false
        }

        val component = runCatching {
            ShizukuApplication.appContext.packageManager
                .getLaunchIntentForPackage(packageName)?.component
        }.getOrNull()
        if (component == null) {
            LOGGER.w("not restarting $packageName: it has nothing to launch")
            return false
        }

        val stopped = shell("am force-stop $packageName") != null
        val output = shell("am start -n ${component.flattenToShortString()}")
        val started = output != null && !output.contains("Error")

        if (!started) {
            LOGGER.w("restart of $packageName did not take: ${output ?: "no shell"}")
        }
        return stopped && started
    }

    // ---- the tunnel --------------------------------------------------------------

    /** Whether a VPN is up, asked of the platform rather than the shell. */
    fun vpnUp(): Boolean = runCatching {
        val manager = connectivity() ?: return false
        manager.allNetworks
            .mapNotNull { manager.getNetworkCapabilities(it) }
            .any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }.getOrDefault(false)

    private fun connectivity(): ConnectivityManager? =
        ShizukuApplication.appContext.getSystemService(ConnectivityManager::class.java)

    /**
     * The apps that could be holding a tunnel, which is every app declaring a VpnService.
     *
     * Asked of the package manager rather than guessed from the network: a VPN network does not
     * name its owner in anything a normal app may read.
     */
    fun vpnCandidates(): List<String> = runCatching {
        val manager = ShizukuApplication.appContext.packageManager
        manager.queryIntentServices(Intent("android.net.VpnService"), 0)
            .map { it.serviceInfo.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * The client to act on: the one chosen, or the only one there is.
     *
     * More than one candidate and no choice made means this refuses rather than guesses, because
     * stopping the wrong client is a tunnel taken down that can never be put back - the client
     * that was running is the only thing that knows how to bring it back.
     */
    private fun vpnClientPackage(): String? {
        chosenVpnClient()?.let { return it }

        val candidates = vpnCandidates()
        if (candidates.size == 1) return candidates.first()

        LOGGER.w("a VPN is up and ${candidates.size} clients could hold it: $candidates")
        return null
    }

    fun chosenVpnClient(): String? = prefs.getString(PREF_VPN_CHOSEN, null)

    /** Set once a choice has been made, so a second client cannot be acted on by accident. */
    fun chooseVpnClient(packageName: String) {
        prefs.edit().putString(PREF_VPN_CHOSEN, packageName).apply()
    }

    private fun rememberedVpnClient(): String? = prefs.getString(PREF_VPN_CLIENT, null)

    private fun rememberVpnClient(packageName: String) {
        prefs.edit().putString(PREF_VPN_CLIENT, packageName).apply()
    }

    private fun forgetVpnClient() {
        prefs.edit().remove(PREF_VPN_CLIENT).apply()
    }

    /** Hidden means: this app took a tunnel down, and it is still down. */
    private fun vpnTakenDown(): Boolean = rememberedVpnClient() != null && !vpnUp()

    /**
     * Takes the tunnel down by stopping the client that owns it.
     *
     * Nothing to do when no VPN is up: the device is not on a tunnel and this app did not take
     * one away, so there is nothing to remember and nothing to put back later.
     */
    fun takeVpnDown(): Boolean {
        if (!vpnUp()) {
            forgetVpnClient()
            return true
        }

        val client = vpnClientPackage()
        if (client == null) {
            LOGGER.w("a VPN is up and the client behind it was not identified")
            return false
        }

        rememberVpnClient(client)
        shell("am force-stop $client")
        // Asked, not assumed: the tunnel is gone when the platform says there is no VPN network.
        return !vpnUp()
    }

    /**
     * Asks the same client for the tunnel back, and only if this app took it away.
     *
     * A client whose own start action is known is asked directly, and anything else is relaunched
     * and left to reconnect on its own - which some do and some do not, so the record is dropped
     * either way rather than claiming a tunnel that may not be there.
     */
    private fun bringVpnBack(): Boolean {
        val client = rememberedVpnClient() ?: return true

        // Somebody else already brought it back, or it never really went down.
        if (vpnUp()) {
            forgetVpnClient()
            return true
        }

        if (!shellAvailable()) return false

        val command = VPN_START[client]
            ?: "monkey -p $client -c android.intent.category.LAUNCHER 1"
        LOGGER.i("asking $client for the tunnel back")
        shell(command)
        forgetVpnClient()
        return true
    }

    private val VPN_START = mapOf(
        "com.github.metacubex.clash.meta" to
            "am start -a com.github.metacubex.clash.meta.action.START_CLASH " +
            "-n com.github.metacubex.clash.meta/com.github.kr328.clash.ExternalControlActivity " +
            "--activity-multiple-task --activity-no-history --activity-no-animation " +
            "--activity-exclude-from-recents",
        "com.appshub.bettbox" to
            "am start -a com.appshub.bettbox.action.START " +
            "-n com.appshub.bettbox/com.appshub.bettbox.TempActivity " +
            "--activity-multiple-task --activity-no-history --activity-no-animation " +
            "--activity-exclude-from-recents",
        "com.follow.clash" to
            "am start -a com.follow.clash.action.START -n com.follow.clash/.TempActivity " +
            "--activity-multiple-task --activity-no-history --activity-no-animation " +
            "--activity-exclude-from-recents",
        "com.getsurfboard" to
            "am start -a android.intent.action.VIEW -d surfboard:///start -p com.getsurfboard",
        "com.nebula.clashmi" to
            "cmd deviceidle tempwhitelist -d 10000 com.nebula.clashmi >/dev/null 2>&1; " +
            "am broadcast --user current --receiver-foreground --include-stopped-packages " +
            "-a com.nebula.clashmi.action.CONNECT " +
            "-n com.nebula.clashmi/com.nebula.clashmi.AutomationCommandReceiver"
    )

    /**
     * Brings a signal back into view when there is nothing recorded to restore it to.
     *
     * A setting can be hidden by something outside this app, and without a record the watch would
     * know the setting is hidden and have nothing to write to un-hide it.
     */
    private fun reveal(signal: Signal): Boolean {
        if (signal == Signal.VPN) return bringVpnBack()

        var applied = true
        signal.keys.forEach { key ->
            val visible = key.visible ?: run {
                LOGGER.w("${key.id} is hidden and nothing was recorded for it")
                return@forEach
            }
            if (!writeKey(key, visible)) {
                LOGGER.w("bringing ${key.id} back would not take")
                applied = false
            }
        }
        return applied
    }

    private fun originals(signal: Signal): MutableMap<String, String?> =
        prefs.getStringSet(originalsPref(signal), emptySet())
            .orEmpty()
            .associate { entry ->
                val split = entry.indexOf('=')
                // No `=` at all is a key that was not set, which is not the same as one that was
                // set to nothing.
                if (split < 0) entry to null else entry.substring(0, split) to entry.substring(split + 1)
            }
            .toMutableMap()

    private fun writeOriginals(signal: Signal, saved: Map<String, String?>) {
        val entries = saved.entries.map { (id, value) -> if (value == null) id else "$id=$value" }
        prefs.edit().putStringSet(originalsPref(signal), entries.toSet()).apply()
    }

    private fun writeKey(key: SettingKey, value: String): Boolean = runCatching {
        val resolver = ShizukuApplication.appContext.contentResolver
        val put = when (key.namespace) {
            "global" -> Settings.Global.putString(resolver, key.name, value)
            "secure" -> Settings.Secure.putString(resolver, key.name, value)
            else -> false
        }
        // Read back, always: a write the provider accepted is not the same as a setting the
        // platform is using, and the switches have to be able to say which happened.
        put && readKey(key) == value
    }.getOrDefault(false)

    /**
     * Removes a key rather than setting it to nothing, which is the only way back to a key that
     * was never there: an empty string is a value somebody chose.
     */
    private fun deleteKey(key: SettingKey): Boolean = runCatching {
        val resolver = ShizukuApplication.appContext.contentResolver
        when (key.namespace) {
            "global" -> Settings.Global.putString(resolver, key.name, null)
            "secure" -> Settings.Secure.putString(resolver, key.name, null)
            else -> return false
        }
        readKey(key) == null
    }.getOrDefault(false)

    /**
     * One shell command, its merged output, or null when it did not run or did not succeed.
     *
     * The shell is reached through Shizuku so the command runs as the privileged user: the
     * settings writes above need WRITE_SECURE_SETTINGS, which this app holds only when Shizuku
     * granted it, and the dumpsys read needs DUMP for records about other apps.
     */
    fun shell(command: String): String? {
        // java.lang.Process, not android.os.Process: the latter is the uid helper imported above.
        var process: java.lang.Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            val out = ByteArrayOutputStream()
            process.inputStream.copyTo(out)
            process.errorStream.copyTo(out)
            val exit = process.waitFor()
            val text = out.toString("UTF-8").trim()
            if (exit == 0) text else null
        } catch (e: Throwable) {
            LOGGER.w("shell failed: $command", e)
            null
        } finally {
            runCatching { process?.destroy() }
        }
    }
}
