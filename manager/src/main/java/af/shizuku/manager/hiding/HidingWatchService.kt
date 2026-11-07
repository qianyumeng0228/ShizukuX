package af.shizuku.manager.hiding

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import af.shizuku.manager.R
import af.shizuku.manager.force_dark.ForceDarkManager
import af.shizuku.manager.utils.Logger.LOGGER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The watch: while an app on one of the hiding lists is in front, the settings it objects to are
 * hidden, and they go back the moment it leaves.
 *
 * This is the part that makes the lists worth having. A setting is the device's, so hiding one is
 * hiding it from everybody; what makes that liveable is doing it for exactly as long as the app
 * that cares is on screen, and that is a question only the foreground can answer.
 *
 * It runs in the foreground itself, which is not decoration: a process the platform is free to
 * kill between two polls would leave the device in its hidden state with nothing left to notice
 * that nobody is using the app any more. The ongoing notification is how the user can always see
 * that hiding is on, and it is where the way out is.
 *
 * Polling rather than listening, because there is nothing to listen to: no API for the foreground
 * app is available to the shell, and the usage-stats app op that would answer the same question
 * is one the platform refuses to change for adb. One filtered dumpsys is about twenty
 * milliseconds, which is what makes this affordable at this interval.
 */
class HidingWatchService : Service() {

    companion object {
        private const val CHANNEL_ID = "hiding"
        private const val NOTIFICATION_ID = 1452

        private const val POLL_MS = 1200L

        /** How often the state is looked at while hiding is paused and there is nothing to do. */
        private const val IDLE_MS = 1500L

        /**
         * Called after a list changes: starts the watch when anything is on a list and stops it,
         * putting everything back, when nothing is.
         *
         * Runs shell commands, so it does not belong on the main thread.
         */
        fun refresh(context: Context) {
            val intent = Intent(context, HidingWatchService::class.java)
            if (!Hiding.hasWorkToDo() && !ForceDarkManager.needsInProcessWatch()) {
                // No app left to act for, so nothing has a reason to stay hidden - and nothing for
                // a watch to watch. Force dark belongs on this side of the line too: the switch it
                // holds is device-wide, so it goes back with everything else rather than being
                // left on by a service that is about to stop.
                Hiding.restoreAll()
                ForceDarkManager.restore()
                runCatching { context.stopService(intent) }
                return
            }

            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { LOGGER.w("could not start the hiding watch", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, HidingWatchService::class.java)) }
        }

        /** Whether hiding is live right now, which is what the page says. */
        @JvmStatic
        fun isRunning(): Boolean = running.get()

        /** The app a list is hiding for right now, or null. */
        @JvmStatic
        fun holdingFor(): String? = holding.get()

        private val holding = AtomicReference<String?>(null)

        private val running = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watching: Job? = null

    /**
     * What was hidden at the last pass that differed, so the log says the story once.
     *
     * Written because of the one thing this feature cannot be watched through: hiding a
     * transport turns off adb, which is the cable every log reader is holding. A line per change
     * rather than per pass, so a session is a handful of lines and not one every second.
     */
    private var lastLoggedState: String? = null

    /**
     * The app this watch has already restarted, so one launch is restarted once.
     *
     * Not a cooldown: the relaunched app comes straight back to the front without the watch ever
     * seeing it leave, so a timer would only make the second kill later rather than absent. It is
     * cleared the moment nothing is hidden any more, which is when the user has left the app.
     */
    private var restartedFor: String? = null

    /** The state the notification last said, so it is posted on a change and not on every pass. */
    private var notifiedKey: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        channel()

        // Asked for once, quietly, the first time the watch runs: all three are shell commands,
        // so there is no dialog to show and nothing for the user to decide.
        scope.launch { HidingGrants.ensureQuietly() }

