package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings
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
        } catch (e: IllegalStateException) {
            Timber.tag("BootCompleteReceiver").e(e, "WorkManager not ready, skipping auto-start")
        }
        if (ShizukuSettings.getWatchdog()) WatchdogService.start(context)
    }
}
