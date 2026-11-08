package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.update.UpdateManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The launcher-facing home screen. It draws nothing itself any more: the five destinations live in
 * the pager inherited from [GlassShellActivity], so home is page 0 of that container rather than a
 * second, standalone page that would have to launch activities to change tabs.
 *
 * What stays here is what only the entry point does: shortcut intents, the wireless-ADB cold-start
 * hand-off and the update check.
 */
open class HomeActivity : GlassShellActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.getStringExtra("shortcut_action")) {
            "start_wireless_adb" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startActivity(Intent(this, af.shizuku.manager.starter.StarterActivity::class.java))
            }
            "open_terminal" -> startActivity(
                Intent(this, af.shizuku.manager.shell.ShellTutorialActivity::class.java)
            )
        }

        // Cold-start launch from the ADB pairing success notification. onNewIntent() is only called
        // when the activity already exists; when the app is not running the system calls onCreate()
        // with the launch intent instead.
        if (intent?.getBooleanExtra(EXTRA_START_SERVICE_VIA_WADB, false) == true) {
            homeCtl.handleStartViaMdnsNotification()
        }

        checkForUpdates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_PAIRING_DIALOG, false)) {
            showAccessibilityDialog()
        }
        if (intent.getBooleanExtra(EXTRA_START_SERVICE_VIA_WADB, false)) {
            homeCtl.handleStartViaMdnsNotification()
        }
    }

    /**
     * Check for updates on app startup and show popup dialog
     */
    private fun checkForUpdates() {
        if (!ShizukuSettings.isAutoUpdateEnabled()) return

        val lastCheckTime = ShizukuSettings.getLastUpdateCheckTime()
        val now = System.currentTimeMillis()
        val oneDayInMillis = 24 * 60 * 60 * 1000L
        if (now - lastCheckTime < oneDayInMillis) return
        if (isFinishing || isDestroyed) return

        lifecycleScope.launch {
            try {
                val result = UpdateChecker.checkForUpdate(ShizukuSettings.getUpdateChannel())

                when (result) {
                    is UpdateChecker.CheckResult.UpdateAvailable -> {
                        ShizukuSettings.setLastUpdateCheckTime(now)
                        ShizukuSettings.setLastUpdateCheckFailed(false)
                        if (!isFinishing && !isDestroyed) showUpdateAvailableDialog(result.info)
                    }
                    is UpdateChecker.CheckResult.UpToDate -> {
                        ShizukuSettings.setLastUpdateCheckTime(now)
                        ShizukuSettings.setLastUpdateCheckFailed(false)
                    }
                    is UpdateChecker.CheckResult.NetworkError -> {
                        ShizukuSettings.setLastUpdateCheckFailed(true)
                    }
                }
            } catch (e: Exception) {
                Timber.tag("HomeActivity").e(e, "Unexpected error checking for update")
                ShizukuSettings.setLastUpdateCheckFailed(true)
            }
        }
    }

    /**
     * Show update available popup dialog
     */
    private fun showUpdateAvailableDialog(updateInfo: UpdateChecker.UpdateInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_available, null)
        dialogView.findViewById<TextView>(R.id.update_version_name).text =
            getString(R.string.update_version_name, updateInfo.versionName)
        dialogView.findViewById<TextView>(R.id.update_published_date).text =
            if (updateInfo.publishedAt.isNotEmpty()) {
                getString(R.string.update_published_date, UpdateChecker.formatPublishedDate(updateInfo.publishedAt))
            } else {
                ""
            }
        // updateInfo.releaseNotes is the raw GitHub release body (Markdown) — render it instead of
        // dumping it as plain text, which showed literal "**", "###", table syntax and bracketed
        // links. Drop the "Recent Releases" rollup the same way ChangelogDialogFragment does.
        val notesBody = updateInfo.releaseNotes
            .substringBefore("## 📦 Recent Releases")
            .trim()
            .ifEmpty { getString(R.string.update_no_release_notes) }
        val releaseNotesView = dialogView.findViewById<TextView>(R.id.update_release_notes)
        Markwon.create(this).setMarkdown(releaseNotesView, notesBody)
        releaseNotesView?.movementMethod = LinkMovementMethod.getInstance()

        val openReleases = {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(af.shizuku.manager.utils.ProjectLinks.RELEASES))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_available_title)
            .setView(dialogView)
            .setNegativeButton(R.string.update_later, null)
            .setNeutralButton(R.string.update_release_notes) { _, _ -> openReleases() }

        if (updateInfo.requiresManualDownload) {
            builder.setPositiveButton(R.string.update_view_on_github) { _, _ -> openReleases() }
        } else {
            builder.setPositiveButton(R.string.update_download) { _, _ ->
                UpdateManager(this).downloadUpdate(updateInfo.downloadUrls, updateInfo.versionName)
            }
        }

        builder.show()
    }

    companion object {
        const val EXTRA_SHOW_PAIRING_DIALOG = "show_pairing_dialog"
        const val EXTRA_START_SERVICE_VIA_WADB = "start_service_via_wadb"
    }
}
