package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.CustomTabsHelper

class AboutSettingsFragment : BaseSettingsFragment() {

    private var versionClickCount = 0

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val context = requireContext()

        findPreference<Preference>("version")?.apply {
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setOnPreferenceClickListener {
                if (ShizukuSettings.isVectorEnabled()) {
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                
                versionClickCount++
                if (versionClickCount >= 7) {
                    ShizukuSettings.setVectorEnabled(true)
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    versionClickCount = 0
                } else if (versionClickCount > 2) {
                    Toast.makeText(context, context.getString(R.string.settings_developer_options_click_more, 7 - versionClickCount), Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/thejaustin/ShizukuPlus")
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(requireContext(), "https://github.com/thejaustin/ShizukuPlus/blob/main/OPEN_SOURCE_LICENSES.md")
            true
        }
    }
}
