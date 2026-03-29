package moe.shizuku.manager.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Sentry
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.update.UpdateChecker
import moe.shizuku.manager.update.UpdateManager

/**
 * Settings fragment for auto-update configuration
 */
class UpdateSettingsFragment : BaseSettingsFragment() {

    companion object {
        private const val TAG = "UpdateSettingsFragment"
        private const val KEY_AUTO_UPDATE = "auto_update_enabled"
        private const val KEY_CHECK_FOR_UPDATE = "check_for_update"
        private const val KEY_AUTO_INSTALL = "auto_install_enabled"
        private const val KEY_CURRENT_VERSION = "current_version"
        private const val KEY_LAST_CHECK = "last_check_time"
    }

    private lateinit var updateManager: UpdateManager

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_update, rootKey)
        updateManager = UpdateManager(requireContext())

        setupAutoUpdatePreference()
        setupAutoInstallPreference()
        setupCheckForUpdatePreference()
        setupCurrentVersionPreference()
        updateLastCheckTime()
    }

    private fun setupAutoUpdatePreference() {
        val autoUpdatePref = findPreference<TwoStatePreference>(KEY_AUTO_UPDATE)
        autoUpdatePref?.isChecked = ShizukuSettings.isAutoUpdateEnabled()
        autoUpdatePref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                ShizukuSettings.setAutoUpdateEnabled(newValue)
                autoUpdatePref.isChecked = newValue
                // If disabling auto-update, also disable auto-install
                if (!newValue) {
                    findPreference<TwoStatePreference>(KEY_AUTO_INSTALL)?.isChecked = false
                    ShizukuSettings.setAutoInstallEnabled(false)
                }
            }
            false
        }
    }

    private fun setupAutoInstallPreference() {
        val autoInstallPref = findPreference<TwoStatePreference>(KEY_AUTO_INSTALL)
        autoInstallPref?.isEnabled = ShizukuSettings.isAutoUpdateEnabled()
        autoInstallPref?.isChecked = ShizukuSettings.isAutoInstallEnabled()
        autoInstallPref?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                // Check if user has granted install permission
                if (newValue && !updateManager.canRequestPackageInstalls()) {
                    showPermissionRequiredDialog()
                    return@setOnPreferenceChangeListener false
                }
                ShizukuSettings.setAutoInstallEnabled(newValue)
                autoInstallPref.isChecked = newValue
            }
            false
        }
    }

    private fun setupCheckForUpdatePreference() {
        val checkUpdatePref = findPreference<Preference>(KEY_CHECK_FOR_UPDATE)
        checkUpdatePref?.setOnPreferenceClickListener {
            checkForUpdate(showProgress = true)
            true
        }
    }

    private fun setupCurrentVersionPreference() {
        val versionPref = findPreference<Preference>(KEY_CURRENT_VERSION)
        versionPref?.summary = BuildConfig.VERSION_NAME
    }

    private fun updateLastCheckTime() {
        val lastCheckPref = findPreference<Preference>(KEY_LAST_CHECK)
        val lastCheckTime = ShizukuSettings.getLastUpdateCheckTime()
        lastCheckPref?.summary = if (lastCheckTime > 0) {
            UpdateChecker.formatPublishedDate(
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                    .format(java.util.Date(lastCheckTime))
            )
        } else {
            getString(R.string.update_never_checked)
        }
    }

    /**
     * Check for updates manually
     */
    private fun checkForUpdate(showProgress: Boolean) {
        if (showProgress) {
            // Show a simple toast to indicate check is starting
            android.widget.Toast.makeText(requireContext(), R.string.update_checking, android.widget.Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch {
            try {
                val updateInfo = UpdateChecker.checkForUpdate()

                if (updateInfo != null) {
                    showUpdateAvailableDialog(updateInfo)
                } else {
                    showUpToDateDialog()
                }

                ShizukuSettings.setLastUpdateCheckTime(System.currentTimeMillis())
                updateLastCheckTime()
            } catch (e: Exception) {
                Log.e(TAG, "Error checking for update", e)
                Sentry.captureException(e)
                showErrorDialog()
            }
        }
    }

    /**
     * Show dialog when update is available
     */
    private fun showUpdateAvailableDialog(updateInfo: UpdateChecker.UpdateInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_available, null)
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.update_available_title))
            .setMessage(getString(R.string.update_available_message, updateInfo.versionName))
            .setPositiveButton(R.string.update_download) { _, _ ->
                updateManager.downloadUpdate(updateInfo.downloadUrl, updateInfo.versionName)
            }
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_release_notes) { _, _ ->
                // Open release notes in browser
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/thejaustin/ShizukuPlus/releases"))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            .show()
    }

    /**
     * Show dialog when already on latest version
     */
    private fun showUpToDateDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_up_to_date_title)
            .setMessage(getString(R.string.update_up_to_date_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * Show error dialog
     */
    private fun showErrorDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_error_title)
            .setMessage(R.string.update_error_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * Show permission required dialog
     */
    private fun showPermissionRequiredDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_permission_required_title)
            .setMessage(R.string.update_permission_required_message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * Public method to trigger update check from outside
     */
    fun triggerUpdateCheck() {
        checkForUpdate(showProgress = false)
    }
}
