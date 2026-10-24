package af.shizuku.manager.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings.Keys.*
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.DiagnosticsExporter
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
import af.shizuku.manager.backup.BackupRestoreManager
import af.shizuku.manager.backup.BackupKeyUnavailableException
import af.shizuku.manager.backup.CryptoUtils
import af.shizuku.manager.security.BiometricLock
import androidx.biometric.BiometricPrompt
import android.security.keystore.KeyPermanentlyInvalidatedException
import javax.crypto.AEADBadTagException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import androidx.preference.TwoStatePreference
import androidx.activity.result.contract.ActivityResultContracts

class AdvancedSettingsFragment : BaseSettingsFragment() {

    override fun getTitle(): CharSequence? = getString(R.string.settings_advanced_diagnostics_title)

    // e.message is often null for keystore/cipher exceptions (#315's "Backup failed: null"), and
    // KeyPermanentlyInvalidatedException needs a message explaining it's unrecoverable rather
    // than a raw exception string (#332) - encryption self-heals from this in CryptoUtils, but
    // decryption of an existing backup genuinely can't.
    private fun backupErrorMessage(ctx: Context, prefixRes: Int, isRestore: Boolean, e: Exception): String = when (e) {
        is KeyPermanentlyInvalidatedException ->
            ctx.getString(
                if (isRestore) {
                    R.string.settings_backup_key_invalidated_restore
                } else {
                    R.string.settings_backup_key_invalidated_backup
                },
                ctx.getString(prefixRes)
            )
        // The key was destroyed (uninstall/reinstall or cleared data) — explain, don't show a raw error (#370).
        is BackupKeyUnavailableException ->
            ctx.getString(R.string.settings_backup_key_unavailable, ctx.getString(prefixRes))
        // A valid key exists but can't authenticate this ciphertext: the backup was made by a
        // different install, is corrupt, or was tampered with. GCM's tag check is exactly what
        // catches that — surface it as a clear cause instead of "AEADBadTagException" (#370).
        is AEADBadTagException ->
            ctx.getString(R.string.settings_backup_decryption_failed_install, ctx.getString(prefixRes))
        else -> ctx.getString(R.string.settings_error_with_detail, ctx.getString(prefixRes), e.message ?: e.javaClass.simpleName)
    }

