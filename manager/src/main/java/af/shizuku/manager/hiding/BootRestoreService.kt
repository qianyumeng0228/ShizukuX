package af.shizuku.manager.hiding

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import af.shizuku.manager.R
import af.shizuku.manager.apps.PrivilegedShell
import af.shizuku.manager.devicecontrol.AutoRotatePreference
import af.shizuku.manager.utils.Logger.LOGGER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The part of the boot restore that waits, kept out of the broadcast.
 *
 * `BootRestoreReceiver` used to hold a `goAsync` receiver open for up to ninety seconds while
 * waiting for the shell. Measured on 2210132C / HyperOS: the system posts
 * `ANR in moe.shizuku.privileged.api -- Broadcast ... BOOT_COMPLETED ... BootRestoreReceiver`
 * (background ANR, `anrType=broadcast`) about sixty seconds after the broadcast is dispatched, so
 * the restore was running inside a window the platform had already given up on. Nothing here needs
 * to finish within a broadcast's lifetime, so the waiting moved into a foreground service and the
 * receiver now returns in milliseconds.
 *
 * The service waits up to three minutes. The boot start of the API server and the first unlock
 * both land inside that on the devices measured so far; the old ninety seconds came from
 * thirty attempts of three seconds, which is short enough that a slow unlock loses the restore
 * entirely.
 *
 * Release builds print no Timber, so the lines below use `android.util.Log` with a tag a tester
 * can filter on (`ShizukuX/BootRestore`). That is the only way to tell "the restore did nothing"
 * apart from "the restore never ran" from a phone.
 */
class BootRestoreService : Service() {

    companion object {
        private const val TAG = "ShizukuX/BootRestore"
        private const val CHANNEL_ID = "boot_restore"
        private const val NOTIFICATION_ID = 1453
        private const val INTERVAL_MS = 3000L

        /** Three minutes: sixty attempts of three seconds. */
        private const val MAX_ATTEMPTS = 60

        /** How long to wait before checking whether a switch write survived. */
        private const val SETTLE_MS = 5000L

        /**
         * How many times the write is repeated.
         *
         * Measured on 2210132C: a write six seconds into boot is accepted, reported as done, and
         * then rewritten back to 1 while the rest of the system finishes starting — the device
         * still reported 1 two minutes later. A single write therefore proves nothing; the value
         * has to be read back after the system settles, and a late write repeated until it holds.
         */
        private const val SETTLE_ATTEMPTS = 6

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BootRestoreService::class.java)
                )
            }.onFailure { LOGGER.w("could not start the boot restore", it) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        channel()

        // Posted before anything waits, because a foreground service that has not posted its
        // notification yet is one the platform will kill.
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hiding_24)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Restoring per-app rules")
            .setOngoing(true)
            .setShowWhen(false)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        scope.launch { restore() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun restore() {
        try {
            var attempts = 0
            while (attempts < MAX_ATTEMPTS) {
                if (PrivilegedShell.available()) break
                attempts++
                delay(INTERVAL_MS)
            }

            val waited = attempts * (INTERVAL_MS / 1000)
            if (!PrivilegedShell.available()) {
                Log.w(TAG, "the shell never came up within ${waited}s, nothing restored")
                return
            }

            // The platform comes back with auto-rotate on whatever the user had it at, so the
            // switch has to be put back before the watch starts sampling it — otherwise the first
            // idle pass records the platform's default as the user's choice.
            //
            // A write alone is not an answer: the value is read back after it, and a write the
            // system did not keep is repeated. "restored=true" on a phone that still reports 1
            // is exactly the state this loop exists to prevent.
            val wanted = AutoRotatePreference.value()?.let { if (it) "1" else "0" }
            var acc = readAcc()
            var wrote = false
            var settleTries = 0
            if (wanted != null) {
                while (settleTries < SETTLE_ATTEMPTS && acc != wanted) {
                    wrote = runCatching {
                        AutoRotatePreference.restore(
                            available = { PrivilegedShell.available() },
                            run = { cmd -> PrivilegedShell.run(cmd) }
                        )
                    }.getOrDefault(false) || wrote
                    settleTries++
                    delay(SETTLE_MS)
                    acc = readAcc()
                    Log.w(TAG, "attempt $settleTries: wrote=$wrote acc=$acc wanted=$wanted")
                }
                if (acc != wanted) {
                    Log.w(TAG, "the auto-rotate switch did not keep the value after ${settleTries * (SETTLE_MS / 1000)}s")
                }
            }

            Log.w(
                TAG,
                "shell up after ${waited}s, auto-rotate wanted=$wanted acc=$acc wrote=$wrote attempts=$settleTries"
            )

            HidingWatchService.refresh(this)
            Log.w(TAG, "watch refresh requested, running=${HidingWatchService.isRunning()}")
        } catch (t: Throwable) {
            Log.w(TAG, "boot restore failed", t)
        } finally {
            stopSelf()
        }
    }

    private fun readAcc(): String? = PrivilegedShell.run("settings get system accelerometer_rotation")
        ?.lineSequence()?.firstOrNull()?.trim()

    private fun channel() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(NotificationManager::class.java) ?: return
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Boot restore",
                    NotificationManager.IMPORTANCE_MIN
                ).apply { setShowBadge(false) }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
