package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.preference.TwoStatePreference
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*

/**
 * Developer Options Settings
 * 
 * Contains experimental and developer-focused features:
 * - AVF/Vector (virtual machine support)
 * - Experimental Root Compatibility
 * - Device Identity Spoofing
 * 
 * These are separated from main ShizukuX Features as they are
 * intended for developers and power users.
 */
class DeveloperOptionsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_developer_options, rootKey)

        // Vector / AVF Manager
        val vectorPref = findPreference<TwoStatePreference>(KEY_VECTOR_ENABLED)
        vectorPref?.isChecked = ShizukuSettings.isVectorEnabled()
        vectorPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setVectorEnabled(newValue)
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        // Experimental Root Compatibility
        val experimentalRootPref = findPreference<TwoStatePreference>(KEY_EXPERIMENTAL_ROOT_COMPAT)
        experimentalRootPref?.isChecked = ShizukuSettings.isExperimentalRootCompatEnabled()
        experimentalRootPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setExperimentalRootCompatEnabled(newValue)
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        // Device Identity Spoofing
        val spoofDevicePref = findPreference<TwoStatePreference>(KEY_SPOOF_DEVICE_ENABLED)
        spoofDevicePref?.isChecked = ShizukuSettings.isSpoofDeviceEnabled()
        spoofDevicePref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setSpoofDeviceEnabled(newValue)
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        // Spoof Target
        findPreference<androidx.preference.Preference>(KEY_SPOOF_TARGET)?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }
    }
}
