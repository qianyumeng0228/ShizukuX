package moe.shizuku.manager.settings

import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.service.AdbProxyService
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

class LegacyCompatSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_legacy_compat, rootKey)
        val context = requireContext()

        // Sync current su_bridge state to server on fragment open
        ShizukuSettings.syncAllPlusFeaturesToServer()

        findPreference<TwoStatePreference>("adb_proxy_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                val intent = Intent(context, AdbProxyService::class.java)
                if (newValue) context.startService(intent) else context.stopService(intent)
            }
            true
        }

        findPreference<TwoStatePreference>("on_device_adb_tcp")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                lifecycleScope.launch(Dispatchers.IO) {
                    if (newValue) {
                        moe.shizuku.manager.service.AdbProxyService.enableAdbTcp()
                    } else {
                        moe.shizuku.manager.service.AdbProxyService.disableAdbTcp()
                    }
                }
            }
            true
        }

        findPreference<TwoStatePreference>("force_start_wadb")?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        findPreference<TwoStatePreference>("su_bridge_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.syncAllPlusFeaturesToServer()
            }
            true
        }

        findPreference<Preference>("root_compatibility_hub")?.setOnPreferenceClickListener {
            startActivity(Intent(context, RootCompatibilityActivity::class.java))
            true
        }
    }
}