    private val createPlainBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        try {
            val payload = BackupRestoreManager.createPlainBackupPayload(ctx)
            ctx.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
            }
            Toast.makeText(ctx, R.string.settings_plain_backup_exported, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.settings_backup_operation_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val lock = BiometricLock(requireActivity())
        val useAuth = lock.canAuthenticate(ctx)

        if (!useAuth) {
            try {
                val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = false)
                val payload = BackupRestoreManager.createBackupPayload(ctx, cipher)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                }
                Toast.makeText(ctx, R.string.settings_backup_exported, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, backupErrorMessage(ctx, R.string.settings_backup_failed_prefix, false, e), Toast.LENGTH_LONG).show()
            }
            return@registerForActivityResult
        }

        try {
            val cipher = CryptoUtils.getCipherForEncryption(userAuthRequired = true)
            lock.authenticate(onSuccess = { crypto ->
                try {
                    val payload = BackupRestoreManager.createBackupPayload(ctx, crypto?.cipher ?: cipher)
                    ctx.contentResolver.openOutputStream(uri)?.use { os ->
                        OutputStreamWriter(os, Charsets.UTF_8).use { it.write(payload) }
                    }
                Toast.makeText(ctx, R.string.settings_backup_exported, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, backupErrorMessage(ctx, R.string.settings_backup_failed_prefix, false, e), Toast.LENGTH_LONG).show()
                }
            }, onError = { errCode ->
                Toast.makeText(ctx, ctx.getString(R.string.settings_authentication_failed, errCode), Toast.LENGTH_SHORT).show()
            }, crypto = BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.settings_backup_operation_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val restoreBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        val lock = BiometricLock(requireActivity())
        val useAuth = lock.canAuthenticate(ctx)

        try {
            val payload = ctx.contentResolver.openInputStream(uri)?.use { `is` ->
                InputStreamReader(`is`, Charsets.UTF_8).readText()
            } ?: return@registerForActivityResult

            // Auto-detect format: plain (v2) backups skip encryption entirely.
            if (!BackupRestoreManager.isEncrypted(payload)) {
                try {
                    BackupRestoreManager.restoreFromPlainPayload(ctx, payload)
                    Toast.makeText(ctx, R.string.settings_backup_restored_restart, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, R.string.settings_restore_operation_failed, Toast.LENGTH_LONG).show()
                }
                return@registerForActivityResult
            }

            val iv = BackupRestoreManager.extractIv(payload)

            if (!useAuth) {
                try {
                    val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = false)
                    BackupRestoreManager.restoreFromPayload(ctx, payload, cipher)
                    Toast.makeText(ctx, R.string.settings_backup_restored_restart, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, backupErrorMessage(ctx, R.string.settings_restore_failed_prefix, true, e), Toast.LENGTH_LONG).show()
                }
                return@registerForActivityResult
            }

            val cipher = CryptoUtils.getCipherForDecryption(iv, userAuthRequired = true)
            lock.authenticate(onSuccess = { crypto ->
                try {
                    BackupRestoreManager.restoreFromPayload(ctx, payload, crypto?.cipher ?: cipher)
                    Toast.makeText(ctx, R.string.settings_backup_restored_restart, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(ctx, backupErrorMessage(ctx, R.string.settings_restore_failed_prefix, true, e), Toast.LENGTH_LONG).show()
                }
            }, onError = { errCode ->
                Toast.makeText(ctx, ctx.getString(R.string.settings_authentication_failed, errCode), Toast.LENGTH_SHORT).show()
            }, crypto = BiometricPrompt.CryptoObject(cipher))
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.settings_restore_operation_failed, Toast.LENGTH_LONG).show()
        }
    }

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

        // One-tap diagnostics export: collect device/server/shell state on IO, then show a
        // dialog to copy or share (e.g. to QQ) — no adb/logcat permission required.
        findPreference<Preference>("export_diagnostics")?.setOnPreferenceClickListener {
            // Capture the context up front; requireContext() inside the IO coroutine would throw
            // if the fragment got detached while collecting.
            val ctx = context ?: return@setOnPreferenceClickListener true
            Toast.makeText(ctx, R.string.export_diagnostics_collecting, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val report = withContext(Dispatchers.IO) {
                    try {
                        DiagnosticsExporter.collect(ctx)
                    } catch (e: Exception) {
                        null
                    }
                }
                if (!isAdded) return@launch
                if (report != null) {
                    showDiagnosticsDialog(report)
                } else {
                    Toast.makeText(ctx, R.string.export_diagnostics_failed, Toast.LENGTH_SHORT).show()
                }
            }
            true
        }

        findPreference<TwoStatePreference>(KEY_ENABLE_ACTIVITY_LOG)?.setOnPreferenceChangeListener { _, _ ->
            ShizukuSettings.syncAllExtraFeaturesToServer()
            true
        }

        val backupSettingsPref = findPreference<Preference>("backup_settings")
        backupSettingsPref?.setOnPreferenceClickListener {
            val dateStr = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_export_backup_title)
                .setItems(arrayOf(
                    getString(R.string.settings_encrypted_backup_option),
                    getString(R.string.settings_plain_backup_option)
                )) { _, which ->
                    try {
                        when (which) {
                            0 -> createBackupLauncher.launch("ShizukuX_Settings_$dateStr.json")
                            1 -> createPlainBackupLauncher.launch("ShizukuX_Settings_plain_$dateStr.json")
                        }
                    } catch (e: android.content.ActivityNotFoundException) {
                        Toast.makeText(requireContext(), R.string.settings_no_file_manager_save, Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            true
        }

        val restoreSettingsPref = findPreference<Preference>("restore_settings")
        restoreSettingsPref?.setOnPreferenceClickListener {
            try {
                restoreBackupLauncher.launch(arrayOf("application/json", "*/*"))
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(requireContext(), R.string.settings_no_file_manager_open, Toast.LENGTH_LONG).show()
            }
            true
        }

        findPreference<Preference>("scripting")?.setOnPreferenceClickListener {
            startActivity(Intent(context, af.shizuku.manager.scripting.ScriptingActivity::class.java))
            true
        }

        findPreference<TwoStatePreference>(KEY_LEGACY_PAIRING)?.apply {
            isVisible = !EnvironmentUtils.isTelevision()
        }        // Auto pairing: mirrors the pairing-assistant accessibility service. Turning it on
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
                        ShizukuSettings.syncAllExtraFeaturesToServer()
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
                        ShizukuSettings.syncAllExtraFeaturesToServer()
                    true
                }
            }
        }
    }

    private fun showDiagnosticsDialog(report: String) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.export_diagnostics_title)
            .setMessage(report)
            .setPositiveButton(R.string.export_diagnostics_share) { _, _ ->
                // Long shell diagnostics go out as a file (FileProvider) so messaging apps
                // (QQ/WeChat) don't truncate the report; short reports share as plain text.
                try {
                    val file = java.io.File(ctx.cacheDir, "shizukux_diagnostics_${System.currentTimeMillis()}.txt")
                    file.writeText(report)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        ctx,
                        "${ctx.packageName}.fileprovider",
                        file
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, report.take(500))
                        clipData = ClipData.newUri(ctx.contentResolver, "ShizukuX Diagnostics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, ctx.getString(R.string.export_diagnostics_share)))
                } catch (e: Exception) {
                    // Fall back to plain-text share if the file provider path fails.
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    startActivity(Intent.createChooser(send, ctx.getString(R.string.export_diagnostics_share)))
                }
            }
            .setNeutralButton(R.string.export_diagnostics_copy) { _, _ ->
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ShizukuX Diagnostics", report))
                Toast.makeText(ctx, R.string.export_diagnostics_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
