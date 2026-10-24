package af.shizuku.manager.worker

import android.content.Context
import androidx.work.*
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ShizukuStateMachine
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * A persistent worker that ensures the ShizukuX Watchdog remains active.
 *
 * Samsung's OneUI is aggressive at killing background services.
 * This worker acts as a "Self-Healing" mechanism by checking the
 * state every 1-2 hours and restarting the Watchdog if necessary.
 */
class WatchdogWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "shizuku_watchdog_healer"
        private const val HEAL_WORK_NAME = "shizuku_watchdog_one_time_heal"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .build()

            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(2, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Enqueues a one-shot expedited heal used by WatchdogAlarmReceiver on Android 15+
         * (API 35 / VANILLA_ICE_CREAM), where startForegroundService() from a BroadcastReceiver
         * is blocked while the device is locked. WorkManager's expedited promotion gives the
         * app a temporary foreground window during which WatchdogService.start() is allowed.
         */
        fun scheduleOneTimeHeal(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchdogWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                HEAL_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!ShizukuSettings.getWatchdog()) {
            Timber.tag("WatchdogWorker").d("Watchdog is disabled in settings, skipping healing.")
            return Result.success()
        }

        if (!WatchdogService.isRunning()) {
            Timber.tag("WatchdogWorker").w("WatchdogService was DEAD! Restarting...")
            WatchdogService.start(applicationContext)
        } else {
            Timber.tag("WatchdogWorker").d("WatchdogService is healthy.")
        }

        // Also trigger a state update to be sure
        ShizukuStateMachine.update()

        return Result.success()
    }
}
