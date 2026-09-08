package af.shizuku.manager.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import af.shizuku.manager.R
import af.shizuku.manager.receiver.DeveloperOptionsRestorer
import java.util.concurrent.TimeUnit

/**
 * Delayed second pass of the boot-time developer-options restore. The first pass runs inline in
 * [af.shizuku.manager.receiver.BootCompleteReceiver] (writing the keys is fast enough to survive
 * the receiver's goAsync() window), but the 15s-delayed retry used to live in a coroutine there
 * and got cancelled when the system reclaimed the process during the wait. WorkManager survives
 * process death, so this worker owns the retry plus the "wireless debugging needs Wi-Fi"
 * notification when the toggle still didn't stick.
 */
class DeveloperRestoreWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val ok = withContext(Dispatchers.IO) {
            DeveloperOptionsRestorer.restore(context)
        }
        // restore() writes adb_wifi_enabled=1 directly; when Wi-Fi is off the system re-writes it
        // back to 0 shortly after. Reading immediately would see our own just-written 1 and miss
        // the failure, so wait for the value to settle before deciding whether to notify.
        delay(8_000)
        if (!DeveloperOptionsRestorer.isWirelessDebuggingEnabled(context)) {
            Timber.tag("DeveloperRestoreWorker").i("Wireless debugging did not stick after restore; notifying user")
            showWirelessNeedsWifiNotification(context)
        }
        return if (ok) Result.success() else Result.retry()
    }

    /**
     * One-shot notification telling the user the boot-time auto-restore could not re-enable
     * wireless debugging because Wi-Fi was off. Tapping the action jumps to the Wi-Fi settings;
     * once Wi-Fi is on, AdbStartWorker's AWAITING_WIFI path takes over and starts Shizuku.
     */
    private fun showWirelessNeedsWifiNotification(context: Context) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.settings_restore_wifi_notification_title),
                        NotificationManager.IMPORTANCE_DEFAULT
                    )
                )
            }

            val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            val wifiPendingIntent = PendingIntent.getActivity(
                context, 1, wifiIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val openAppIntent = PendingIntent.getActivity(
                context, 2,
                Intent(context, af.shizuku.manager.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nb = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_settings_outline_24)
                .setContentTitle(context.getString(R.string.settings_restore_wifi_notification_title))
                .setContentText(context.getString(R.string.settings_restore_wifi_notification_text))
                .setContentIntent(openAppIntent)
                .addAction(
                    R.drawable.ic_settings_outline_24,
                    context.getString(R.string.settings_restore_wifi_notification_action),
                    wifiPendingIntent
                )
                .setAutoCancel(true)

            nm.notify(NOTIFICATION_ID, nb.build())
        }.onFailure {
            Timber.tag("DeveloperRestoreWorker").w(it, "Failed to show wireless-restore notification")
        }
    }

    companion object {
        const val CHANNEL_ID = "AutoRestore"
        // Distinct from AdbStartWorker's NOTIFICATION_ID (1448).
        const val NOTIFICATION_ID = 1455

        fun enqueue(context: Context) {
            // WorkManager uses credential-encrypted storage which is unavailable during direct boot.
            // Skip enqueueing until the user has unlocked their device.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            val request = OneTimeWorkRequestBuilder<DeveloperRestoreWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "developer_restore_worker",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
