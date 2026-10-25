package af.shizuku.manager.settings

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.security.BiometricLock
import androidx.biometric.BiometricPrompt
import af.shizuku.manager.utils.CrashHandler
import af.shizuku.manager.utils.CrashReporter
import af.shizuku.manager.utils.CustomTabsHelper

class DeveloperOptionsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_developer_options)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_developer_options, rootKey)

        // Vector / AVF Manager
        findPreference<TwoStatePreference>(KEY_VECTOR_ENABLED)?.apply {
            isChecked = ShizukuSettings.isVectorEnabled()
            setOnPreferenceChangeListener { _, v ->
                ShizukuSettings.setVectorEnabled(v as Boolean)
                ShizukuSettings.syncAllExtraFeaturesToServer()
                true
            }
        }

        // Experimental Root Compatibility
        findPreference<TwoStatePreference>(KEY_EXPERIMENTAL_ROOT_COMPAT)?.apply {
            isChecked = ShizukuSettings.isExperimentalRootCompatEnabled()
            setOnPreferenceChangeListener { _, v ->
                ShizukuSettings.setExperimentalRootCompatEnabled(v as Boolean)
                ShizukuSettings.syncAllExtraFeaturesToServer()
                true
            }
        }

        // Device Identity Spoofing
        findPreference<TwoStatePreference>(KEY_SPOOF_DEVICE_ENABLED)?.apply {
            isChecked = ShizukuSettings.isSpoofDeviceEnabled()
            setOnPreferenceChangeListener { _, v ->
                ShizukuSettings.setSpoofDeviceEnabled(v as Boolean)
                ShizukuSettings.syncAllExtraFeaturesToServer()
                true
            }
        }

        // Spoof Target
        findPreference<rikka.preference.SimpleMenuPreference>(KEY_SPOOF_TARGET)?.setOnPreferenceChangeListener { _, v ->
            ShizukuSettings.setSpoofTarget(if (v == "auto") "auto" else v as String)
            ShizukuSettings.syncAllExtraFeaturesToServer()
            true
        }

        // Manual Crash Report
        findPreference<Preference>("manual_report")?.apply {
            val ctx = context ?: return@apply
            val hasLastCrash = CrashHandler.getLastCrashReport(ctx) != null
            if (hasLastCrash) {
                setTitle(R.string.manual_report_last_crash_title)
                setSummary(R.string.manual_report_last_crash_summary)
            }

            setOnPreferenceClickListener {
                showDialog(
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(if (hasLastCrash) R.string.manual_report_last_crash_title else R.string.manual_report_title)
                        .setMessage(R.string.manual_report_summary)
                        .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                            lifecycleScope.launch {
                                val report = CrashReporter.generateReport(ctx)
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                    ctx.getString(R.string.manual_report_clipboard_label), report))

                                Toast.makeText(ctx, R.string.manual_report_toast_copied, Toast.LENGTH_LONG).show()

                                val url = CrashReporter.getGitHubReportUrl(ctx)
                                CustomTabsHelper.launchUrlOrCopy(ctx, url)

                                if (hasLastCrash) {
                                    CrashHandler.clearLastCrash(ctx)
                                }
                            }
                        }
                        .setNeutralButton(R.string.manual_report_copied_dialog_share) { _, _ ->
                            lifecycleScope.launch {
                                CrashReporter.shareAsFile(ctx)
                                if (hasLastCrash) {
                                    CrashHandler.clearLastCrash(ctx)
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                )
                true
            }
        }

        // ---- Plus experimental features (moved from Feature Hub developer bucket) ----
        val plusKeys = listOf(
            "window_manager_plus_enabled" to "window_manager_plus",
            "overlay_manager_plus_enabled" to "overlay_manager_plus",
            "ai_core_plus_enabled" to "ai_core_plus",
            "ai_core_master_enabled" to "ai_core_master",
            "npu_acceleration_enabled" to "npu_acceleration",
            "native_window_crawler_enabled" to "native_window_crawler",
            "ai_core_experimental_enabled" to "ai_core_experimental",
            "samsung_system_uid_escalation_enabled" to "samsung_system_uid_escalation"
        )
        val experimentalKeys = setOf(
            "ai_core_master_enabled",
            "npu_acceleration_enabled",
            "native_window_crawler_enabled",
            "ai_core_experimental_enabled",
            "samsung_system_uid_escalation_enabled"
        )

        plusKeys.forEach { (prefKey, featureName) ->
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as? Boolean ?: false
                if (enabled && experimentalKeys.contains(prefKey)) {
                    showExperimentalWarning(prefKey) {
                        preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, true)?.apply()
                        // Cascade child state BEFORE syncing so the server sees a consistent
                        // parent+child snapshot (disabling a parent force-unchecks children).
                        updatePlusFeatureDependency(prefKey, true)
                        ShizukuSettings.syncAllExtraFeaturesToServer()
                    }
                    false // Handle manually after dialog
                } else {
                    preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, enabled)?.apply()
                    updatePlusFeatureDependency(prefKey, enabled)
                    ShizukuSettings.syncAllExtraFeaturesToServer()
                    true
                }
            }
        }

        // AICore+ enablement requires biometric authentication when available
        findPreference<TwoStatePreference>("ai_core_plus_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            if (enabled) {
                val ctx = context ?: return@setOnPreferenceChangeListener false
                val lock = BiometricLock(requireActivity())
                if (lock.canAuthenticate(ctx)) {
                    lock.authenticate({
                        ShizukuSettings.setAICoreExtraEnabled(true)
                        ShizukuSettings.syncAllExtraFeaturesToServer()
                        activity?.runOnUiThread {
                            findPreference<TwoStatePreference>("ai_core_plus_enabled")?.isChecked = true
                            updatePlusFeatureDependency("ai_core_plus_enabled", true)
                        }
                    }, { _ -> /* Ignore or show toast */ })
                    return@setOnPreferenceChangeListener false
                }
            }
            // fallback / standard or disabling
            preferenceManager.sharedPreferences?.edit()?.putBoolean("ai_core_plus_enabled", enabled)?.apply()
            updatePlusFeatureDependency("ai_core_plus_enabled", enabled)
            ShizukuSettings.syncAllExtraFeaturesToServer()
            true
        }

        // Initialize all preference dependencies and app integrations
        updateExperimentalDependencies()
        checkExperimentalIntegrations()
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

    private fun checkExperimentalIntegrations() {
        val integrations = mapOf(
            "window_manager_plus_enabled" to listOf(
                "com.arlosoft.macrodroid" to "MacroDroid",
                "com.isaiasmatewos.taskbar" to "Taskbar"
            ),
            "overlay_manager_plus_enabled" to listOf(
                "project.vivid.hex.nx" to "Hex Installer",
                "tk.wasdennnoch.substratumlite" to "Substratum Lite"
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

    private fun updateExperimentalDependencies() {
        val customApiEnabled = ShizukuSettings.isCustomApiEnabled()
        val hideDisabled = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()

        updatePreferenceDependency("window_manager_plus_enabled", customApiEnabled, hideDisabled)
        updatePreferenceDependency("ai_core_plus_enabled", customApiEnabled, hideDisabled)
        val aiCoreExtraEnabled = ShizukuSettings.isAICoreExtraEnabled() && customApiEnabled
        updatePreferenceDependency("ai_core_master_enabled", aiCoreExtraEnabled, hideDisabled)
        updatePreferenceDependency("ai_core_experimental_enabled", aiCoreExtraEnabled, hideDisabled)
        val aiCoreMasterEnabled = ShizukuSettings.isAiCoreMasterEnabled() && aiCoreExtraEnabled
        updatePreferenceDependency("npu_acceleration_enabled", aiCoreMasterEnabled, hideDisabled)
        updatePreferenceDependency("native_window_crawler_enabled", aiCoreMasterEnabled, hideDisabled)

        // overlay_manager_plus also depends on window_manager_plus_enabled
        val windowManagerExtraEnabled = ShizukuSettings.isWindowManagerPlusEnabled() && customApiEnabled
        updatePreferenceDependency("overlay_manager_plus_enabled", windowManagerExtraEnabled, hideDisabled)
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

    private fun updatePlusFeatureDependency(prefKey: String, newValue: Boolean) {
        val hideDisabled = ShizukuSettings.isHideDisabledPlusFeaturesEnabled()
        val customApiEnabled = ShizukuSettings.isCustomApiEnabled()
        when (prefKey) {
            "window_manager_plus_enabled" -> {
                updatePreferenceDependency("overlay_manager_plus_enabled", newValue && customApiEnabled, hideDisabled)
            }
            "ai_core_plus_enabled" -> {
                val active = newValue && customApiEnabled
                updatePreferenceDependency("ai_core_master_enabled", active, hideDisabled)
                updatePreferenceDependency("ai_core_experimental_enabled", active, hideDisabled)
                val masterActive = ShizukuSettings.isAiCoreMasterEnabled() && active
                updatePreferenceDependency("npu_acceleration_enabled", masterActive, hideDisabled)
                updatePreferenceDependency("native_window_crawler_enabled", masterActive, hideDisabled)
            }
            "ai_core_master_enabled" -> {
                val aiCoreExtraActive = ShizukuSettings.isAICoreExtraEnabled() && customApiEnabled
                val active = newValue && aiCoreExtraActive
                updatePreferenceDependency("npu_acceleration_enabled", active, hideDisabled)
                updatePreferenceDependency("native_window_crawler_enabled", active, hideDisabled)
            }
        }
    }
}
