package af.shizuku.manager.settings
import af.shizuku.manager.activitylog.ActivityLogActivity

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.home.disablePairingAssistant
import af.shizuku.manager.home.enablePairingAssistant
import af.shizuku.manager.home.isPairingAssistantEnabled
import af.shizuku.manager.home.showAccessibilityDialog
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.setComponentEnabled
import android.widget.Toast
import timber.log.Timber
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.BuildConfig
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.utils.ProjectLinks
import androidx.preference.TwoStatePreference

class AdvancedSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_advanced_diagnostics_title)

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_advanced, rootKey)
        val context = requireContext()

        findPreference<Preference>("update_app_database")?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = java.net.URL(ProjectLinks.APPS_DB)
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    val content = try {
                        connection.instanceFollowRedirects = true
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000

                        val responseCode = connection.responseCode
                        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                            throw java.io.IOException("HTTP $responseCode from GitHub")
                        }

                        connection.inputStream.use { it.bufferedReader().readText() }
                    } finally {
                        connection.disconnect()
                    }
                    withContext(Dispatchers.Main) {
                        AppContextManager.updateDatabase(content)
                        Toast.makeText(context, R.string.settings_update_app_database_success, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Timber.w("update app database failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.settings_update_app_database_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }

        findPreference<Preference>("service_doctor")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ServiceDoctorActivity::class.java))
            true
        }

        findPreference<Preference>("activity_log")?.setOnPreferenceClickListener {
            startActivity(Intent(context, ActivityLogActivity::class.java))
            true
        }

        findPreference<TwoStatePreference>(KEY_ENABLE_ACTIVITY_LOG)?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllPlusFeaturesToServer()
            true
        }

        findPreference<Preference>("scripting")?.setOnPreferenceClickListener {
            startActivity(Intent(context, af.shizuku.manager.scripting.ScriptingActivity::class.java))
            true
        }

        findPreference<TwoStatePreference>(KEY_LEGACY_PAIRING)?.apply {
            isVisible = !EnvironmentUtils.isTelevision()
        }

        // Auto pairing: mirrors the pairing-assistant accessibility service. Turning it on
        // enables the service directly when WRITE_SECURE_SETTINGS is available (same linkage
        // as the AI core switch in the feature hub), otherwise falls back to the guided
        // accessibility-enable dialog.
        findPreference<TwoStatePreference>("auto_pairing")?.apply {
            isVisible = !EnvironmentUtils.isTelevision()
            isChecked = context.isPairingAssistantEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                val ctx = context ?: return@setOnPreferenceChangeListener false
                if (enable) {
                    if (!ctx.enablePairingAssistant()) {
                        ctx.showAccessibilityDialog()
                    }
                } else {
                    ctx.disablePairingAssistant()
                }
                true
            }
        }

        findPreference<Preference>(KEY_HELP)?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, context.getString(R.string.help_url))
            true
        }

        // Auto-restore developer options on boot. Requires WRITE_SECURE_SETTINGS (an adb-granted
        // appop that survives reboots); without it the toggle still persists but does nothing, so
        // surface the permission state instead of failing silently.
        findPreference<TwoStatePreference>("auto_restore_developer_options")?.apply {
            isChecked = ShizukuSettings.isAutoRestoreDeveloperOptionsEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                val enable = newValue as Boolean
                ShizukuSettings.setAutoRestoreDeveloperOptionsEnabled(enable)
                if (enable && context != null &&
                    context!!.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(
                        context,
                        R.string.settings_auto_restore_dev_options_no_permission,
                        Toast.LENGTH_LONG
                    ).show()
                }
                true
            }
        }

        findPreference<Preference>(KEY_REPORT_BUG)?.setOnPreferenceClickListener {
            BugReportDialog().show(parentFragmentManager, "BugReportDialog")
            true
        }

        findPreference<Preference>("reset_adb_keys")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.settings_reset_adb_keys)
                .setMessage(R.string.settings_reset_adb_keys_summary)
                .setPositiveButton(R.string.settings_reset_adb_keys) { _, _ ->
                    try {
                        ShizukuSettings.getPreferences().edit().remove("adbkey").apply()
                        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                        keyStore.load(null)
                        keyStore.deleteEntry("_adbkey_encryption_key_")
                        Toast.makeText(context, R.string.settings_reset_adb_keys_success, Toast.LENGTH_SHORT).show()
                        (activity as? SettingsActivity)?.onThemeChanged()
                    } catch (e: Exception) {
                        Timber.tag("AdvancedSettings").e(e, "Failed to reset ADB keys")
                        Toast.makeText(context, R.string.settings_reset_adb_keys_error, Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        // The manifest's namespace (af.shizuku.manager) differs from the per-flavor applicationId
        // (context.packageName), so ".LauncherAlias" must resolve against the namespace, not the
        // package name, or ComponentName construction throws "Component class ... does not exist"
        // (SHIZUKUPLUS-7R).
        val launcherAlias = ComponentName(context, "af.shizuku.manager.LauncherAlias")
        findPreference<TwoStatePreference>("stealth_mode")?.apply {
            isChecked = ShizukuSettings.isStealthModeEnabled()
            setOnPreferenceChangeListener { pref, newValue ->
                val enable = newValue as Boolean
                if (enable) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.settings_enable_stealth_title)
                        .setMessage(
                            getString(
                                R.string.settings_enable_stealth_message,
                                "adb shell pm enable ${context.packageName}/af.shizuku.manager.LauncherAlias"
                            )
                        )
                        .setPositiveButton(R.string.settings_enable_stealth_confirm) { _, _ ->
                            context.packageManager.setComponentEnabled(launcherAlias, false)
                            ShizukuSettings.setStealthModeEnabled(true)
                            (pref as? TwoStatePreference)?.isChecked = true
                        }
                        .setNegativeButton(android.R.string.cancel) { _, _ ->
                            (pref as? TwoStatePreference)?.isChecked = false
                        }
                        .show()
                    false
                } else {
                    context.packageManager.setComponentEnabled(launcherAlias, true)
                    ShizukuSettings.setStealthModeEnabled(false)
                    true
                }
            }
        }
    }
}
