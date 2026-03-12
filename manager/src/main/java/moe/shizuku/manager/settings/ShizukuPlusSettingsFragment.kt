package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.Keys.*
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService

class ShizukuPlusSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_shizuku_plus, rootKey)

        ShizukuSettings.syncAllPlusFeaturesToServer()

        val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)!!
        dhizukuPref.isChecked = ShizukuSettings.isDhizukuModeEnabled()
        dhizukuPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_DHIZUKU_MODE, newValue) {
                    ShizukuSettings.setDhizukuModeEnabled(newValue)
                    dhizukuPref.isChecked = newValue
                }
            }
            false
        }

        val customApiPref = findPreference<TwoStatePreference>(KEY_CUSTOM_API_ENABLED)!!
        customApiPref.isChecked = ShizukuSettings.isCustomApiEnabled()
        customApiPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_CUSTOM_API_ENABLED, newValue) {
                    ShizukuSettings.setCustomApiEnabled(newValue)
                    customApiPref.isChecked = newValue
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                }
            }
            false
        }
        
        // Experimental: Reveal Developer Options on long-press
        customApiPref.setOnPreferenceClickListener {
            // Not a long press but we can use this to reveal if it was already checked
            false
        }

        val devCategory = findPreference<androidx.preference.PreferenceCategory>("category_developer")
        if (ShizukuSettings.isVectorEnabled()) {
            devCategory?.isVisible = true
        }

        val vectorPref = findPreference<TwoStatePreference>(KEY_VECTOR_ENABLED)
        vectorPref?.isChecked = ShizukuSettings.isVectorEnabled()
        vectorPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setVectorEnabled(newValue)
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        val experimentalRootPref = findPreference<TwoStatePreference>(KEY_EXPERIMENTAL_ROOT_COMPAT)
        experimentalRootPref?.isChecked = ShizukuSettings.isExperimentalRootCompatEnabled()
        experimentalRootPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setExperimentalRootCompatEnabled(newValue)
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        val spoofDevicePref = findPreference<TwoStatePreference>(KEY_SPOOF_DEVICE_ENABLED)
        spoofDevicePref?.isChecked = ShizukuSettings.isSpoofDeviceEnabled()
        spoofDevicePref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setSpoofDeviceEnabled(newValue)
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        val plusKeys = listOf(
            "shell_interceptor_enabled" to "shell_interceptor",
            "avf_manager_enabled" to "avf_manager",
            "storage_proxy_enabled" to "storage_proxy",
            "continuity_bridge_enabled" to "continuity_bridge",
            "ai_core_plus_enabled" to "ai_core_plus",
            "window_manager_plus_enabled" to "window_manager_plus",
            "overlay_manager_plus_enabled" to "overlay_manager_plus",
            "network_governor_plus_enabled" to "network_governor_plus",
            "activity_manager_plus_enabled" to "activity_manager_plus"
        )
        plusKeys.forEach { (prefKey, _) ->
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, _ ->
                ShizukuSettings.syncAllPlusFeaturesToServer()
                true
            }
        }
    }
}
