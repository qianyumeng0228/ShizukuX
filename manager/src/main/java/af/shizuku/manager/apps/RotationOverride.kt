package af.shizuku.manager.apps

import android.content.Context
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
            return
        }

        val current = applied()
        if (current != null && current.first == foreground && current.second == wanted) return
        if (!PrivilegedShell.available()) return

        // The first lock records the display as the user had it. A base left in preferences is
        // reused after a process restart: capturing the forced state as the base would make
        // restore put the phone back into the lock it was trying to remove.
        if (readBase() == null) {
            val base = readDisplayState() ?: return
            saveBase(base)
        }

        if (applyLock(wanted)) {
            prefs().edit().putString(KEY_APPLIED, "$foreground=${wanted.name}").apply()
        }
    }

    /** The forced mode currently holding the display, for the next pass. */
    fun applied(): Pair<String, RotationMode>? {
        val raw = prefs().getString(KEY_APPLIED, null) ?: return null
        val packageName = raw.substringBefore('=')
        val mode = RotationMode.from(raw.substringAfter('=', ""))
        if (packageName.isEmpty() || !mode.forced) return null
        return packageName to mode
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

        val commands = ArrayList<String>(4)
        base.fixedToUser?.let { commands.add("wm fixed-to-user-rotation $it") }
        base.autoRotate?.let { commands.add("settings put system accelerometer_rotation $it") }
        base.userRotation?.let { commands.add("settings put system user_rotation $it") }
        base.userRotationMode?.let { commands.add("wm user-rotation $it") }
        if (commands.isNotEmpty()) {
            PrivilegedShell.run(commands.joinToString("; "))
        }

        prefs().edit().remove(KEY_BASE).remove(KEY_APPLIED).apply()
    }

    // ------------------------------------------------------------------ display state

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
