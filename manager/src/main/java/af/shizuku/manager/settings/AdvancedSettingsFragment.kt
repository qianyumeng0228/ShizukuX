package af.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.ShizukuSettings
import android.widget.Toast
import timber.log.Timber
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.BuildConfig

class AdvancedSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_advanced, rootKey)
        val context = requireContext()

        findPreference<Preference>("service_doctor")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ServiceDoctorActivity::class.java))
            true
        }

        findPreference<Preference>("activity_log")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ActivityLogActivity::class.java))
            true
        }

        findPreference<androidx.preference.TwoStatePreference>(KEY_LEGACY_PAIRING)?.apply {
            isVisible = !EnvironmentUtils.isTelevision()
        }

        findPreference<Preference>(KEY_HELP)?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.help_url))
            true
        }

        findPreference<Preference>(KEY_REPORT_BUG)?.setOnPreferenceClickListener {
            BugReportDialog().show(parentFragmentManager, "BugReportDialog")
            true
        }

        findPreference<Preference>("reset_adb_keys")?.setOnPreferenceClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_reset_adb_keys)
                .setMessage(R.string.settings_reset_adb_keys_summary)
                .setPositiveButton(R.string.settings_reset_adb_keys) { _, _ ->
                    try {
                        ShizukuSettings.getPreferences().edit().remove("adbkey").apply()
                        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                        keyStore.load(null)
                        keyStore.deleteEntry("_adbkey_encryption_key_")
                        Toast.makeText(context, R.string.settings_reset_adb_keys_success, Toast.LENGTH_SHORT).show()
                        activity?.recreate()
                    } catch (e: Exception) {
                        Timber.tag("AdvancedSettings").e(e, "Failed to reset ADB keys")
                        Toast.makeText(context, R.string.settings_reset_adb_keys_error, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }
}
