package af.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import timber.log.Timber
import androidx.core.app.NotificationCompat
import com.topjohnwu.superuser.Shell
import io.sentry.Sentry
import io.sentry.Breadcrumb
import af.shizuku.manager.R
import af.shizuku.manager.AppConstants
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.ProjectLinks
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.worker.AdbStartWorker
import rikka.shizuku.Shizuku
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object ShizukuReceiverStarter {

    const val NOTIFICATION_ID = 1447
    private const val CHANNEL_ID = "AdbStartWorker"

    /** App-lifetime scope for the v25 async ADB→root fallback probe. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    enum class WorkerState {
        AWAITING_WIFI,
        AWAITING_RETRY,
        AWAITING_DISCOVERY,
        RUNNING,
        STOPPED
    }

    fun start(context: Context, forceStart: Boolean = false) {
        if (!forceStart && (UserHandleCompat.myUserId() > 0
                || ShizukuStateMachine.isRunning()
                || ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING)) return

        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                // v25: probe the wireless-debugging preconditions before committing to
                // AdbStartWorker. v24 on-device testing showed the watchdog recovering into a
                // dead end when SP mode=ADB but wireless debugging was never armed (no TCP port /
                // tcp mode off): the worker's mDNS discovery then times out and the server never
                // comes up. If the ADB path can't work here but the device is rooted, fall back to
                // rootStart (which keeps all its defences: binder preflight, pm path resolution,
                // recoverFromFailedStart); if neither path is available, tell the user explicitly
                // instead of failing silently. Mirrors the "try the configured path, degrade to
                // what the device can actually do" recovery thinking of KernelSU/SukiSU daemons.
                val adbUsable = EnvironmentUtils.getAdbTcpPort() > 0 && !EnvironmentUtils.isWifiRequired()
                if (!adbUsable) {
                    // v25 fallback: root probe is async — start() runs on the main thread
                    // from receivers and inside withContext(IO) from the watchdog, and libsu's
                    // probe can block on a pending grant prompt. Never block the caller;
                    // bound the probe to 5s and fall through to the notification branch.
                    scope.launch {
                        if (hasRootWithTimeout()) {
                            withContext(Dispatchers.IO) { rootStart(context) }
                        } else {
                            showUnavailableNotification(context)
                        }
                    }
                } else if (adbUsable) {
                    AdbStartWorker.enqueue(context)
                    updateNotification(context, WorkerState.RUNNING)
                } else {
                    showUnavailableNotification(context)
                }
            } else {
                showPermissionErrorNotification(context)
            }
        } else {
            Timber.tag(AppConstants.TAG).w("Background start not supported")
        }
    }

    /** Symmetric with [start]: stops a running service. Shared by the manual STOP broadcast
     *  receiver and the Tasker/Locale plugin's fire receiver. */
    fun stop() {
        if (!ShizukuStateMachine.isRunning()) return
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING)
        killServerProcess()
        runCatching { Shizuku.exit() }
            .onFailure { Timber.tag(AppConstants.TAG).w(it, "Shizuku.exit failed") }
    }

    /**
     * Terminate the long-lived privileged server process itself (not just this client's binder
     * connection). `Shizuku.exit()` alone only disconnects the local binder; the server keeps
     * running and the next binder reconnection instantly flips the state machine back to RUNNING,
     * which made "Stop" look like it stopped for a few seconds and then self-healed. Running the
     * kill through the privileged process API ensures it works for both adb and root launches.
     */
    fun killServerProcess() {
        runCatching {
            af.shizuku.manager.database.ShizukuProcessUtils.runPrivilegedCapture(
                arrayOf("pkill", "-f", "shizuku_plus_server")
            )
        }.onFailure { Timber.tag(AppConstants.TAG).w(it, "pkill shizuku_plus_server failed") }
    }

    fun buildNotification(context: Context, msg: String? = null): Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wadb_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val cancelIntent = Intent(context, NotifCancelReceiver::class.java)
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val attemptNowIntent = Intent(context, NotifAttemptReceiver::class.java)
        val attemptNowPendingIntent = PendingIntent.getBroadcast(
            context, 0, attemptNowIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wifiIntent = SettingsPage.InternetPanel.buildIntent(context)
        val wifiPendingIntent = PendingIntent.getActivity(
            context, 0, wifiIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nb = NotificationCompat.Builder(context, CHANNEL_ID)

        if (msg != null) nb.setContentText(msg)

        return nb
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wadb_notification_title))
            .setOngoing(true)
            .setSilent(true)
            .addAction(R.drawable.ic_notification_server_restart, context.getString(R.string.wadb_notification_attempt_now), attemptNowPendingIntent)
            .addAction(R.drawable.ic_notification_close_24, context.getString(android.R.string.cancel), cancelPendingIntent)
            .setContentIntent(wifiPendingIntent)
            .build()
    }

    fun updateNotification(context: Context, state: WorkerState) {
        if (state == WorkerState.STOPPED) return
        val msgId = when (state) {
            WorkerState.AWAITING_WIFI -> R.string.wadb_notification_wifi_required
            WorkerState.AWAITING_RETRY -> R.string.wadb_notification_retry
            WorkerState.AWAITING_DISCOVERY -> R.string.wadb_notification_discovery_timeout
            else -> null
        }
        val msg = if (msgId != null) context.getString(msgId) else null
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(context, msg))
    }

    private fun rootStart(context: Context) {
        Sentry.addBreadcrumb(Breadcrumb("Background Root start initiated").apply { category = "shizuku.starter" })

        // Live pre-flight before doing anything that can kill an existing server.
        //
        // starter.cpp unconditionally runs kill_old_processes() on every invocation: any
        // shizuku_plus_server found by name gets SIGKILL'd before a new instance is spawned.
        // The guard in start() above only trusts ShizukuStateMachine.isRunning(), which is the
        // in-memory/persisted state — stale when the server was started *outside* this app
        // (e.g. the documented manual `su -c "nohup libshizuku.so --apk=… &"` path). On a
        // cold-started process the persisted state is STOPPED, so start() proceeded, the
        // healthy manual server was killed, and the freshly spawned instance then crashed with
        // ClassNotFoundException for rikka.shizuku.server.ShizukuService (the "double failure"
        // seen on device). Ping the binder right now: if a server is actually alive, skip the
        // start entirely and never kill it.
        if (ShizukuStateMachine.update() == ShizukuStateMachine.State.RUNNING) {
            Sentry.addBreadcrumb(Breadcrumb("Background Root start skipped: server already alive").apply {
                category = "shizuku.starter"
                level = io.sentry.SentryLevel.INFO
            })
            return
        }

        if (!Shell.getShell().isRoot) {
            Sentry.addBreadcrumb(Breadcrumb("Background Root start failed - no root").apply {
                category = "shizuku.starter"
                level = io.sentry.SentryLevel.WARNING
            })
            //NotificationHelper.notify(context, AppConstants.NOTIFICATION_ID_STATUS, AppConstants.NOTIFICATION_CHANNEL_STATUS, R.string.notification_service_start_no_root)
            Shell.getCachedShell()?.close()
            return
        }

        try {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)

            // Resolve the CURRENT on-disk base.apk via `pm path` — the same derivation the
            // stable manual command uses (nativeLibDir/apkPath are taken from `pm path`).
            // Starter.internalCommand bakes in applicationInfo.sourceDir, which can reference a
            // stale apk on this device (process kept alive across an update, or an early
            // direct-boot context); app_process then loads that apk's dex and cannot find
            // rikka.shizuku.server.ShizukuService -> ClassNotFoundException. pm path always
            // returns the installed, canonical base.apk; fall back to sourceDir only if it
            // somehow fails.
            val pkg = context.packageName
            val apkPath = try {
                Shell.cmd("pm path $pkg").exec().out.firstOrNull { it.startsWith("package:") }
                    ?.removePrefix("package:")?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            } ?: context.applicationInfo.sourceDir

            val command = "${Starter.userCommand} --apk='$apkPath'"
            val result = Shell.cmd(command).exec()
            if (!result.isSuccess) {
                // libsu doesn't throw on a non-zero exit. Without this check the state machine is
                // left in STARTING, which update() preserves indefinitely while the binder is dead —
                // so the watchdog (which only reacts to CRASHED) never retries and the UI shows a
                // perpetual "Starting…".
                Sentry.addBreadcrumb(Breadcrumb("Background Root start failed: starter exited ${result.code}").apply {
                    category = "shizuku.starter"
                    level = io.sentry.SentryLevel.ERROR
                })
                Timber.tag(AppConstants.TAG).e("Root starter exited with code ${result.code}")
                recoverFromFailedStart()
            }
        } catch (e: Exception) {
            Sentry.addBreadcrumb(Breadcrumb("Background Root start failed: ${e.message}").apply {
                category = "shizuku.starter"
                level = io.sentry.SentryLevel.ERROR
            })
            Timber.tag(AppConstants.TAG).e(e, "Failed to start Shizuku with root")
            recoverFromFailedStart()
        }
    }

    /** Clears a stuck STARTING state after a failed start attempt, then re-detects: if a previous
     *  server instance is actually still alive, update() flips the state back to RUNNING. */
    private fun recoverFromFailedStart() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
        ShizukuStateMachine.update()
    }

    private fun showPermissionErrorNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wadb_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val webpageIntent = Intent(Intent.ACTION_VIEW, Uri.parse(ProjectLinks.HELP_BOOT))
        val pendingWebpageIntent = PendingIntent.getActivity(
            context, 0, webpageIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val msg = context.getString(R.string.wadb_permission_error_notification_content)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.wadb_permission_error_notification_title))
            .setContentText(msg)
            .setSilent(true)
            .setContentIntent(pendingWebpageIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    /** v25: ADB launch was requested but wireless debugging is not armed AND the device is not
     *  rooted, so neither the ADB nor the root path can start the service. Post an explicit
     *  notification instead of failing silently (previously the worker would just time out). */
    private fun showUnavailableNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.wadb_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val msg = context.getString(R.string.wadb_notification_unavailable)
        nm.notify(NOTIFICATION_ID, buildNotification(context, msg))
    }

    /** v25: bounded root probe for the background (watchdog/receiver) ADB→root fallback.
     *  libsu's `Shell.getShell().isRoot` blocks the calling thread on the su spawn — when the
     *  app holds no root grant (or the manager prompt cannot be answered headless) that call
     *  wedges the recovery flow indefinitely, and a coroutine timeout cannot interrupt a
     *  blocking call anyway. Probe on a daemon thread and bound the wait with a latch instead:
     *  a pending prompt times out as "no root" after 5s and the caller falls through to the
     *  notification branch; the daemon thread may still sit on a prompt the user can answer
     *  later, which does not block any state-machine flow. */
    private suspend fun hasRootWithTimeout(): Boolean {
        return withContext(Dispatchers.IO) {
            val result = arrayOf(false)
            val latch = java.util.concurrent.CountDownLatch(1)
            val t = Thread {
                try {
                    result[0] = Shell.getShell().isRoot
                } catch (e: Exception) {
                    result[0] = false
                } finally {
                    latch.countDown()
                }
            }.apply {
                isDaemon = true
                start()
            }
            try {
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            result[0]
        }
    }
}