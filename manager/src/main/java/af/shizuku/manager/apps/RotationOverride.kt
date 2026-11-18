package af.shizuku.manager.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import af.shizuku.manager.ShizukuApplication

/** What an app's rotation should do while it is in front. */
enum class RotationMode(
    /** Portrait and Landscape take over the display; the other two only touch the compat change. */
    val forced: Boolean = false,
    /** Surface rotation used for the two forced modes. */
    val rotation: Int = -1
) {
    OFF,
    AUTO,
    PORTRAIT(forced = true, rotation = 0),
    LANDSCAPE(forced = true, rotation = 1);

    companion object {
        fun from(value: String?): RotationMode =
            values().firstOrNull { it.name == value } ?: OFF
    }
}

/**
 * Per-app rotation, ported from Shizuku-Next (`0d579006` / `a80085d7`).
 *
 * Two different mechanisms, because they answer two different questions:
 *
 * - `AUTO` is the Android 16 compat change `OVERRIDE_ANY_ORIENTATION_TO_USER`. It makes an app
 *   that pins its own orientation follow the user's rotation — but only where the platform
 *   applies that override. An app targeting Android 16 can keep requesting its own orientation,
 *   which is exactly the case the compat change does not cover.
 * - `PORTRAIT` / `LANDSCAPE` therefore do what AppRotate does: `wm fixed-to-user-rotation enabled`
 *   plus a locked `user_rotation`. The display is shared, so the lock only lasts while the named
 *   app is in front; [applyFor] is called from the watch each pass and puts the display back when
 *   that app leaves.
 *
 * Everything written here is read back. Compat state comes from `dumpsys platform_compat`, the
 * display state from `wm` and `settings` — a command that was accepted but not applied must not
 * leave a row claiming it worked.
 */
object RotationOverride {

    private const val CHANGE = "OVERRIDE_ANY_ORIENTATION_TO_USER"
    private const val PREF = "rotation_override"
    private const val KEY_MODES = "modes"
    private const val KEY_BASE = "base"
    private const val KEY_APPLIED = "applied"
    private const val KEY_IDLE_ACC = "idle_acc"
    private const val FIELD = "\u001f"
    private const val MARKER = "--rotation-field--"

    /** The display state as `wm` and `settings` report it, so it can be written back verbatim. */
    private data class DisplayState(
        val autoRotate: String?,
        val userRotation: String?,
        val fixedToUser: String?,
        val userRotationMode: String?
    )

    private fun prefs() =
        ShizukuApplication.appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Both mechanisms arrived with Android 16's rotation behaviour. */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= 36

    // ------------------------------------------------------------------ compat change

    /** Every package with the override set, as the platform reports it. */
    fun enabledPackages(): Set<String> {
        if (!isSupported()) return emptySet()
        return parse(PrivilegedShell.run("dumpsys platform_compat"))
    }

    fun isEnabled(packageName: String): Boolean = packageName in enabledPackages()

    private fun setCompat(packageName: String, enabled: Boolean): Boolean {
        if (!PrivilegedShell.available()) return false
        if ((packageName in enabledPackages()) == enabled) return true
        val verb = if (enabled) "enable" else "disable"
        PrivilegedShell.run("am compat $verb $CHANGE '$packageName'")
        return (packageName in enabledPackages()) == enabled
    }

    // ------------------------------------------------------------------ modes

    /** Every app with a mode other than [RotationMode.OFF]. */
    fun modes(): Map<String, RotationMode> = prefs()
        .getStringSet(KEY_MODES, emptySet()).orEmpty()
        .mapNotNull { entry ->
            val packageName = entry.substringBefore('=')
            val mode = RotationMode.from(entry.substringAfter('=', ""))
            packageName.takeIf { it.isNotEmpty() && mode != RotationMode.OFF }?.let { it to mode }
        }
        .toMap()

    fun modeFor(packageName: String): RotationMode = modes()[packageName] ?: RotationMode.OFF

