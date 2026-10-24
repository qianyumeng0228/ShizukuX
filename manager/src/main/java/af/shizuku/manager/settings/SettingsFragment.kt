package af.shizuku.manager.settings

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.KEY_DHIZUKU_MODE
import rikka.shizuku.Shizuku

class SettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_title)

    override fun onResume() {
        super.onResume()
        // Refresh Dhizuku device-owner status when returning to this page.
        // The status can change externally (e.g. device owner transferred from
        // another app like Dhizuku), and onCreatePreferences only runs once.
        val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)
        if (dhizukuPref != null) {
            updateDhizukuDeviceOwnerStatus(dhizukuPref)
        }
    }

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_main, rootKey)

        val dhizukuPref = requireNotNull(findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE))
        dhizukuPref.isChecked = ShizukuSettings.isDhizukuModeEnabled()
        updateDhizukuDeviceOwnerStatus(dhizukuPref)
        dhizukuPref.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            if (!isDeviceOwnerActive(ctx)) {
                showDhizukuSetupDialog(ctx)
                return@setOnPreferenceClickListener true
            }
            false // let the normal toggle happen
        }
        dhizukuPref.setOnPreferenceChangeListener { _, newValue ->
            if (newValue is Boolean) {
                val ctx = context ?: return@setOnPreferenceChangeListener false
                // If trying to enable but not device owner, show setup dialog instead of toggling
                if (newValue && !isDeviceOwnerActive(ctx)) {
                    showDhizukuSetupDialog(ctx)
                    return@setOnPreferenceChangeListener false
                }
                ShizukuSettings.setDhizukuModeEnabled(newValue)
                maybePromptRestart(KEY_DHIZUKU_MODE, newValue) {
                    dhizukuPref.isChecked = newValue
                }
            }
            true
        }

        // Clear Device Owner button — only visible when app holds DO status
        val clearDoPref = findPreference<Preference>("clear_device_owner")
        clearDoPref?.isVisible = isDeviceOwnerActive(requireContext())
        clearDoPref?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            showClearDeviceOwnerDialog(ctx)
            true
        }

        // Transfer Device Owner button — only visible when app holds DO status
        val transferDoPref = findPreference<Preference>("transfer_device_owner")
        transferDoPref?.isVisible = isDeviceOwnerActive(requireContext())
        transferDoPref?.setOnPreferenceClickListener {
            val ctx = context ?: return@setOnPreferenceClickListener true
            showTransferDeviceOwnerDialog(ctx)
            true
        }

        // Dhizuku App Management — list apps that may use the Dhizuku (device-owner) API
        findPreference<Preference>("dhizuku_app_management")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), DhizukuAppManagementActivity::class.java))
            true
        }

        // Device Owner Tools - Screen Capture Lockdown
        findPreference<TwoStatePreference>("dhizuku_disable_screencap")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            val ctx = context ?: return@setOnPreferenceChangeListener false
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                dpm.setScreenCaptureDisabled(admin, enabled)
                Toast.makeText(
                    ctx,
                    if (enabled) R.string.settings_screen_capture_disabled else R.string.settings_screen_capture_enabled,
                    Toast.LENGTH_SHORT
                ).show()
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.settings_device_owner_required, Toast.LENGTH_LONG).show()
                false
            }
        }

        // Device Owner Tools - USB Data Lockdown
        findPreference<TwoStatePreference>("dhizuku_disallow_usb")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: false
            val ctx = context ?: return@setOnPreferenceChangeListener false
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                if (enabled) {
                    dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    Toast.makeText(ctx, R.string.settings_usb_data_locked, Toast.LENGTH_SHORT).show()
                } else {
                    dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_USB_FILE_TRANSFER)
                    Toast.makeText(ctx, R.string.settings_usb_data_unlocked, Toast.LENGTH_SHORT).show()
                }
                true
            } catch (e: Exception) {
                Toast.makeText(ctx, R.string.settings_device_owner_required, Toast.LENGTH_LONG).show()
                false
            }
        }

        // Device Owner Tools - App Freezing
        findPreference<Preference>("dhizuku_suspended_packages")?.setOnPreferenceChangeListener { _, newValue ->
            val packagesStr = newValue as? String ?: ""
            val packagesList = if (packagesStr.isBlank()) emptyList() else packagesStr.split(",").map { it.trim() }
            val ctx = context ?: return@setOnPreferenceChangeListener false
            // getInstalledPackages() + setPackagesSuspended() are both real IPCs (PackageManager /
            // DevicePolicyManager); do them off the main thread rather than blocking this
            // preference-change callback like the now-fixed RootCompatibilityActivity.buildItems().
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                    val pm = ctx.packageManager
                    val installed = pm.getInstalledPackages(0).map { it.packageName }.toSet()

                    // Clear any existing suspensions first
                    val toUnsuspend = installed.toMutableList()
                    dpm.setPackagesSuspended(admin, toUnsuspend.toTypedArray(), false)

                    // Set chosen suspensions
                    var message: String? = null
                    if (packagesList.isNotEmpty()) {
                        val toSuspend = packagesList.filter { installed.contains(it) }
                        val failed = dpm.setPackagesSuspended(admin, toSuspend.toTypedArray(), true)
                        message = if (failed.isNotEmpty()) {
                            ctx.getString(R.string.settings_frozen_apps_failed, failed.joinToString())
                        } else {
                            ctx.getString(R.string.settings_frozen_apps_count, toSuspend.size)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        message?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, R.string.settings_device_owner_required, Toast.LENGTH_LONG).show()
                    }
                }
            }
            true
        }
    }

    private fun isDeviceOwnerActive(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName) || dpm.isProfileOwnerApp(ctx.packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun updateDhizukuDeviceOwnerStatus(pref: TwoStatePreference) {
        val ctx = context ?: return
        val active = isDeviceOwnerActive(ctx)
        val statusLine = if (active)
            getString(R.string.dhizuku_status_active)
        else
            getString(R.string.dhizuku_status_not_set)
        val baseSummary = getString(R.string.settings_dhizuku_mode_summary)
        pref.summary = "$statusLine\n\n$baseSummary"
        // If device owner is active, ensure Dhizuku mode is enabled (they go hand-in-hand)
        if (active && !pref.isChecked) {
            pref.isChecked = true
            ShizukuSettings.setDhizukuModeEnabled(true)
        }
        // Show/hide the Clear Owner and Transfer Owner buttons based on active status
        findPreference<Preference>("clear_device_owner")?.isVisible = active
        findPreference<Preference>("transfer_device_owner")?.isVisible = active
    }

    private fun showClearDeviceOwnerDialog(ctx: Context) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_clear_owner_title)
            .setMessage(R.string.dhizuku_clear_owner_message)
            .setPositiveButton(R.string.dhizuku_clear_owner_confirm) { _, _ ->
                clearDeviceOwner(ctx)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun clearDeviceOwner(ctx: Context) {
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                dpm.clearDeviceOwnerApp(ctx.packageName)
            } else if (dpm.isProfileOwnerApp(ctx.packageName)) {
                val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                dpm.clearProfileOwner(admin)
            }
            Toast.makeText(ctx, R.string.dhizuku_clear_owner_success, Toast.LENGTH_LONG).show()
            // Refresh the UI to reflect the change
            val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)
            if (dhizukuPref != null) {
                ShizukuSettings.setDhizukuModeEnabled(false)
                dhizukuPref.isChecked = false
                updateDhizukuDeviceOwnerStatus(dhizukuPref)
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.dhizuku_clear_owner_failure, Toast.LENGTH_LONG).show()
        }
    }

    private fun showDhizukuSetupDialog(ctx: Context) {
        // applicationId (xyz.shizuku.extra.api) differs from namespace (af.shizuku.manager),
        // so the full class name must be explicit rather than using the shorthand dot notation.
        val command = "adb shell dpm set-device-owner " +
            "${ctx.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"
        val dpmCommand = "dpm set-device-owner " +
            "${ctx.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"
        lateinit var setupDialog: androidx.appcompat.app.AlertDialog
        setupDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_setup_title)
            .setMessage(getString(R.string.dhizuku_setup_message, command))
            .setPositiveButton(R.string.dhizuku_setup_via_shizukux) { _, _ ->
                // Dismiss the setup dialog first, then show progress and activate
                setupDialog.dismiss()
                activateViaShizukuX(ctx, dpmCommand)
            }
            .setNeutralButton(R.string.dhizuku_setup_command_line) { _, _ ->
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("dpm command", command))
                Toast.makeText(ctx, R.string.dhizuku_setup_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        setupDialog.show()
    }

    private fun activateViaShizukuX(ctx: Context, command: String) {
        // Check if ShizukuX service is running
        if (!Shizuku.pingBinder()) {
            Toast.makeText(ctx, R.string.dhizuku_setup_shizukux_not_running, Toast.LENGTH_LONG).show()
            return
        }
        val progressDialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_setup_running)
            .setCancelable(false)
            .create()
        progressDialog.show()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                // Execute dpm command via ShizukuX's shell privilege (inherits shell/ADB UID,
                // which has permission to run dpm set-device-owner).
                // Explicit environment variables are required because Shizuku.newProcess may not
                // inherit the full shell environment (PATH, HOME, ANDROID_ROOT, etc.).
                val env = arrayOf(
                    "PATH=/system/bin:/system/xbin:/vendor/bin",
                    "HOME=/data/local/tmp",
                    "ANDROID_ROOT=/system",
                    "ANDROID_DATA=/data",
                    "EXTERNAL_STORAGE=/sdcard",
                    "TMPDIR=/data/local/tmp"
                )
                // Use full path to dpm to ensure command is found
                val fullCommand = "/system/bin/dpm set-device-owner " +
                    "${ctx.packageName}/af.shizuku.manager.admin.DhizukuAdminReceiver"
                val process = Shizuku.newProcess(
                    arrayOf("/system/bin/sh", "-c", fullCommand),
                    env,
                    "/data/local/tmp"
                )
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0 && (stdout + stderr).contains("Success")) {
                    Result.success(Unit)
                } else {
                    val error = (stdout + stderr).trim().ifEmpty { "exit code $exitCode" }
                    Result.failure(Exception(error))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (result.isSuccess) {
                    Toast.makeText(ctx, R.string.dhizuku_setup_shizukux_success, Toast.LENGTH_LONG).show()
                    val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)
                    if (dhizukuPref != null) {
                        ShizukuSettings.setDhizukuModeEnabled(true)
                        dhizukuPref.isChecked = true
                        updateDhizukuDeviceOwnerStatus(dhizukuPref)
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Unknown error"
                    Toast.makeText(ctx, getString(R.string.dhizuku_setup_shizukux_failure, error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTransferDeviceOwnerDialog(ctx: Context) {
        if (!isDeviceOwnerActive(ctx)) {
            Toast.makeText(ctx, R.string.dhizuku_transfer_not_owner, Toast.LENGTH_LONG).show()
            return
        }
        // Scan all apps that declare a DeviceAdminReceiver via the DEVICE_ADMIN_ENABLED action.
        val pm = ctx.packageName
        val candidates = mutableListOf<Pair<String, android.content.ComponentName>>()
        val seenPackages = mutableSetOf<String>()
        try {
            val intent = android.content.Intent("android.app.action.DEVICE_ADMIN_ENABLED")
            val receivers = ctx.packageManager.queryBroadcastReceivers(intent, 0)
            android.util.Log.d("ShizukuXTransfer", "queryBroadcastReceivers returned ${receivers.size} receivers")
            for (resolveInfo in receivers) {
                val activityInfo = resolveInfo.activityInfo ?: continue
                val packageName = activityInfo.packageName
                android.util.Log.d("ShizukuXTransfer", "found receiver: $packageName/${activityInfo.name}")
                if (packageName == pm) continue // skip self
                if (seenPackages.contains(packageName)) continue // dedupe by package: some apps declare multiple DPC receivers
                seenPackages.add(packageName)
                val name = activityInfo.name
                val appInfo = activityInfo.applicationInfo
                val label = appInfo?.loadLabel(ctx.packageManager)?.toString()
                    ?: activityInfo.loadLabel(ctx.packageManager)?.toString()
                    ?: packageName
                val component = android.content.ComponentName(packageName, name)
                candidates.add(label to component)
            }
        } catch (e: Exception) {
            android.util.Log.e("ShizukuXTransfer", "Exception scanning", e)
        }
        android.util.Log.d("ShizukuXTransfer", "candidates size: ${candidates.size}")
        if (candidates.isEmpty()) {
            Toast.makeText(ctx, R.string.dhizuku_transfer_no_targets, Toast.LENGTH_LONG).show()
            return
        }
        val labels = candidates.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_transfer_title)
            .setItems(labels) { _, which ->
                val (label, component) = candidates[which]
                confirmTransfer(ctx, label, component)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmTransfer(ctx: Context, label: String, target: android.content.ComponentName) {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dhizuku_transfer_confirm_title)
            .setMessage(getString(R.string.dhizuku_transfer_confirm_message, label))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    val admin = ComponentName(ctx, af.shizuku.manager.admin.DhizukuAdminReceiver::class.java)
                    dpm.transferOwnership(admin, target, null)
                    Toast.makeText(ctx, getString(R.string.dhizuku_transfer_success, label), Toast.LENGTH_LONG).show()
                    // Update UI: ShizukuX is no longer device owner
                    val dhizukuPref = findPreference<TwoStatePreference>(KEY_DHIZUKU_MODE)
                    if (dhizukuPref != null) {
                        ShizukuSettings.setDhizukuModeEnabled(false)
                        dhizukuPref.isChecked = false
                        updateDhizukuDeviceOwnerStatus(dhizukuPref)
                    }
                } catch (e: Exception) {
                    val error = e.message ?: e.javaClass.simpleName
                    android.util.Log.i("ShizukuXTransfer", "transferOwnership FAILED: pkg=" + ctx.packageName + " target=" + target + " ex=" + e.javaClass.name + " msg=" + error, e)
                    Toast.makeText(ctx, getString(R.string.dhizuku_transfer_failure, error), Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
