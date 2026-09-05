package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.RootCompatHelper
import af.shizuku.manager.service.WatchdogService

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handled = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> true
            else -> false
        }
        if (!handled) return

        Timber.tag("BootCompleteReceiver").i("Triggered by: $action")
        try {
            ShizukuReceiverStarter.start(context)
        } catch (e: Exception) {
            // LOCKED_BOOT_COMPLETED fires during direct boot, before credential-encrypted storage
            // is available — WorkManager can't initialize and prefs may be inaccessible. This is
            // expected; the later BOOT_COMPLETED (post-unlock) handles auto-start. Catch broadly so
            // no variant crashes the receiver, and log at warn (breadcrumb, not a billed Sentry event).
            Timber.tag("BootCompleteReceiver").w(e, "Auto-start skipped (service not ready, e.g. direct boot)")
        }
        try {
            if (ShizukuSettings.getWatchdog()) {
                WatchdogService.start(context)
                // Exact alarms don't survive a reboot; re-arm the external watchdog (#415, #417).
                WatchdogAlarmReceiver.schedule(context)
            }
        } catch (e: Exception) {
            // startForegroundService can be refused during direct boot / from background — expected.
            Timber.tag("BootCompleteReceiver").w(e, "Watchdog start skipped")
        }
        // Restart AutomationService if any automation rules were configured before the reboot.
        // The service self-stops when no rules are configured, so this is safe to call unconditionally
        // as long as rules exist.
        if (ShizukuSettings.hasAnyAutomationRulesConfigured()) {
            try {
                context.startForegroundService(
                    android.content.Intent(context, af.shizuku.manager.automation.AutomationService::class.java)
                )
            } catch (e: Exception) {
                Timber.tag("BootCompleteReceiver").w(e, "AutomationService start skipped")
            }
        }
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED && ShizukuSettings.isSuBridgeEnabled()) {
            // The su/rish_shizuku.dex bridge deployed to /data/local/tmp is version-specific
            // bytecode compiled against this exact APK build; previously it was only ever
            // refreshed when the user manually reopened Root Compatibility settings, so an app
            // update left every existing SU Bridge user running a stale dex against the new
            // server until they happened to revisit that screen (#423 - "script" callers started
            // getting a null newProcess() result after updating). Re-deploy on every self-update
            // for users who already opted into the bridge; deployBridgeToTmp() no-ops safely if
            // Shizuku isn't up yet (best-effort, same as the starter/watchdog calls above).
            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    RootCompatHelper.deployBridgeToTmp(appContext)
                } catch (e: Exception) {
                    Timber.tag("BootCompleteReceiver").w(e, "SU bridge redeploy skipped")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