    /**
     * True when an app needs the display itself, not just a compat change.
     *
     * Only the forced modes need a watch: `AUTO` is a platform flag that stays in effect on its
     * own, so keeping a foreground poller alive for it would be battery spent on nothing.
     */
    fun hasWorkToDo(): Boolean = hasForceWork()

    /** True when any app needs the display itself, not just a compat change. */
    fun hasForceWork(): Boolean = modes().values.any { it.forced }

    /**
     * Saves one app's mode and applies the compat half.
     *
     * Forced modes deliberately leave the compat change alone: it does not affect an app that
     * targets Android 16, and the display lock is what makes those modes work. `AUTO` is the one
     * mode that depends on the compat change, so it is the one that fails if it cannot be enabled.
     */
    fun setMode(packageName: String, mode: RotationMode): Boolean {
        if (!isSupported()) return false
        if (mode != RotationMode.OFF && !PrivilegedShell.available()) return false

        when (mode) {
            RotationMode.AUTO -> if (!setCompat(packageName, true)) return false
            RotationMode.OFF -> if (!setCompat(packageName, false)) return false
            RotationMode.PORTRAIT, RotationMode.LANDSCAPE -> Unit
        }

        val entries = prefs().getStringSet(KEY_MODES, emptySet()).orEmpty().toMutableSet()
        entries.removeAll { it.substringBefore('=') == packageName }
        if (mode != RotationMode.OFF) entries.add("$packageName=${mode.name}")
        prefs().edit().putStringSet(KEY_MODES, entries).apply()
        return modeFor(packageName) == mode
    }

    /** Compatibility shim for the long-press menu: true = [RotationMode.AUTO], false = OFF. */
    fun setEnabled(packageName: String, enabled: Boolean): Boolean =
        setMode(packageName, if (enabled) RotationMode.AUTO else RotationMode.OFF)

    // ------------------------------------------------------------------ the watch

    /**
     * Applies the lock for [foreground], or puts the display back when that app has no forced
     * mode. Called from the watch every pass, so it has to be cheap when nothing changed.
     */
    fun applyFor(foreground: String?) {
        val wanted = foreground?.let { modeFor(it) }?.takeIf { it.forced }
        if (wanted == null) {
            restore()
            // On this ROM the switch is rewritten by the system itself when an app comes to the
            // front, so the value read while a forced app is in front says nothing about what the
            // user chose. The one moment the value is the user's own is the idle pass below, with
            // the display back in its own state — remember it for the next sample.
            //
            // "Idle" has to mean the home screen, not "some app that is not forced": any app in
            // front is enough for the system to rewrite the switch, so sampling there records the
            // rewrite as if it were the user's choice and the next lock restores it, which is how
            // the switch ended up on after a leave. Only the launcher (or nothing in front at all)
            // is a moment the user can have set it themselves.
            if (isHome(foreground)) rememberIdleAutoRotate()
            return
        }

        val current = applied()
        if (current != null && current.first == foreground && current.second == wanted) {
            // The lock is held, but this ROM and the apps on it can rewrite the two switches
            // while the app stays in front — measured on 2210132C / Android 16: with the lock
            // already applied, `accelerometer_rotation` went back to 1 on its own. That is the
            // switch the user sees in quick settings, so a pass that trusts the applied marker
            // alone leaves the tile lit while the screen is still pinned: "自动旋转被打开了".
            // Each pass therefore re-reads the two things a lock owns and re-applies when either
            // has been written by someone else.
            if (lockStillHeld()) return
            if (!PrivilegedShell.available()) return
            val held = applyLock(wanted)
            android.util.Log.w(
                "ShizukuX/Rotation",
                "relock fg=$foreground mode=${wanted.name} ok=$held"
            )
            if (!held) prefs().edit().remove(KEY_APPLIED).apply()
            return
        }
        if (!PrivilegedShell.available()) return

        // The first lock records the display as the user had it. A base left in preferences is
        // reused after a process restart, because the applied marker survives the restart too —
        // but a base with no applied marker behind it is left over from a restore that died
        // before it could clear itself, and reusing it would put the phone back into whatever
        // state that failed run produced.
        var live = readDisplayState() ?: return
        if (current == null && readBase() == null && live.fixedToUser == "enabled") {
            // A display that is still fixed-to-user with no applied marker and no base is a lock
            // this tool left behind, not the user's own state — the lock is only ever enabled by
            // applyLock. Recorded as-is it would be written straight back by the next restore, so
            // clear it first and read again.
            PrivilegedShell.run("wm fixed-to-user-rotation disabled")
            live = readDisplayState() ?: return
        }
        if (readBase() == null || current == null) {
            // The auto-rotate half comes from the last idle pass when there is one: by the time a
            // forced app is in front the system has already turned the switch back on, so `live`
            // would record "on" no matter what the user chose. With no idle reading there is no
            // trustworthy value at all, and writing `live` back is what turned the switch on for
            // users who had it off — so that half is left unrecorded and the restore leaves it
            // alone rather than guessing.
            val idle = idleAutoRotate()
            val base = if (idle != null) live.copy(autoRotate = idle) else live.copy(autoRotate = null)
            android.util.Log.w(
                "ShizukuX/Rotation",
                "sample fg=$foreground current=$current live=$live idle=$idle base=$base"
            )
            saveBase(base)
        }

        if (applyLock(wanted)) {
            prefs().edit().putString(KEY_APPLIED, "$foreground=${wanted.name}").apply()
        }
    }

