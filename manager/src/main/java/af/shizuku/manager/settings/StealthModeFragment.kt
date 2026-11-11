package af.shizuku.manager.settings

import android.os.Bundle
import af.shizuku.manager.R

/**
 * Stealth mode tutorial page (self-built in this repo's preference style).
 * Static guide only: the stealth toggle lives in AdvancedSettingsFragment and must not be
 * duplicated here, otherwise the same setting can be flipped from two places.
 */
class StealthModeFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_stealth_tutorial_entry)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.settings_stealth)

        val context = requireContext()

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
