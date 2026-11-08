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
import io.sentry.Sentry
import io.sentry.Breadcrumb
import af.shizuku.manager.R
import af.shizuku.manager.AppConstants
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.LaunchMethod
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.database.ActivityLogManager
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

    /** Display name used for the start/stop trail written to the in-app activity log. */
    private const val LOG_APP_NAME = "ShizukuX"

    /** App-lifetime scope for the v25 async ADB→root fallback probe. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * De-duplication gate for automatic starts: one ADB bring-up takes a couple of seconds, so a
     * 10s window is long enough to swallow every concurrent automatic caller while still letting
     * the watchdog's own backoff retry soon after a genuinely failed attempt.
     */
    private object startGate
    private var lastAutoStartAt = 0L
    private const val START_DEDUP_WINDOW_MS = 10_000L

    enum class WorkerState {
        AWAITING_WIFI,
        AWAITING_RETRY,
        AWAITING_DISCOVERY,
        RUNNING,
        STOPPED
    }

    fun start(context: Context, forceStart: Boolean = false) {
        // The user's last deliberate action was "stop the server". Boot sync, the watchdog, alarm
        // re-arm and the ADB worker all funnel here; letting any of them through would restart a
        // service the user stopped, and because the restart resolves LaunchMethod.ADB the server
        // comes back as a new instance with an empty grant list. Only an explicit start (forceStart,
        // or a path that already wrote STARTING - see ShizukuStateMachine.markUserStopped()) clears
        // the tombstone, so an automatic caller can never resurrect it on its own.
        if (!forceStart && ShizukuSettings.isUserStoppedIntent()) {
            Timber.tag(AppConstants.TAG)
                .i("Auto start skipped: the user stopped the service deliberately")
            return
        }

        // Automatic callers arrive from several places at once (cold-start receiver, watchdog,
        // ADB worker, boot sync). Each of them used to spawn its own starter: the second starter
        // kills the first server and starts another, and if that second attempt loses the race
        // the device is left with an orphan shizuku_plus_server plus one working pair (three
        // processes observed on device). A short window collapses concurrent attempts into the
        // one that is already in flight; an explicit user start is never throttled.
        if (!forceStart) {
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(startGate) {
                if (now - lastAutoStartAt < START_DEDUP_WINDOW_MS) {
                    Timber.tag(AppConstants.TAG).i("Auto start skipped: a start is already in flight")
                    return
                }
                lastAutoStartAt = now
            }
        }

        // forceStart means the user pressed "start" themselves (settings page / explicit intent).
        // Declare that intent before anything else: transition(STARTING) persists STARTING, which
        // is what clears the tombstone above, and it stops the 800ms stop-settling fallback in
        // markUserStopped() from turning this deliberate start back into STOPPED.
        if (forceStart && !ShizukuStateMachine.isRunning()
            && ShizukuStateMachine.get() != ShizukuStateMachine.State.STARTING
        ) {
            ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        }

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
                //
                // v26 (Boot sync): the availability probe is `getAdbTcpPort() > 0` — the
                // persist.adb.tcp.port property existing means wireless debugging has been
                // configured (the property survives reboots and is readable at BOOT_COMPLETED),
                // so it is NOT "unavailable" even if Wi-Fi is not connected yet: AdbStartWorker
                // itself owns the readiness semantics (live-port probe, mDNS discovery, the
                // AWAITING_WIFI notification and retry loop). Dropping the v25 `&& !isWifiRequired()`
                // clause fixes a false negative: wireless-debugging users (tcp_mode=false) were
                // classified as unavailable and wrongly diverted to the root fallback/notification
                // on every boot and crash recovery. Only a missing port (never configured, or the
                // property lost) is treated as unavailable and falls through to root/notify.
                // v28: a remembered port counts as usable. On this device the ADB wire is the
                // only route that can ever bring the server up: the app process sees no su at
                // all (exec answers EACCES, /proc/<pid>/root/system/bin/su does not exist), so
                // the root branch below is a guaranteed dead end, while the remembered port
                // (adbd / adb reverse) did start the server on every attempt. Waiting for the
                // service.adb.tcp.port property alone therefore parked the watchdog on the root
                // path with no fallback.
                val lastAdbPort = ShizukuSettings.getLastPort()
                val adbUsable = EnvironmentUtils.getAdbTcpPort() > 0 || lastAdbPort > 0
                if (!adbUsable) {
                    // v27: go straight to the root start instead of deciding on a root probe
                    // first. Both libsu's `Shell.getShell().isRoot` and the cached
                    // EnvironmentUtils.isRooted() can read "no root" in a background process on
                    // SukiSU while `su 0 sh -c …` from that same process works (on device the
                    // probe answered false, the fallback was skipped, and the service stayed down
                    // for 60s). rootStart() now attempts the su launch itself and reports the real
                    // outcome, so a failed start always ends in the unavailable notification
                    // instead of silence.
                    scope.launch {
                        val started = withContext(Dispatchers.IO) { rootStart(context) }
                        if (!started) {
                            // Last resort: a previously known ADB port. On this device the root
                            // route can be unavailable for good (app processes see no
                            // /system/bin/su at all: exec answers EACCES and the app's own
                            // namespace has no su entry), while adb reverse / wireless debugging
                            // may still expose the remembered port. Handing it to AdbStartWorker
                            // reuses its retry + AWAITING_WIFI reporting instead of a bare
                            // notification.
                            val lastPort = ShizukuSettings.getLastPort()
                            if (lastPort > 0) {
                                ActivityLogManager.log(
                                    LOG_APP_NAME,
                                    context.packageName,
                                    "Watchdog: root fallback failed, retrying ADB on port $lastPort"
                                )
                                AdbStartWorker.enqueue(context)
                                updateNotification(context, WorkerState.RUNNING)
                            } else {
                                ActivityLogManager.log(
                                    LOG_APP_NAME,
                                    context.packageName,
                                    "Watchdog: root fallback did not start the server"
                                )
                                showUnavailableNotification(context)
                            }
                        }
                    }
                } else {
                    AdbStartWorker.enqueue(context)
                    updateNotification(context, WorkerState.RUNNING)
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
        ShizukuStateMachine.markUserStopped()
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
        // v27 (manual-stop self-heal): the starter supervisor (starter.cpp start_server /
        // supervise_server) is the process that restarts a SIGKILLed shizuku_plus_server after
        // 5s ("restarting shizuku_server in 5 seconds (attempt 1/5)..."), with a crash counter
        // capped at 5 and reset whenever the server survived >= 60s. Killing only the server
        // leaves the supervisor alive, so a manual Stop self-heals seconds later and the user's
        // explicit intent is silently ignored (this is the "手动停止后软件会自启" bug). Kill the
        // supervisor FIRST so the 5s restart window never opens; the server kill then terminates
        // the actual service. The match is exact enough: the supervisor's argv is the
        // libshizuku.so path (+ --apk=...), and no app-facing process (xyz.shizuku.extra.api /
        // app_process / adbd / the server itself) carries "libshizuku.so" in its cmdline — so
        // unlike `pkill -f shizuku` (which hit the manager's own cmdline on a v25 test) this
        // cannot self-kill or touch unrelated processes.
        runCatching {
            af.shizuku.manager.database.ShizukuProcessUtils.runPrivilegedCapture(
                arrayOf("pkill", "-f", "libshizuku.so")
            )
        }.onFailure { Timber.tag(AppConstants.TAG).w(it, "pkill libshizuku.so supervisor failed") }

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

    private fun rootStart(context: Context): Boolean {
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
            return true
        }

        // No root probe here on purpose. Both libsu's `Shell.getShell().isRoot` and the cached
        // EnvironmentUtils.isRooted() can answer false in a background process on SukiSU while
        // `su 0 sh -c …` from the same process works — that false negative is exactly what made
        // the watchdog fallback a no-op. suExitCode() below is the attempt: it either runs the
        // starter (exit 0 = started) or fails loudly with the reason recorded in the activity log.

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
                suOutput("pm path $pkg").firstOrNull { it.startsWith("package:") }
                    ?.removePrefix("package:")?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            } ?: context.applicationInfo.sourceDir

            val command = "${Starter.userCommand} --apk='$apkPath'"
            val exitCode = suExitCode(command, 30)
            if (exitCode != 0) {
                // A non-zero shell exit code used to be silent. The state machine is then left in
                // STARTING, which update() preserves indefinitely while the binder is dead — so the
                // watchdog (which only reacts to CRASHED) never retries and the UI shows a
                // perpetual "Starting…".
                Sentry.addBreadcrumb(Breadcrumb("Background Root start failed: starter exited $exitCode").apply {
                    category = "shizuku.starter"
                    level = io.sentry.SentryLevel.ERROR
                })
                Timber.tag(AppConstants.TAG).e("Root starter exited with code $exitCode")
                ActivityLogManager.log(
                    LOG_APP_NAME,
                    context.packageName,
                    "Watchdog: root starter exited $exitCode"
                )
                recoverFromFailedStart()
                return false
            }
            return true
        } catch (e: Exception) {
            Sentry.addBreadcrumb(Breadcrumb("Background Root start failed: ${e.message}").apply {
                category = "shizuku.starter"
                level = io.sentry.SentryLevel.ERROR
            })
            Timber.tag(AppConstants.TAG).e(e, "Failed to start Shizuku with root")
            ActivityLogManager.log(
                LOG_APP_NAME,
                context.packageName,
                "Watchdog: root start threw ${e.javaClass.simpleName}: ${e.message}"
            )
            recoverFromFailedStart()
            return false
        }
    }

    /** Settles a failed start attempt into CRASHED (see ShizukuStateMachine.markStartFailed:
     *  STOPPED would both claim the user asked for this and leave the watchdog with a state it
     *  never acts on), then re-detects: if a previous server instance is actually still alive,
     *  update() flips the state to RUNNING. */
    private fun recoverFromFailedStart() {
        ShizukuStateMachine.markStartFailed()
    }

    /** Locates a usable su binary by absolute path. An app process does not necessarily carry the
     *  root manager's bin directory on its PATH (SukiSU installs su into /system/bin on this
     *  device, but the APEX-era app PATH is not guaranteed to contain it), and
     *  ProcessBuilder("su", …) then fails with IOException before the manager ever sees the
     *  request. Resolve once per call: the list is short and File.exists() is cheap. */
    private fun resolveSuBinary(): String? {
        val candidates = listOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/data/adb/ksu/bin/su",
            "/data/adb/ap/bin/su",
            "/data/adb/magisk/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/data/local/xbin/su"
        )
        return candidates.firstOrNull { java.io.File(it).exists() }
    }

    /** Runs [command] as root through the device's su binary (`su 0 sh -c <command>`) instead of
     *  a libsu root shell. libsu builds its cached shell through its own builder and, on this
     *  device class (SukiSU + enforcing SELinux), a background process cannot always build one:
     *  `Shell.getShell().isRoot` then blocks until the probe is cut off and the caller reads
     *  "no root" although `su 0 sh -c …` from the same process returns rc=0 and starts the
     *  server (verified on device). EnvironmentUtils already uses this direct-su route for its
     *  last-resort probe, so the watchdog fallback now runs on the path known to work here. */
    private fun suExitCode(command: String, timeoutSec: Long): Int {
        val su = resolveSuBinary() ?: "su"
        val p = ProcessBuilder(su, "0", "sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        return if (p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
            p.exitValue()
        } else {
            p.destroyForcibly()
            -1
        }
    }

    /** Same su route as [suExitCode], for short read-only queries such as `pm path`. */
    private fun suOutput(command: String): List<String> {
        val su = resolveSuBinary() ?: "su"
        val p = ProcessBuilder(su, "0", "sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val lines = p.inputStream.bufferedReader().use { it.readLines() }
        p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
        return lines
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
}