    /** The forced mode currently holding the display, for the next pass. */
    fun applied(): Pair<String, RotationMode>? {        val raw = prefs().getString(KEY_APPLIED, null) ?: return null
        val packageName = raw.substringBefore('=')
        val mode = RotationMode.from(raw.substringAfter('=', ""))
        if (packageName.isEmpty() || !mode.forced) return null
        return packageName to mode
    }

    /**
     * Drops the lock markers a restart invalidated.
     *
     * The saved base describes the display as it was before the lock, and the applied marker
     * describes what the watch was holding. Neither means anything after a reboot: the platform
     * comes back with its own display defaults, and a restore driven by that old base would write
     * a pre-restart value over them. The modes themselves stay - those are the user's list.
     */
    fun resetAfterBoot() {
        // The idle reading goes with them: it was sampled before the restart, and the platform
        // has since put its own default in place, so using it would record a pre-restart value
        // as the one to come back to.
        prefs().edit().remove(KEY_BASE).remove(KEY_APPLIED).remove(KEY_IDLE_ACC).apply()
    }

    /**
     * Puts the display back exactly as it was before the first lock.
     *
     * Safe to call every pass: with no saved base there is nothing to restore. The order is the
     * one measured on the device — fixed-to-user first, then the two settings, then the user
     * rotation mode that was in force.
     */
    fun restore() {
        val base = readBase()
        if (base == null) {
            // Nothing was taken, so nothing goes back — and this runs every watch pass, so it
            // must not rewrite preferences when there is nothing to rewrite.
            if (prefs().contains(KEY_APPLIED)) {
                prefs().edit().remove(KEY_APPLIED).apply()
            }
            return
        }

        // Without a shell the lock cannot be undone. Keeping the saved base is what lets the
        // next watch pass put the display back instead of forgetting what it replaced.
        if (!PrivilegedShell.available()) return

        // A base with no applied marker behind it is a leftover from a run that died before its
        // restore finished. Writing it back would put the display into a state the user never
        // asked for - and because the hiding module calls restore() whenever its own lists
        // change, that leftover is enough to turn auto-rotate on "by itself" while the user is
        // editing an unrelated list. It is dropped instead of replayed.
        if (applied() == null) {
            prefs().edit().remove(KEY_BASE).apply()
            return
        }

        val commands = ArrayList<String>(5)
        base.fixedToUser?.let { commands.add("wm fixed-to-user-rotation $it") }
        // `accelerometer_rotation` is the authority here, and `wm user-rotation` must not be
        // used to restore it. The two are not independent on HyperOS (measured on 2210132C /
        // Android 16): `wm user-rotation free` writes accelerometer_rotation=1, and writing
        // accelerometer_rotation=0 puts the mode back to `lock <user_rotation>`. Each undoes the
        // other, so whichever lands last decides — and a restore that ran `wm user-rotation free`
        // after the settings came back with auto-rotate on even though the user had it off.
        // Writing the two settings alone reproduces both original states exactly:
        // 1 gives `free`, 0 gives `lock <user_rotation>`.
        base.userRotation?.let { commands.add("settings put system user_rotation $it") }
        base.autoRotate?.let { commands.add("settings put system accelerometer_rotation $it") }
        // …but not every ROM derives the mode from that setting. On ColorOS the quick-settings
        // tile keeps showing the last free/lock state even when accelerometer_rotation is written
        // back to 0, so the tile stays lit while the display is locked - reported by an OPPO user
        // as "自动旋转自己打开了". Shizuku-Next restores `wm user-rotation` last for this reason;
        // the two values are written to agree, so the order cannot conflict on HyperOS either:
        // `free` also writes accelerometer_rotation=1, `lock N` also writes it 0.
        when (base.autoRotate) {
            "0" -> base.userRotation?.let { commands.add("wm user-rotation lock $it") }
            "1" -> commands.add("wm user-rotation free")
        }
        if (commands.isNotEmpty()) {
            try {
                val out = PrivilegedShell.run(commands.joinToString("; "))
                // Release builds print no Timber, so this is the only way to read back what the
                // restore actually did when a phone comes back with the wrong switch.
                android.util.Log.w("ShizukuX/Rotation", "restore base=$base cmd=${commands.joinToString("; ")} out=${out?.trim()}")
            } catch (e: Throwable) {
                // The prefs are cleared below either way: a saved base that outlives its restore
                // gets reused by the next lock and would restore the state this run failed to
                // leave behind.
                android.util.Log.w("ShizukuX/Rotation", "restore failed base=$base", e)
            }
        }

        prefs().edit().remove(KEY_BASE).remove(KEY_APPLIED).apply()
    }

