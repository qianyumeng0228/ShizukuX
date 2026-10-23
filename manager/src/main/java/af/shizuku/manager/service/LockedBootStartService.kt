package af.shizuku.manager.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import af.shizuku.manager.adb.AdbArm
import af.shizuku.manager.adb.AdbPortProber
import af.shizuku.manager.adb.AdbStarter
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.receiver.ShizukuReceiverStarter.WorkerState
import af.shizuku.manager.starter.Starter
import timber.log.Timber

/**
 * Starts the server over wireless debugging before the first unlock.
 *
 * The ordinary start is WorkManager work, and WorkManager's database lives in credential-encrypted
 * storage that cannot be read until the user has unlocked for the first time - which, after a
 * restart, is when they get to type the PIN, half a minute or more after the system is ready for
 * everything this start needs. This service is direct-boot aware and runs the same wireless start
 * ([AdbStarter.startAdb]) without WorkManager, from LOCKED_BOOT_COMPLETED, so the server can be up
 * by the time the PIN is in.
 *
 * One attempt, no backoff: a failure ends it with the reason in the log, and the start made at the
 * unlock (the ordinary BOOT_COMPLETED → WorkManager path) is the retry. The service stops itself
 * as soon as the attempt has finished or failed.
 *
 * Ported from Shizuku-Next c5eeafa (LockedBootStartService), adapted to ShizukuX's execution model:
 * instead of the extracted AdbStartRun, it reuses the existing [AdbStarter.startAdb] entry point
 * after arming via [AdbArm] and probing for a live wireless-debugging port.
 *
 * What the platform allows is the part that cannot be checked here: a foreground service may be
 * started from LOCKED_BOOT_COMPLETED (the exemption from the background-start restrictions names
 * it), and whether the wireless debugging and the TLS handshake with adbd work before the unlock
 * is for a device to say.
 */
class LockedBootStartService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // First, whatever follows: the platform gives a service started with startForegroundService
        // a few seconds to call this, and one that does not is a crash.
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        synchronized(lock) {
            if (job?.isActive == true) {
                Timber.tag(TAG).i("Start before the unlock: one is already running")
                return START_NOT_STICKY
            }
            job = scope.launch { runStart() }
        }
        return START_NOT_STICKY
    }

    private suspend fun runStart() {
        try {
            val context = applicationContext

            // Arm wireless debugging through the AdbArm choke point (WRITE_SECURE_SETTINGS
            // or Device Owner) — everything this start needs is in device-protected storage
            // or system settings, none of which wait for the unlock.
            AdbArm.arm(context)

            // Fast probe for a live wireless-debugging port on loopback. No mDNS, no long
            // discovery: before the unlock a long search is more likely stuck on the lock
            // than about to find it, and the unlock begins an ordinary start in any case.
            val port = AdbPortProber.findActiveLoopbackPort()
            if (port <= 0) {
                Timber.tag(TAG).i("Start before the unlock: no live wireless-debugging port found; leaving it to the unlock")
                return
            }

            Timber.tag(TAG).i("Start before the unlock: connecting to port $port")
            ShizukuReceiverStarter.updateNotification(context, WorkerState.RUNNING)
            AdbStarter.startAdb(context, port)
            Starter.waitForBinder()
            Timber.tag(TAG).i("Start before the unlock: server is up")
        } catch (e: CancellationException) {
            Timber.tag(TAG).i("Start before the unlock was cancelled")
            throw e
        } catch (e: Throwable) {
            // Nothing for the user to act on, and no notification to raise about it: the reason
            // goes in the log, and the start the unlock makes is the retry.
            Timber.tag(TAG).w("The start before the unlock gave up", e)
        } finally {
            stopSelf()
        }
    }

    /** Puts the service in the foreground with the start's notification; false if it may not. */
    private fun enterForeground(): Boolean = try {
        val notification = ShizukuReceiverStarter.buildNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                ShizukuReceiverStarter.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ShizukuReceiverStarter.NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        // ForegroundServiceStartNotAllowedException and the like: an ordinary outcome for a service
        // the platform did not expect to be allowed, and worth knowing about on a device.
        Timber.tag(TAG).w("Start before the unlock could not enter the foreground", e)
        false
    }

    override fun onDestroy() {
        scope.cancel()
        synchronized(lock) { job = null }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LockedBootStartService"

        private val lock = Any()

        @Volatile
        private var job: Job? = null

        /** Whether a start is running in this service at the moment. */
        @JvmStatic
        fun isRunning(): Boolean = job?.isActive == true

        /**
         * Asks for the service and says whether it was asked for. Never throws; the caller
         * (a boot receiver) must not be taken down by a refused foreground start.
         */
        @JvmStatic
        fun start(context: Context): Boolean {
            return try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LockedBootStartService::class.java)
                )
                true
            } catch (e: Exception) {
                Timber.tag(TAG).w("Start before the unlock could not start its service", e)
                false
            }
        }

        /** Cancels the start running in the service (user stop / notification cancel). */
        @JvmStatic
        fun cancelRun(reason: String) {
            val running = synchronized(lock) { job?.takeIf { it.isActive } }
            if (running == null) return
            Timber.tag(TAG).i("Cancelling the start before the unlock: $reason")
            running.cancel()
        }
    }
}
