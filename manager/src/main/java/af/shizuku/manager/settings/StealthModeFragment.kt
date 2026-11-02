package af.shizuku.manager.settings

import android.content.ComponentName
import android.os.Bundle
import androidx.preference.TwoStatePreference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Stealth mode tutorial page (P2-4, self-built in this repo's preference style).
 * Hosts the same stealth toggle as AdvancedSettingsFragment plus static guide
 * entries explaining what stealth mode does and how to exit it over ADB.
 */
class StealthModeFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_stealth_tutorial_entry)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings_stealth)

        val context = requireContext()
        // The manifest's namespace (af.shizuku.manager) differs from the per-flavor applicationId,
        // so ".LauncherAlias" must resolve against the namespace, not the package name.
        val launcherAlias = ComponentName(context, "af.shizuku.manager.LauncherAlias")

        findPreference<TwoStatePreference>("stealth_mode")?.apply {
            isChecked = ShizukuSettings.isStealthModeEnabled()
            setOnPreferenceChangeListener { pref, newValue ->
                val enable = newValue as Boolean
                if (enable) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.settings_enable_stealth_title)
                        .setMessage(
                            getString(
                                R.string.settings_enable_stealth_message,
                                "adb shell pm enable ${context.packageName}/af.shizuku.manager.LauncherAlias"
                            )
                        )
                        .setPositiveButton(R.string.settings_enable_stealth_confirm) { _, _ ->
                            context.packageManager.setComponentEnabledSetting(
                                launcherAlias,
                                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                0
                            )
                            ShizukuSettings.setStealthModeEnabled(true)
                            ShizukuSettings.syncAllExtraFeaturesToServer()
                            (pref as? TwoStatePreference)?.isChecked = true
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            (pref as? TwoStatePreference)?.isChecked = false
                        }
                        .show()
                    false
                } else {
                    context.packageManager.setComponentEnabledSetting(
                        launcherAlias,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        0
                    )
                    ShizukuSettings.setStealthModeEnabled(false)
                    ShizukuSettings.syncAllExtraFeaturesToServer()
                    true
                }
            }
        }

        findPreference<androidx.preference.Preference>("stealth_tutorial_exit")?.apply {
            summary = getString(
                R.string.settings_stealth_tutorial_exit_summary
            ) + "\n\n" + getString(
                R.string.settings_stealth_tutorial_exit_command,
                context.packageName
            )
        }
    }
}