    // ------------------------------------------------------------------ display state

    /**
     * Whether the app in front is the home screen, the one place the switch is the user's own.
     *
     * A null foreground counts: it means the platform did not name an app at all, which is the
     * same kind of moment. Any other app is excluded because the system rewrites the switch as
     * soon as one comes to the front on this ROM.
     */
    private fun isHome(packageName: String?): Boolean {
        if (packageName == null) return true
        val context = ShizukuApplication.appContext
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolved?.activityInfo?.packageName == packageName
    }

    private fun readDisplayState(): DisplayState? {
        val output = PrivilegedShell.run(
            "settings get system accelerometer_rotation; echo $MARKER; " +
                "settings get system user_rotation; echo $MARKER; " +
                "wm fixed-to-user-rotation; echo $MARKER; " +
                "wm user-rotation"
        ) ?: return null

        val fields = output.split(MARKER).map { it.trim() }
        if (fields.size < 4) return null

        return DisplayState(
            autoRotate = fields[0].firstLine()?.usable(),
            userRotation = fields[1].firstLine()?.usable(),
            fixedToUser = fields[2].firstLine()?.usable(),
            userRotationMode = fields[3].firstLine()?.usable()
        )
    }

    private fun applyLock(mode: RotationMode): Boolean {
        val rotation = mode.rotation
        PrivilegedShell.run(
            "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation $rotation; " +
                "wm fixed-to-user-rotation enabled; " +
                "wm user-rotation lock $rotation"
        )

        val state = readDisplayState() ?: return false
        return state.autoRotate == "0" &&
            state.userRotation == rotation.toString() &&
            state.fixedToUser == "enabled" &&
            state.userRotationMode == "lock $rotation"
    }

