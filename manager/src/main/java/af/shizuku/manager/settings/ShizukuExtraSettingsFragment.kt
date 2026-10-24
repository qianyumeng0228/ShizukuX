package af.shizuku.manager.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.html.text.toHtml
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*

import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.core.view.MenuProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ShizukuExtraSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_feature_hub_title)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (!isAdded) return
        setPreferencesFromResource(R.xml.settings_shizuku_extra, rootKey)

        ShizukuSettings.syncAllExtraFeaturesToServer()

        // Setup menu for 'Learn more' icon
        activity?.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                if (!isAdded) return
                menu.clear()
                menuInflater.inflate(R.menu.extra_settings_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (!isAdded) return false
                if (menuItem.itemId == R.id.action_plus_help) {
                    showGeneralHelpDialog()
                    return true
                }
                return false
            }
        }, this)

        val customApiPref = requireNotNull(findPreference<TwoStatePreference>(KEY_CUSTOM_API_ENABLED))
        customApiPref.isChecked = ShizukuSettings.isCustomApiEnabled()
        customApiPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                maybePromptRestart(KEY_CUSTOM_API_ENABLED, newValue) {
                    ShizukuSettings.setCustomApiEnabled(newValue)
                    customApiPref.isChecked = newValue
                    ShizukuSettings.syncAllExtraFeaturesToServer()
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
            "network_governor_plus_enabled" to "network_governor_plus",
            "activity_manager_plus_enabled" to "activity_manager_plus",
            "shadow_binder_enabled" to "shadow_binder",
            "binder_firewall_enabled" to "binder_firewall",
            "binder_logging_enabled" to "binder_logging"
        )
        val experimentalKeys = setOf(
            "avf_manager_enabled"
        )

        plusKeys.forEach { (prefKey, featureName) ->
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                if (enabled && experimentalKeys.contains(prefKey)) {
                    showExperimentalWarning(prefKey) {
                        preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, true)?.apply()
                        ShizukuSettings.syncAllExtraFeaturesToServer()
                    }
                    false // Handle manually after dialog
                } else {
                    preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, enabled)?.apply()
                    ShizukuSettings.syncAllExtraFeaturesToServer()
                    true
                }
            }
        }

        findPreference<Preference>(KEY_SHADOW_BINDER_HIDDEN_PACKAGES)?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllExtraFeaturesToServer()
            true
        }

        // Initialize all preference dependencies
        updateAllPlusFeatureDependencies()

        // Check for integrated apps and update summaries
        checkAppIntegrations()

        // External relay authorization entry: opens the dedicated management screen where the
        // user can manage relayed apps (Scene today, more apps later).
        findPreference<Preference>("external_relay_manager")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), ExternalRelayActivity::class.java))
            true
        }
    }

    private fun showGeneralHelpDialog() {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_shizuku_extra_features)
            .setMessage(getString(R.string.help_general_plus_summary).toHtml())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showExperimentalWarning(prefKey: String, onConfirm: () -> Unit) {
        val context = context ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_experimental_warning_title)
            .setMessage(R.string.settings_experimental_warning_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pref = findPreference<TwoStatePreference>(prefKey)
                pref?.isChecked = true
                onConfirm()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun checkAppIntegrations() {
        val integrations = mapOf(
            "continuity_bridge_enabled" to listOf(
                "com.arlosoft.macrodroid" to "MacroDroid"
            ),
            "activity_manager_plus_enabled" to listOf(
                "com.arlosoft.macrodroid" to "MacroDroid",
                "net.dinglisch.android.taskerm" to "Tasker"
            ),
            "network_governor_plus_enabled" to listOf(
                "dev.ukanth.ufirewall" to "AFWall+"
            ),
            "storage_proxy_enabled" to listOf(
                "com.machiav3lli.neo_backup" to "Neo Backup",
                "eu.darken.sdm" to "SD Maid",
                "eu.darken.sdmse" to "SD Maid SE"
            ),
            "root_magisk_mocking_enabled" to listOf(
                "com.topjohnwu.magisk" to "Magisk Manager"
            )
        )

        val pm = (context ?: return).packageManager
        lifecycleScope.launch(Dispatchers.IO) {
            val found = integrations.mapValues { (_, apps) ->
                apps.find { (pkg, _) ->
                    try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
                }
            }
            launch(Dispatchers.Main) {
                found.forEach { (prefKey, foundApp) ->
                    if (foundApp != null) {
                        findPreference<ExtraFeaturePreference>(prefKey)?.apply {
                            setIntegration(foundApp.first, foundApp.second)
                            val originalSummary = summary
                            summary = getString(R.string.settings_extra_app_found, foundApp.second) + "\n\n" + originalSummary
                        }
                    }
                }
            }
        }
    }

    private fun updateAllPlusFeatureDependencies() {
        val customApiEnabled = ShizukuSettings.isCustomApiEnabled()
        val hideDisabled = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()

        // Update all preferences that depend on custom_api_enabled
        updatePreferenceDependency("shell_interceptor_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("avf_manager_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("storage_proxy_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("continuity_bridge_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("network_governor_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("activity_manager_plus_enabled", customApiEnabled, hideDisabled)

        // Binder firewall & the external-relay subcategory live inside the collapsible
        // connectivity category. When that category starts collapsed, updateChildren() hides
        // every child; these two are not covered by the dependency list above, so restore
        // their visibility here (Scene relay does not require the enhanced API).
        updatePreferenceDependency("binder_firewall_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("category_external_relay", true, hideDisabled)

        // Force RecyclerView to recalculate layout after hiding/showing items.
        // Guard: PreferenceFragmentCompat.getListView() throws (not returns null) before
        // onCreateView completes, so the safe-call (?.) does NOT protect us — use
        // Fragment.getView() which correctly returns null when the view isn't ready.
        view?.post {
            if (isAdded && view != null) {
                try {
                    listView.requestLayout()
                    listView.invalidate()
                } catch (e: IllegalStateException) {
                    // Fragment view was destroyed between post() scheduling and execution
                }
            }
        }
    }

    private fun updatePreferenceDependency(prefKey: String, parentEnabled: Boolean, hideIfDisabled: Boolean = false) {
        findPreference<Preference>(prefKey)?.apply {
            isEnabled = parentEnabled
            if (this is TwoStatePreference && !parentEnabled) {
                isChecked = false
            }
            isVisible = if (hideIfDisabled) parentEnabled else true
        }
    }
}
