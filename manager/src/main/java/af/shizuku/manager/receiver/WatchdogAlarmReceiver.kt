package af.shizuku.manager.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.WatchdogWorker
import timber.log.Timber

/**
 * External, process-independent re-arm for the Watchdog (#415, #417).
 *
 * Samsung's "Sleeping apps" freezer can kill the whole manager process on screen lock,
 * taking WatchdogService (and WatchdogWorker's in-process checks) down with it — a frozen
 * or dead process can't detect its own death. AlarmManager callbacks are dispatched by
 * system_server, which will cold-start a fresh process to deliver this broadcast even if
 * the old one was frozen/killed, so this survives what WatchdogWorker's 2-hour WorkManager
 * backstop and WatchdogService's in-process state-flow listener both cannot.
 *
 * Exact alarms don't repeat on their own (setRepeating() isn't guaranteed exact and drifts
 * under Doze), so this self-reschedules on every fire — same idiom as one-shot exact alarms
 * used for periodic background work elsewhere on Android.
 */
class WatchdogAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (!ShizukuSettings.getWatchdog()) {
            Timber.tag(TAG).d("Watchdog disabled in settings, not rescheduling alarm")
            return
        }

        if (!WatchdogService.isRunning()) {
            Timber.tag(TAG).w("WatchdogService found dead by alarm re-arm; restarting")
            // Android 15+ (API 35 / VANILLA_ICE_CREAM) blocks startForegroundService() from a
            // BroadcastReceiver while the device is locked. Route through an expedited
            // WorkManager one-shot on those versions so WatchdogService.start() is allowed.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                WatchdogWorker.scheduleOneTimeHeal(context.applicationContext)
            } else {
                WatchdogService.start(context)
            }
        }

        // v24 (power) note: the 15-min RTC_WAKEUP cadence and Doze-compatible exact/inexact
        // fallback were already in place (INTERVAL_MS / setExactAndAllowWhileIdle /
        // setAndAllowWhileIdle). A short-circuit was trialled (skip update() while the watchdog
        // service is alive and the screen is interactive) and REVERTED after on-device testing:
        // update() is the alarm's last-resort freshness check when the binder-death callback is
        // delayed or missed, and at one call per 15 min its cost is negligible — the short-circuit
        // saved ~nothing while adding a real state-staleness risk.
        ShizukuStateMachine.update()

        schedule(context.applicationContext)
    }

    companion object {
        private const val TAG = "WatchdogAlarmReceiver"
        private const val REQUEST_CODE = 9001
        private val INTERVAL_MS = 15 * 60 * 1000L // 15 min: tight enough for a meaningfully
        // faster recovery than the 2h WorkManager backstop, loose enough not to be a battery
        // complaint on its own (matches the tightest interval WorkManager itself allows).

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogAlarmReceiver::class.java)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        @JvmStatic
        fun schedule(context: Context) {
            if (!ShizukuSettings.getWatchdog()) return
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return
                val triggerAt = System.currentTimeMillis() + INTERVAL_MS
                val pi = pendingIntent(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                    // No exact-alarm permission (user declined, or OEM policy) — fall back to an
                    // inexact wake; still survives a frozen process, just batched by the OS.
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to schedule watchdog re-arm alarm")
            }
        }

        @JvmStatic
        fun cancel(context: Context) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                    ?: return
                alarmManager.cancel(pendingIntent(context))
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to cancel watchdog re-arm alarm")
            }
        }
    }
}