    /**
     * Whether the two switches a lock owns are still the ones [applyLock] wrote.
     *
     * Deliberately narrower than [readDisplayState]: this runs every watch pass, and only the
     * auto-rotate switch and the fixed-to-user flag are read back — `user_rotation` follows the
     * lock on its own and reading it would cost two more shell commands per pass.
     */
    private fun lockStillHeld(): Boolean {
        val output = PrivilegedShell.run(
            "settings get system accelerometer_rotation; echo $MARKER; wm fixed-to-user-rotation"
        ) ?: return false
        val fields = output.split(MARKER).map { it.trim() }
        if (fields.size < 2) return false
        return fields[0].firstLine()?.usable() == "0" &&
            fields[1].firstLine()?.usable() == "enabled"
    }

    private fun saveBase(state: DisplayState) {
        prefs().edit().putString(
            KEY_BASE,
            listOf(
                state.autoRotate,
                state.userRotation,
                state.fixedToUser,
                state.userRotationMode
            ).joinToString(FIELD) { it ?: "" }
        ).apply()
    }

    /**
     * The auto-rotate value last seen with no forced app in front, i.e. the one the user set.
     *
     * HyperOS flips `accelerometer_rotation` to 1 and `user-rotation` to free as soon as an app
     * comes to the front — measured on 2210132C / Android 16, after a reboot, with this service
     * not running at all — so a sample taken while the app is already in front cannot tell what
     * the user chose. This is the only reading that can.
     */
    private fun idleAutoRotate(): String? = prefs().getString(KEY_IDLE_ACC, null)?.usable()

    /** Called on every idle pass, after [restore] has put the display back. */
    private fun rememberIdleAutoRotate() {
        if (applied() != null || readBase() != null) return
        val output = PrivilegedShell.run("settings get system accelerometer_rotation") ?: return
        val acc = output.lineSequence().firstOrNull()?.trim()?.usable() ?: return
        if (acc != idleAutoRotate()) {
            prefs().edit().putString(KEY_IDLE_ACC, acc).apply()
            android.util.Log.w("ShizukuX/Rotation", "idle acc=$acc")
        }
    }

    private fun readBase(): DisplayState? {
        val raw = prefs().getString(KEY_BASE, null) ?: return null
        val fields = raw.split(FIELD)
        if (fields.size != 4) return null
        return DisplayState(
            fields[0].usable(),
            fields[1].usable(),
            fields[2].usable(),
            fields[3].usable()
        )
    }

    /** `settings get` prints "null" for an unset key; that is not a value to write back. */
    private fun String?.usable(): String? = this?.takeIf { it.isNotBlank() && it != "null" }

    private fun String.firstLine(): String? = lineSequence().firstOrNull()?.trim()

    // ------------------------------------------------------------------ compat dump parse

    /**
     * The packages marked true in the change's `packageOverrides` map.
     *
     * One line of the dump carries the whole map, for example
     * `... packageOverrides={com.example=true}; rawOverrides=...`. A package explicitly set to
     * false is present but not enabled, and an absent package is at the platform default.
     */
    internal fun parse(dump: String?): Set<String> {
        val line = dump
            ?.lineSequence()
            ?.firstOrNull { it.contains("name=$CHANGE") }
            ?: return emptySet()

        val overrides = line
            .substringAfter("packageOverrides={", "")
            .substringBefore("}", "")

        if (overrides.isBlank()) return emptySet()

        return overrides
            .split(',')
            .mapNotNull { entry ->
                val name = entry.substringBefore('=').trim()
                val value = entry.substringAfter('=', "").trim()
                name.takeIf { it.isNotEmpty() && value == "true" }
            }
            .toSet()
    }
}
