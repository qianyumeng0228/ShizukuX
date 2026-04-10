package af.shizuku.manager.settings

import android.os.Bundle
import androidx.preference.TwoStatePreference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*

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
        val spoofTargetPref = findPreference<rikka.preference.SimpleMenuPreference>(KEY_SPOOF_TARGET)
        spoofTargetPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue == "auto") {
                // When auto is selected, we detect the current device and map it to the closest supported target if possible,
                // or just pass through the real device info to the server.
                val actualModel = android.os.Build.MODEL.lowercase().replace(" ", "_")
                val actualManuf = android.os.Build.MANUFACTURER.lowercase()
                
                // If the real device is already in our supported list, we can just use that specific target.
                // Otherwise, 'auto' tells the server to use real system properties.
                ShizukuSettings.setSpoofTarget("auto")
            } else if (newValue is String) {
                ShizukuSettings.setSpoofTarget(newValue)
            }
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }
    }
}
