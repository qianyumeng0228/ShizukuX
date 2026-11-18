package af.shizuku.manager.devicecontrol

import android.content.Context
import af.shizuku.manager.ShizukuApplication

/**
 * The auto-rotate value the user set themselves, remembered across restarts.
 *
 * The device does not keep this choice: measured on 2210132C / Android 16 / HyperOS, a restart
 * comes back with `accelerometer_rotation=1` no matter what the user had it at. On ColorOS the
 * same reading is reported by users as "重启之后自动旋转又自己开了".
 *
 * Only a click on the control counts as a choice. The value is never sampled from the platform,
 * because every app coming to the front rewrites that switch (see `RotationOverride`), so a
 * sampled value says nothing about what the user asked for - it is only the current state.
 */
object AutoRotatePreference {

    private const val PREF = "auto_rotate_preference"
    private const val KEY_USER_VALUE = "user_value"

    private fun prefs() =
        ShizukuApplication.appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Records a value the user just chose. Called only after the write was read back as applied. */
    fun remember(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_USER_VALUE, enabled).apply()
    }

    /** The user's choice, or null when they have never set it from this app. */
    fun value(): Boolean? =
        if (prefs().contains(KEY_USER_VALUE)) prefs().getBoolean(KEY_USER_VALUE, false) else null

    /**
     * Writes the remembered value back when the platform came up with a different one.
     *
     * Returns true when a write was made. Nothing happens without a remembered value: guessing
     * here would be the same mistake this preference exists to avoid.
     */
    fun restore(available: () -> Boolean, run: (String) -> String?): Boolean {
        val wanted = value() ?: return false
        if (!available()) return false

        val wantedText = if (wanted) "1" else "0"
        val current = run("settings get system accelerometer_rotation")
            ?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return false
        if (current == wantedText) return false

        run("settings put system accelerometer_rotation $wantedText")
        return true
    }
}
