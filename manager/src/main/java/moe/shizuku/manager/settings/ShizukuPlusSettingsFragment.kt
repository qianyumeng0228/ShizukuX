package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
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

        val dhizukuPref = requireNotNull(findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE))
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

        val customApiPref = requireNotNull(findPreference<TwoStatePreference>(KEY_CUSTOM_API_ENABLED))
        customApiPref.isChecked = ShizukuSettings.isCustomApiEnabled()
        customApiPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_CUSTOM_API_ENABLED, newValue) {
                    ShizukuSettings.setCustomApiEnabled(newValue)
                    customApiPref.isChecked = newValue
                    ShizukuSettings.syncAllPlusFeaturesToServer()
                    updateAllPlusFeatureDependencies()
                }
            }
            false
        }

        val hideDisabledPref = findPreference<TwoStatePreference>("hide_disabled_plus_features")
        hideDisabledPref?.isChecked = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()
        hideDisabledPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setHideDisabledPlusFeaturesEnabled(newValue)
                updateAllPlusFeatureDependencies()
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
        plusKeys.forEach { (prefKey, featureName) ->
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                ShizukuSettings.syncAllPlusFeaturesToServer()
                updatePlusFeatureDependency(prefKey, newValue as? Boolean ?: false)
                true
            }
        }

        // Initialize all preference dependencies
        updateAllPlusFeatureDependencies()
    }

    private fun updateAllPlusFeatureDependencies() {
        val customApiEnabled = ShizukuSettings.isCustomApiEnabled()
        val hideDisabled = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()

        // Update all preferences that depend on custom_api_enabled
        updatePreferenceDependency("shell_interceptor_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("avf_manager_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("storage_proxy_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("continuity_bridge_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("ai_core_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("window_manager_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("network_governor_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("activity_manager_plus_enabled", customApiEnabled, hideDisabled)

        // These also depend on window_manager_plus_enabled
        val windowManagerPlusEnabled = ShizukuSettings.isWindowManagerPlusEnabled() && customApiEnabled
        updatePreferenceDependency("overlay_manager_plus_enabled", windowManagerPlusEnabled, hideDisabled)
        
        // Fix scrolling: Force RecyclerView to recalculate layout after hiding/showing items
        listView?.post {
            listView?.requestLayout()
            listView?.invalidate()
        }
    }

    private fun updatePreferenceDependency(prefKey: String, parentEnabled: Boolean, hideIfDisabled: Boolean = false) {
        findPreference<TwoStatePreference>(prefKey)?.apply {
            isEnabled = parentEnabled
            if (!parentEnabled) {
                isChecked = false
            }
            isVisible = if (hideIfDisabled) parentEnabled else true
        }
    }

    private fun updatePlusFeatureDependency(prefKey: String, newValue: Boolean) {
        val hideDisabled = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()
        when (prefKey) {
            "window_manager_plus_enabled" -> {
                // overlay_manager_plus_enabled depends on window_manager_plus_enabled
                updatePreferenceDependency("overlay_manager_plus_enabled", newValue && ShizukuSettings.isCustomApiEnabled(), hideDisabled)
            }
        }
    }
}
