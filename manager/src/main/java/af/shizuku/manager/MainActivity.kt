package af.shizuku.manager

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import timber.log.Timber
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.sentry.Breadcrumb
import io.sentry.Sentry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.home.ChangelogDialogFragment
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.migration.MigrationHelper
import af.shizuku.manager.onboarding.OnboardingActivity
import af.shizuku.manager.utils.ShizukuStateMachine

class MainActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Auto-import settings if an auto-update via Shizuku recently occurred
        val backupFile = af.shizuku.manager.update.UpdateInstaller.getBackupFile(this)
        if (backupFile.exists()) {
            try {
                val json = backupFile.readText()
                if (af.shizuku.manager.utils.SettingsBackupManager.import(this, json)) {
                    Toast.makeText(this, "Settings automatically restored from previous version.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to auto-import settings from backup file")
            } finally {
                backupFile.delete()
            }
        }

        // Check for previous crashes as early as possible to break crash loops
        if (af.shizuku.manager.utils.CrashHandler.getLastCrashReport(this) != null) {
            showCrashReportDialog()
        }

        try {
            Timber.d("Calling super.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("Calling super.onCreate"))
            super.onCreate(savedInstanceState)

            Timber.d("Checking onboarding status")
            Sentry.addBreadcrumb(Breadcrumb("Checking onboarding status"))

            // Auto-restore settings if a force-update backup exists
            checkAndRestoreBackup()

            // Show "What's New" dialog on first launch after an update
            val currentVersion = try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (e: Exception) {
                0
            }
            
            val lastSeenVersion = ShizukuSettings.getLastSeenVersion()
            if (lastSeenVersion != -1 && lastSeenVersion < currentVersion) {
                try {
                    ChangelogDialogFragment().show(supportFragmentManager, ChangelogDialogFragment.TAG)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to show changelog dialog")
                }
            }
            if (lastSeenVersion < currentVersion) {
                ShizukuSettings.setLastSeenVersion(currentVersion)
            }

            Timber.d("MainActivity onCreate complete")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity onCreate complete"))
        } catch (e: Exception) {
            Timber.e(e, "Crash in MainActivity.onCreate")
            Sentry.addBreadcrumb(Breadcrumb("MainActivity crash: ${e.message}"))
            Sentry.captureException(e)
            throw e
        }
    }

    override fun onStart() {
        try {
            super.onStart()
            // Update state machine on app start
            ShizukuStateMachine.update()
        } catch (e: Exception) {
            Timber.e(e, "Error in onStart")
            Sentry.captureException(e)
            throw e
        }
    }

    private fun checkAndRestoreBackup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val backupFile = af.shizuku.manager.update.UpdateInstaller.getBackupFile(this@MainActivity)
            if (backupFile.exists()) {
                try {
                    val json = backupFile.readText()
                    if (af.shizuku.manager.utils.SettingsBackupManager.import(this@MainActivity, json)) {
                        Timber.i("Successfully auto-restored settings from force-update backup")
                        backupFile.delete()
                        // Notify user or refresh UI if needed
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, R.string.migration_success_message, Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to auto-restore settings")
                }
            }
        }
    }

    private fun showMigrationDialog() {
        lifecycleScope.launch {
            val hasRoot = withContext(Dispatchers.IO) { MigrationHelper.isRootAvailable() }
            if (isFinishing || isDestroyed) return@launch
            try {
                val builder = if (hasRoot) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.migration_dialog_title)
                        .setMessage(R.string.migration_dialog_message_root)
                        .setPositiveButton(R.string.migration_migrate_settings) { _, _ -> performMigration() }
                        .setNeutralButton(R.string.migration_uninstall_old) { _, _ -> launchUninstall(MigrationHelper.OLD_PACKAGE) }
                        .setNegativeButton(R.string.migration_dismiss, null)
                } else {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.migration_dialog_title)
                        .setMessage(R.string.migration_no_root_message)
                        .setPositiveButton(R.string.migration_uninstall_old) { _, _ -> launchUninstall(MigrationHelper.OLD_PACKAGE) }
                        .setNegativeButton(R.string.migration_dismiss, null)
                }
                builder.show()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show migration dialog")
            }
        }
    }

    private fun showCrashReportDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_report_title)
            .setMessage(R.string.crash_detected_dialog_message)
            .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                val report = af.shizuku.manager.utils.CrashReporter.generateReport(this)
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.manual_report_clipboard_label), report))
                
                Toast.makeText(this, R.string.manual_report_toast_copied, Toast.LENGTH_LONG).show()
                
                af.shizuku.manager.utils.CustomTabsHelper.launchUrlOrCopy(this, "https://github.com/thejaustin/ShizukuPlus/issues/new")
                af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
            }
            .setNeutralButton(R.string.manual_report_copied_dialog_share) { _, _ ->
                af.shizuku.manager.utils.CrashReporter.shareAsFile(this)
                af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
            }
            .setNegativeButton(R.string.crash_detected_dialog_ignore) { _, _ ->
                af.shizuku.manager.utils.CrashHandler.clearLastCrash(this)
            }
            .show()
    }

    private fun performMigration() {
        lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                MigrationHelper.migrateSettings(this@MainActivity)
            }

            if (isFinishing || isDestroyed) return@launch

            val (title, message) = if (success) {
                Pair(R.string.migration_success_title, R.string.migration_success_message)
            } else {
                Pair(R.string.migration_failure_title, R.string.migration_failure_message)
            }

            try {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton(R.string.migration_uninstall_old) { _, _ ->
                        launchUninstall(MigrationHelper.OLD_PACKAGE)
                    }
                    .setNegativeButton(R.string.migration_dismiss, null)
                    .show()
            } catch (e: Exception) {
                Timber.e(e, "Failed to show migration result dialog")
            }
        }
    }

    private fun launchUninstall(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch uninstall for $packageName")
        }
    }
}