        // Posted before the first pass, because a foreground service that has not posted its
        // notification yet is one the platform will kill.
        notifiedKey = if (Hiding.isPaused()) "paused" else "idle"
        val notification = notification(hidingFor = null, paused = Hiding.isPaused())
        // The type argument arrived with Android 10 (API 29); below it the two-argument call is
        // the only one there is, and it is also the only one that is legal there.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        watching = scope.launch { watch() }
    }

    /**
     * Sticky, because the watch is the only thing that knows the settings are hidden.
     *
     * A process the platform kills for memory leaves the device lying about itself, and a watch
     * that did not come back would leave it that way until somebody opened this app.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running.set(false)
        holding.set(null)
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun watch() {
        var paused = false

        while (scope.isActive) {
            if (!Hiding.hasWorkToDo() && !ForceDarkManager.needsInProcessWatch()) {
                holding.set(null)
                Hiding.restoreAll()
                ForceDarkManager.restore()
                notifyOnce("idle", notification(hidingFor = null, paused = false))
                stopSelf()
                return
            }

            // Nothing can see the foreground, so nothing can know the app that made this
            // necessary is still open - and a device left hidden by a watch that stopped watching
            // is worse than not hiding at all. Everything goes back and it stands down.
            if (!Hiding.canWatch()) {
                Hiding.restoreAll()
                ForceDarkManager.restore()
                notifyOnce("unavailable", notification(hidingFor = null, paused = false, unavailable = true))
                stopSelf()
                return
            }

            if (Hiding.isPaused()) {
                // Restored once on the way in, not once a second: hiding is suspended, so there
                // is nothing to look at until somebody resumes it. Force dark is put back once
                // here as well, because "give my settings back" is answered by the screen too -
                // and a phone left dark would be the one part of it a user cannot miss.
                if (!paused) {
                    holding.set(null)
                    Hiding.restoreAll()
                    ForceDarkManager.restore()
                    notifyOnce("paused", notification(hidingFor = null, paused = true))
                    paused = true
                }
                delay(IDLE_MS)
                continue
            }

            paused = false
            val hidingFor = runCatching { Hiding.reconcile() }
                .onFailure { LOGGER.w("the hiding pass failed", it) }
                .getOrNull()
            holding.set(hidingFor)

            // The other per-app rule runs off the same pass and the same foreground: the app this
            // pass decided was in front is the app the switch is held for, so the two rules cannot
            // disagree about what is on screen.
            runCatching { ForceDarkManager.applyFor(Hiding.lastInFront) }
                .onFailure { LOGGER.w("the force-dark pass failed", it) }

            // An app that has just been hidden for started before any of this, so it has read the
            // settings it objects to. Only a new process can read the hidden ones, which is what
            // the restart is for. Once per app session: the relaunch is the same app in front
            // again, and restarting that too would end where it started.
            if (hidingFor == null) {
                restartedFor = null
            } else if (hidingFor != restartedFor &&
                Hiding.isRestartOnOpenEnabled() &&
                Hiding.shellAvailable()
            ) {
                // Taken before the attempt, not only on success: a force-stop that failed would
                // otherwise be asked for again on the next pass, once a second, over an app the
                // user is looking at.
                restartedFor = hidingFor
                if (runCatching { Hiding.restartForLaunch(hidingFor) }.getOrDefault(false)) {
                    LOGGER.i("restarted $hidingFor so it reads the hidden settings")
                }
            }

            notifyOnce(
                if (hidingFor == null) "idle" else "hidden:$hidingFor",
                notification(hidingFor, paused = false)
            )
            logState(hidingFor)
            delay(POLL_MS)
        }
    }

    /** Says what is hidden, once per change. */
    private fun logState(hidingFor: String?) {
        val state = Signal.entries.joinToString(" ") { signal ->
            when {
                !Hiding.isSignalEnabled(signal) -> "${signal.name}=off"
                Hiding.appsFor(signal).isEmpty() -> "${signal.name}=off-list"
                Hiding.isHidden(signal) -> "${signal.name}=HIDDEN"
                else -> "${signal.name}=visible"
            }
        }
        if (state == lastLoggedState) return
        lastLoggedState = state
        LOGGER.i("hiding for ${hidingFor ?: "nothing"} | $state")
    }

    private fun notify(notification: Notification) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * The same notification, only when its text would change.
     *
     * The pass runs about once a second, and posting the same ongoing notification that often is
     * a shade that flickers and a wakeup that buys nothing: the state it reports is read once per
     * pass but changes far less often than that.
     */
    private fun notifyOnce(key: String, notification: Notification) {
        if (key == notifiedKey) return
        notifiedKey = key
        notify(notification)
    }

    private fun channel() {
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.hiding_notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
                manager.createNotificationChannel(channel)
            }
        }
    }

    /**
     * One notification for both states, because it is the same fact: whether the settings are
     * hidden right now, and the one action that changes that.
     */
    private fun notification(
        hidingFor: String?,
        paused: Boolean,
        unavailable: Boolean = false
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HidingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hiding_24)
            .setContentIntent(open)
            .setOngoing(true)

        if (unavailable) {
            builder
                .setContentTitle(getString(R.string.hiding_notification_stopped_title))
                .setContentText(getString(R.string.hiding_notification_stopped_text))
                .setOngoing(false)
                // A way back that does not need the lists to be touched again: the reason it
                // stopped was Shizuku, and Shizuku being back is the reason to start again.
                .addAction(
                    0,
                    getString(R.string.hiding_start_again),
                    broadcast(Hiding.ACTION_RESUME_HIDING, 1)
                )
            return builder.build()
        }

        if (paused) {
            builder
                .setContentTitle(getString(R.string.hiding_notification_paused_title))
                .setContentText(getString(R.string.hiding_notification_paused_text))
                .addAction(
                    0,
                    getString(R.string.hiding_resume),
                    broadcast(Hiding.ACTION_RESUME_HIDING, 1)
                )
            return builder.build()
        }

        // Two states and two sentences, because they are not the same news: a notification that
        // says the settings are hidden while they are not would have the user looking for a
        // problem that is not there.
        if (hidingFor == null) {
            builder
                .setContentTitle(getString(R.string.hiding_notification_idle_title))
                .setContentText(getString(R.string.hiding_notification_idle_text))
            return builder.build()
        }

        builder
            .setContentTitle(getString(R.string.hiding_notification_title))
            .setContentText(getString(R.string.hiding_notification_text))
            .addAction(
                0,
                getString(R.string.hiding_restore),
                broadcast(Hiding.ACTION_RESTORE_HIDING, 0)
            )

        return builder.build()
    }

    private fun broadcast(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, HidingRestoreReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
