package af.shizuku.manager.settings

import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.receiver.DeveloperOptionsRestorer
import af.shizuku.manager.service.ShizukuLiveService
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BehaviorSettingsFragment : BaseSettingsFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    override fun getTitle(): CharSequence? = getString(R.string.settings_startup_behavior_title)

    private lateinit var startOnBootPreference: TwoStatePreference
    private lateinit var watchdogPreference: TwoStatePreference
    private lateinit var tcpModePreference: TwoStatePreference
    private lateinit var tcpPortPreference: EditTextPreference

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isRunning()) {
            tcpModePreference.icon = maybeGetRestartIcon(KEY_TCP_MODE)
            tcpPortPreference.icon = maybeGetRestartIcon(KEY_TCP_PORT)
        }
    }

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_behavior, rootKey)
        val context = requireContext()

        startOnBootPreference = requireNotNull(findPreference(KEY_START_ON_BOOT))
        watchdogPreference = requireNotNull(findPreference(KEY_WATCHDOG))
        tcpModePreference = requireNotNull(findPreference(KEY_TCP_MODE))
        tcpPortPreference = requireNotNull(findPreference(KEY_TCP_PORT))

        startOnBootPreference.apply {
            isChecked = ShizukuSettings.getStartOnBoot(context)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    val doToggle = {
                        maybeToggleSecureSetting(newValue) { secureResult ->
                            if (secureResult) {
                                maybeToggleBatterySensitiveSetting(newValue) { batteryResult ->
                                    if (batteryResult) {
                                        ShizukuSettings.setStartOnBoot(context, newValue)
                                        isChecked = ShizukuSettings.getStartOnBoot(context)
                                    }
                                }
                            }
                        }
                    }
                    // https://r.android.com/2128832
                        if (newValue &&
                            !EnvironmentUtils.isTelevision() &&
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        ) {
                            showDialog(
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(android.R.string.dialog_alert_title)
                                    .setMessage(R.string.settings_start_on_boot_bug)
                                    .setPositiveButton(android.R.string.ok) { _, _ -> doToggle() }
                                    .setNegativeButton(android.R.string.cancel) { _, _ -> isChecked = !newValue }
                            )
                        } else {
                            doToggle()
                        }
                    }
                    false
                }
        }

        watchdogPreference.apply {
            isChecked = ShizukuSettings.isWatchdogRunning()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleBatterySensitiveSetting(newValue) { result ->
                        if (result) {
                            ShizukuSettings.setWatchdog(context, newValue)
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        tcpModePreference.apply {
            if (EnvironmentUtils.isTlsSupported()) {
                summary = context.getString(R.string.settings_tcp_mode_summary)
                icon = maybeGetRestartIcon(KEY_TCP_MODE)
                setOnPreferenceChangeListener { _, newValue ->
                    if (newValue is Boolean) {
                        val applyChange: () -> Unit = {
                            ShizukuSettings.setTcpMode(newValue)
                            isChecked = newValue
                            isEnabled = true
                            summary = context.getString(R.string.settings_tcp_mode_summary)
                            icon = maybeGetRestartIcon(KEY_TCP_MODE)
                            syncTcpPortVisibility()
                        }
                        if (!newValue && !ShizukuStateMachine.isRunning() && needsRestart(KEY_TCP_MODE, newValue)) {
                            promptStopTcp(tcpModePreference) { applyChange() }
                        } else {
                            maybePromptRestart(KEY_TCP_MODE, newValue) { applyChange() }
                        }
                    }
                    false
                }
            } else if (EnvironmentUtils.isTelevision()) {
                isEnabled = false
                isChecked = true
            } else {
                isVisible = false
            }
        }

        tcpPortPreference.apply {
            syncTcpPortVisibility()
            icon = maybeGetRestartIcon(KEY_TCP_PORT)
            setOnBindEditTextListener { editText ->
                editText.hint = context.getString(R.string.settings_tcp_port_hint)
                editText.inputType = InputType.TYPE_CLASS_NUMBER
                editText.setSelection(editText.text.length)
            }
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                val text = pref.text
                if (text.isNullOrEmpty()) context.getString(R.string.settings_tcp_port_default) else text
            }
            setOnPreferenceChangeListener { _, newValue ->
                val port = (newValue as? String)?.toIntOrNull()
                if (port == null || port in 1..65535) {
                    val applyChange: () -> Unit = {
                        ShizukuSettings.setTcpPort(port)
                        text = port?.toString()
                        icon = maybeGetRestartIcon(KEY_TCP_PORT)
                    }
                    maybePromptRestart(KEY_TCP_PORT, port ?: 5555) { applyChange() }
                } else {
                    SnackbarHelper.show(context, requireView(), context.getString(R.string.snackbar_invalid_port))
                }
                false
            }
        }

        findPreference<TwoStatePreference>(KEY_AUTO_DISABLE_USB_DEBUGGING)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    maybeToggleSecureSetting(newValue) { result ->
                        if (result) {
                            isChecked = newValue
                        }
                    }
                }
                false
            }
        }

        findPreference<TwoStatePreference>(KEY_LIVE_ACTIVITY_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            val enable = newValue as Boolean
            val ctx = requireContext()
            val svcIntent = Intent(ctx, ShizukuLiveService::class.java)
            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svcIntent)
                else ctx.startService(svcIntent)
            } else {
                ctx.stopService(svcIntent)
            }
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
                    // Toast truncates the long adb-grant command — show a full dialog instead.
                    showDialog(
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.settings_auto_restore_dev_options_no_permission_title)
                            .setMessage(R.string.settings_auto_restore_dev_options_no_permission)
                            .setPositiveButton(android.R.string.ok, null)
                    )
                }
                true
            }
        }

        // Manual one-shot restore of developer options / USB debugging / wireless debugging.
        // Runs off the main thread: restore() may wait for the Shizuku-routed `svc wifi enable`
        // and a short settling delay before toggling adb_wifi_enabled.
        findPreference<Preference>("restore_developer_options_now")?.setOnPreferenceClickListener {
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    DeveloperOptionsRestorer.restore(requireContext())
                }
                Toast.makeText(
                    requireContext(),
                    if (ok) R.string.settings_restore_dev_options_ok
                    else R.string.settings_auto_restore_dev_options_no_permission,
                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
                // Xiaomi resets "USB debugging (security settings)" on reboot and it cannot be
                // written from shell; when it is still off after restore, guide the user to the
                // developer options page where the toggle lives.
                if (ok && !DeveloperOptionsRestorer.isAdbSecuritySettingEnabled()) {
                    Toast.makeText(
                        requireContext(),
                        R.string.settings_restore_adb_security_hint,
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
            }
            true
        }

        // Anti-kill guide: explains how to stop the OEM task killers from freezing ShizukuX, and
        // offers a direct hop to the battery optimization exemption page. The watchdog (toggle
        // above) is the automatic-restart side of the same problem; this guide keeps the watchdog
        // process itself alive so its CRASHED-triggered restart can actually fire.
        findPreference<Preference>("anti_kill_guide")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.anti_kill_dialog_title)
                .setMessage(R.string.anti_kill_dialog_message)
                .setPositiveButton(R.string.anti_kill_dialog_open_battery) { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(
                            requireContext(),
                            R.string.settings_anti_kill_guide_summary,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    private fun syncTcpPortVisibility() {
        tcpPortPreference.isVisible = tcpModePreference.isVisible && tcpModePreference.isChecked
    }

    override fun onResume() {
        super.onResume()
        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onPause() {
        ShizukuStateMachine.removeListener(stateListener)
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key == KEY_WATCHDOG) watchdogPreference.isChecked = ShizukuSettings.isWatchdogRunning()
    }
}
