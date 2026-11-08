package af.shizuku.manager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import rikka.shizuku.Shizuku
import timber.log.Timber
import androidx.core.app.NotificationCompat
import af.shizuku.manager.R
import af.shizuku.manager.MainActivity
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.ProjectLinks
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.utils.StockShizukuCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

class WatchdogService : Service() {

    private var lastRestartMs = 0L
    private var consecutiveCrashes = 0
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var job: Job? = null
    private var pendingRecoveryJob: Job? = null
    private var stabilityJob: Job? = null
    private var degradedRetryJob: Job? = null
    private var probeJob: Job? = null
    private var intentionalStop = false

    // Returns false on failure so callers can bail out via stopSelf() instead of crashing
    // (RemoteServiceException$CannotPostForegroundServiceNotificationException, background-start
    // restrictions, etc. — same pattern as AutomationService.ensureForeground(), SHIZUKUPLUS-5P).
    private fun startForegroundSafely(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID_WATCHDOG,
                    buildNotification()
                )
            }
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "startForeground refused; stopping watchdog service")
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning.set(true)

        // Create notification channel before startForeground() to avoid
        // InvalidForegroundServiceTypeException. Wrapped in try-catch so that
        // a null/restricted NotificationManager never prevents startForeground()
        // from being called (which would cause ForegroundServiceDidNotStartInTimeException).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.createNotificationChannel(
                    NotificationChannel(WATCHDOG_CHANNEL_ID, "Watchdog", NotificationManager.IMPORTANCE_LOW)
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to create watchdog notification channel")
            }
        }

        // Must be called within 5s of startForegroundService(); do it before any
        // coroutine work so that a heavy initialisation path can never delay it.
        if (!startForegroundSafely()) {
            isRunning.set(false)
            stopSelf()
            return
        }

        // v29: the state machine only learns about a dead server from two places — a binder
        // death callback (armed only once *this* process has actually taken a binder reference,
        // so a cold start that merely pinged it never arms one) and an explicit update() call.
        // Without this poll a killed server left the state parked on RUNNING forever: the
        // crash listener never fired, the collector below never saw CRASHED, and
        // ShizukuReceiverStarter.start() was never reached — which is why the watchdog looked
        // dead on this device even though the ADB fallback inside start() works.
        probeJob = scope.launch {
            var startingSince = 0L
            while (isActive) {
                delay(PROBE_INTERVAL_MS)
                try {
                    withContext(Dispatchers.IO) {
                        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                        val state = ShizukuStateMachine.get()
                        if (alive) {
                            startingSince = 0L
                            // A server that answers again is RUNNING, whatever the state was.
                            // Without this a persisted STARTING survived both a healthy server
                            // and its death: update() keeps STARTING sticky, the collector only
                            // listens for CRASHED, and nothing ever restarted.
                            if (state != ShizukuStateMachine.State.RUNNING) {
                                runCatching { ShizukuStateMachine.set(ShizukuStateMachine.State.RUNNING) }
                            }
                        } else {
                            when (state) {
                                ShizukuStateMachine.State.RUNNING ->
                                    runCatching { ShizukuStateMachine.set(ShizukuStateMachine.State.CRASHED) }
                                // STARTING is sticky by design (a start is in flight), but not
                                // forever: once the grace window passes with no answer the start
                                // failed, and that is a crash the watchdog must recover from.
                                ShizukuStateMachine.State.STARTING -> {
                                    val now = System.currentTimeMillis()
                                    if (startingSince == 0L) {
                                        startingSince = now
                                    } else if (now - startingSince > STARTING_GRACE_MS) {
                                        runCatching { ShizukuStateMachine.set(ShizukuStateMachine.State.CRASHED) }
                                        startingSince = 0L
                                    }
                                }
                                else -> Unit
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    // One bad round must never kill the probe: this loop is the only thing that
                    // notices a dead server on a cold-started process, and a silent exit is
                    // exactly how the watchdog came to look dead in the first place.
                    runCatching {
                        ActivityLogManager.log(
                            "Shizuku", applicationContext.packageName,
                            "Watchdog: probe round failed: ${t.javaClass.simpleName}: ${t.message}"
                        )
                    }
                }
            }
        }

        job = scope.launch {
            ShizukuStateMachine.asFlow().collectLatest { state ->
                // Same rule as the probe loop: one failed recovery round must not end the
                // collector. The collector is what turns CRASHED into an actual restart, so a
                // silent exit here leaves the probe updating the state machine forever with
                // nobody acting on it — the crash then looks like "the watchdog never fires".
                try {
                if (state == ShizukuStateMachine.State.CRASHED) {
                    // The last thing the user asked for was a stop, and this CRASHED reading is
                    // the state machine replaying a stale RUNNING record (update() promotes
                    // "was RUNNING, binder gone" to CRASHED). Restarting here would undo the
                    // user's stop and, since the restart resolves LaunchMethod.ADB, replace the
                    // server with a new instance and an empty grant list. Correct the state
                    // instead of "healing" something that was never broken.
                    if (ShizukuSettings.isUserStoppedIntent()) {
                        ActivityLogManager.log(
                            "Shizuku", applicationContext.packageName,
                            "Watchdog: restart suppressed (the user stopped the service)"
                        )
                        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
                        return@collectLatest
                    }
                    // The server is no longer RUNNING, so the stability window ends here:
                    // left armed, it would fire while the server is dead and reset
                    // consecutiveCrashes, making the next crash look like a first restart.
                    stabilityJob?.cancel()
                    // A recovery job armed by an earlier first restart is stale too: the
                    // server just died again, so what that job waits for is a later
                    // restart's business now. Left armed, its timeout would post a
                    // duplicate heads-up for this same outage, or a later restart's
                    // server would be logged as "came back after restart #1".
                    pendingRecoveryJob?.cancel()
                    val now = System.currentTimeMillis()
                    val cooldown = backoffMs(consecutiveCrashes)
                    if (now - lastRestartMs > cooldown) {
                        val wasFirstRestart = (consecutiveCrashes == 0)
                        consecutiveCrashes++
                        lastRestartMs = now
                        if (consecutiveCrashes > MAX_AUTO_RESTARTS) {
                            // v25: degraded mode — long-cooldown probing instead of v24's
                            // permanent notify-only suspension. A stuck server can outlive the
                            // transient cause of its crash loop (an OEM freezer lift, a dropped
                            // su grant that the user re-allows, an adbd/wifi rearm), and a dead
                            // StateMachine never re-fires this flow, so a periodic probe is the
                            // only way to pick the environment back up without user action.
                            // This mirrors the classic watchdog idiom (KernelSU / SukiSU-Ultra /
                            // upstream Shizuku): never give up, just back off to a long cadence.
                            // Every DEGRADED_COOLDOWN_MS the probe runs ShizukuReceiverStarter.start()
                            // (which honours the launch mode + the ADB-precondition/root fallback in
                            // start()), the 60s crash-notification cooldown still gates alerts, and
                            // a successful RUNNING resets the counter via the stability window,
                            // cancelling this loop and restoring normal backoff restarts.
                            showCrashNotification()
                            ActivityLogManager.log(
                                "Shizuku", applicationContext.packageName,
                                "Watchdog: auto-restart degraded after $consecutiveCrashes crashes; probing every ${DEGRADED_COOLDOWN_MS / 60_000L}min"
                            )
                            if (degradedRetryJob == null) {
                                degradedRetryJob = scope.launch {
                                    while (isActive) {
                                        delay(DEGRADED_COOLDOWN_MS)
                                        showCrashNotification()
                                        ActivityLogManager.log(
                                            "Shizuku", applicationContext.packageName,
                                            "Watchdog: degraded environment probe"
                                        )
                                        withContext(Dispatchers.IO) {
                                            ShizukuReceiverStarter.start(applicationContext)
                                        }
                                    }
                                }
                            }
                            return@collectLatest
                        }
                        if (!wasFirstRestart) {
                            showCrashNotification()
                        }
                        ActivityLogManager.log("Shizuku", applicationContext.packageName, "Watchdog: restarting after crash #$consecutiveCrashes")
                        if (wasFirstRestart) {
                            pendingRecoveryJob?.cancel()
                            pendingRecoveryJob = scope.launch {
                                val cameBack = withTimeoutOrNull(RECOVERY_WAIT_MS) {
                                    if (ShizukuStateMachine.get() == ShizukuStateMachine.State.RUNNING) {
                                        true
                                    } else {
                                        ShizukuStateMachine.asFlow().first { it == ShizukuStateMachine.State.RUNNING }
                                        true
                                    }
                                } ?: false
                                if (cameBack) {
                                    ActivityLogManager.log("Shizuku", applicationContext.packageName, "Watchdog: the server came back after restart #1")
                                } else {
                                    showCrashNotification()
                                }
                            }
                        }
                        // start() spawns the su process — the root probe is now bounded
                        // (hasRootWithTimeout, 5s) and the su shell itself typically takes
                        // 1-5 s, timeout 20-30 s. Running it on Dispatchers.Main here froze the
                        // UI into an ANR and, because WatchdogService is a foreground service,
                        // fed the crash listener into a restart-block-ANR loop. Hop to IO for
                        // the blocking su shell; the log/notification calls around it remain
                        // on the main dispatcher.
                        withContext(Dispatchers.IO) {
                            ShizukuReceiverStarter.start(applicationContext)
                        }
                        Timber.tag(TAG).d("Watchdog: restart #$consecutiveCrashes (cooldown was ${cooldown}ms)")
                    } else {
                        Timber.tag(TAG).d("Watchdog: restart suppressed (cooldown active, ${now - lastRestartMs}ms / ${cooldown}ms)")
                    }
                } else if (state == ShizukuStateMachine.State.RUNNING) {
                    // Not pendingRecoveryJob: RUNNING is exactly what the recovery check
                    // waits for, and both run on the main dispatcher, so cancelling here
                    // would kill that check before it logs the recovery.
                    stabilityJob?.cancel()
                    // A successful RUNNING also proves the degraded probe found the
                    // environment healed — stop probing; the stability window below resets
                    // consecutiveCrashes and normal backoff restarts take over.
                    degradedRetryJob?.cancel()
                    degradedRetryJob = null
                    stabilityJob = scope.launch {
                        delay(STABILITY_WINDOW_MS)
                        consecutiveCrashes = 0
                    }
                }
                } catch (t: Throwable) {
                    // A child cancelled by collectLatest is normal flow control, not a fault.
                    if (t is CancellationException) throw t
                    // One bad round must never kill the collector: it is what turns CRASHED into
                    // a restart, so a silent exit leaves the probe updating a state machine that
                    // nobody acts on — the crash then reads as "the watchdog never fired".
                    runCatching {
                        ActivityLogManager.log(
                            "Shizuku", applicationContext.packageName,
                            "Watchdog: collector round failed: ${t.javaClass.simpleName}: ${t.message}"
                        )
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafely()) {
            isRunning.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_SERVICE) {
            intentionalStop = true
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        probeJob?.cancel()
        stabilityJob?.cancel()
        pendingRecoveryJob?.cancel()
        degradedRetryJob?.cancel()
        job?.cancel()
        scope.cancel()
        isRunning.set(false)
        if (intentionalStop) {
            ShizukuSettings.setWatchdog(applicationContext, false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        // Channel created in onCreate(); reference it by constant here
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WatchdogService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, WATCHDOG_CHANNEL_ID)
            .setContentTitle(getString(R.string.watchdog_running))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(launchPendingIntent)
            .addAction(
                R.drawable.ic_close_24,
                getString(R.string.watchdog_turn_off),
                stopPendingIntent
            )
            .setOngoing(true)
            .build()
    }

    private fun showCrashNotification() {
        if (!ShizukuSettings.isWatchdogCrashAlertsEnabled()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastCrashNotificationMs < CRASH_NOTIFICATION_COOLDOWN_MS) {
            return
        }
        lastCrashNotificationMs = now

        val channelId = CRASH_CHANNEL_ID
        val channelName = "Crash Reports"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val learnMoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProjectLinks.HELP_WATCHDOG))
        val learnMorePendingIntent = PendingIntent.getActivity(this, 10, learnMoreIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val disableIntent = SettingsPage.Notifications.NotificationChannel.buildIntent(applicationContext)
        val disablePendingIntent = PendingIntent.getActivity(this, 11, disableIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        // Manual crash report via BugReportDialogActivity or similar
        val reportIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProjectLinks.NEW_ISSUE))
        val reportPendingIntent = PendingIntent.getActivity(this, 12, reportIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.watchdog_shizuku_crashed_title))
            .setContentText(getString(R.string.watchdog_shizuku_crashed_text))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentIntent(learnMorePendingIntent)
            .setAutoCancel(true)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_report_manually), reportPendingIntent)
            .addAction(0, getString(R.string.watchdog_shizuku_crashed_action_turn_off_alerts), disablePendingIntent)

        if (consecutiveCrashes >= 3
            && ShizukuSettings.isCompanionFallbackEnabled()
            && StockShizukuCompat.isInstalled(applicationContext)
        ) {
            val companionIntent = applicationContext.packageManager
                .getLaunchIntentForPackage(StockShizukuCompat.PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (companionIntent != null) {
                val companionPendingIntent = PendingIntent.getActivity(
                    this, 3, companionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, getString(R.string.watchdog_open_stock_shizuku), companionPendingIntent)
            }
        }

        val notification = builder.build()

        nm.notify(NOTIFICATION_ID_CRASH, notification)
    }

    companion object {
        private const val TAG = "ShizukuWatchdog"
        private const val NOTIFICATION_ID_WATCHDOG = 1001
        private const val NOTIFICATION_ID_CRASH = 1002
        private const val ACTION_STOP_SERVICE = "action.stop_service"
        const val WATCHDOG_CHANNEL_ID = "shizuku_watchdog"
        const val CRASH_CHANNEL_ID = "crash_reports"

        /** How long a first restart waits for RUNNING before the crash heads-up is posted:
         * a successful restart took ~11 s on an API 34 emulator, a loaded device's first start 32 s. */
        private const val RECOVERY_WAIT_MS = 60_000L

        /** How long the server must remain RUNNING before consecutive crash count resets. */
        private const val STABILITY_WINDOW_MS = 60_000L

        private const val BASE_COOLDOWN_MS = 5_000L
        private const val MAX_COOLDOWN_MS = 300_000L   // 5 min cap
        private const val CRASH_NOTIFICATION_COOLDOWN_MS = 60_000L

        /** Consecutive crash loop threshold after which auto-restart degrades to long-cooldown probes. */
        private const val MAX_AUTO_RESTARTS = 5

        /** Degraded-mode probe cadence: one ShizukuReceiverStarter.start() attempt every 15 min. */
        private const val DEGRADED_COOLDOWN_MS = 15 * 60 * 1000L

        /** How often the watchdog re-pings the binder to learn that the server died. A binder
         * death callback is not guaranteed (it is armed only once this process took a binder
         * reference), so this poll is what actually feeds CRASHED into the collector below. */
        private const val PROBE_INTERVAL_MS = 5_000L

        /** How long a STARTING state may stay unanswered before it counts as a failed start. */
        private const val STARTING_GRACE_MS = 30_000L

        @Volatile
        private var lastCrashNotificationMs = 0L
        private val isRunning = AtomicBoolean(false)

        fun backoffMs(crashes: Int): Long =
            minOf(BASE_COOLDOWN_MS * (1L shl crashes.coerceAtMost(10)), MAX_COOLDOWN_MS)

        @JvmStatic
        fun start(context: Context) {
            try {
                val intent = Intent(context, WatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.tag("ShizukuApplication").w(e, "Failed to start WatchdogService: ${e.message}" )
            }
        }

        @JvmStatic
        fun stop(context: Context) {
            context.stopService(Intent(context, WatchdogService::class.java))
        }

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }
}
