package af.shizuku.manager.adb
import timber.log.Timber
import af.shizuku.manager.R

import android.annotation.TargetApi
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.Observer
import kotlinx.coroutines.*
import af.shizuku.manager.MainActivity
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.starter.StarterActivity

import rikka.core.ktx.unsafeLazy
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

@TargetApi(Build.VERSION_CODES.R)
class AdbPairingService : Service() {

    companion object {

        const val NOTIFICATION_CHANNEL = "adb_pairing"
        const val NOTIFICATION_ID = 1

        /** Broadcast sent after a successful pairing; StarterActivity listens to continue its flow. */
        const val ACTION_PAIRING_SUCCEEDED = "af.shizuku.manager.action.ADB_PAIRING_SUCCEEDED"
        const val EXTRA_PORT = "port_number"

        private const val tag = "AdbPairingService"

        private const val replyRequestId = 1
        private const val stopRequestId = 2
        private const val retryRequestId = 3
        private const val launchRequestId = 4
        private const val startRequestId = 5
        private const val dialogRequestId = 6
        private const val startAction = "start"
        private const val stopAction = "stop"
        private const val replyAction = "reply"
        internal const val dialogReplyAction = "dialog_reply"
        private const val remoteInputResultKey = "paring_code"
        private const val portKey = "port_number"
        internal const val dialogCodeKey = "dialog_code"

        fun startIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(startAction)
        }

        private fun stopIntent(context: Context): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(stopAction)
        }

        private fun replyIntent(context: Context, port: Int): Intent {
            return Intent(context, AdbPairingService::class.java).setAction(replyAction).putExtra(portKey, port)
        }

        fun dialogReplyIntent(context: Context, port: Int, code: String): Intent {
            return Intent(context, AdbPairingService::class.java)
                .setAction(dialogReplyAction)
                .putExtra(portKey, port)
                .putExtra(dialogCodeKey, code)
        }
    }

    private var adbMdns: AdbMdns? = null

    /** mDNS used by the post-pairing auto-grant/auto-start flow; stopped on destroy. */
    private var connectMdns: AdbMdns? = null

    private val observer = Observer<Int> { port ->
        Timber.tag(tag).i("Pairing service port: $port")
        if (port <= 0) return@Observer

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createInputNotification(port)

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private var started = false

    override fun onCreate() {
        super.onCreate()

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                getString(R.string.notification_channel_adb_pairing),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                setAllowBubbles(false)
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            startAction -> {
                onStart()
            }
            replyAction -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(remoteInputResultKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1) {
                    onInput(code.toString(), port)
                } else {
                    onStart()
                }
            }
            dialogReplyAction -> {
                val code = intent.getStringExtra(dialogCodeKey) ?: ""
                val port = intent.getIntExtra(portKey, -1)
                if (port != -1 && code.isNotEmpty()) {
                    onInput(code, port)
                } else {
                    onStart()
                }
            }
            stopAction -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                null
            }
            else -> {
                return START_NOT_STICKY
            }
        }
        if (notification != null) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } catch (e: Throwable) {
                // startForeground can be denied by the OS: a FGS-start-not-allowed from background,
                // or a connectedDevice-FGS SecurityException on OEMs that don't grant the required
                // companion permission (SHIZUKUPLUS-6E). The pairing notification IS the UI the user
                // needs, so post it directly whenever FGS fails — pairing still works without the
                // foreground-service upgrade. Warn (not error) so it doesn't read as a hard crash.
                Timber.tag(tag).w(e, "startForeground failed; posting pairing notification without FGS")
                try {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
                } catch (t: Throwable) {
                    Timber.tag(tag).e(t, "failed to post pairing notification fallback")
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    override fun onTimeout(startId: Int) {
        Toast.makeText(this, R.string.toast_pairing_timeout, Toast.LENGTH_SHORT).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startSearch() {
        if (started) return
        started = true
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    private fun stopSearch() {
        if (!started) return
        started = false
        adbMdns?.stop()
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        stopSearch()
        connectMdns?.stop()
        connectMdns = null
        serviceScope.cancel()
    }

    private fun onStart(): Notification {
        startSearch()
        return searchingNotification
    }

    private fun onInput(code: String, port: Int): Notification {
        serviceScope.launch {
            // Prefer the mDNS-resolved host; falls back to loopback if discovery isn't live (e.g.
            // the service was killed and restarted from the reply intent, which only carries the port).
            val host = adbMdns?.resolvedHost ?: "127.0.0.1"

            val key = try {
                AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
            } catch (e: Throwable) {
                Timber.e("failed to load or create AdbKey", e)
                return@launch
            }

            AdbPairingClient(host, port, code, key).runCatching {
                start()
            }.onFailure {
                handleResult(false, it)
            }.onSuccess {
                handleResult(it, null)
            }
        }

        return workingNotification
    }

    private fun handleResult(success: Boolean, exception: Throwable?) {
        stopForeground(STOP_FOREGROUND_DETACH)

        if (success) {
            Timber.tag(tag).i("Pair succeed")
            stopSearch()
            // Replace the "searching" notification immediately — the post-pairing flow
            // (mDNS-discover connect port -> pm grant -> hand off) can take a few seconds,
            // and leaving "searching for pairing service" up while it runs looks stuck.
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, startingNotification)
            // One-tap flow: after pairing succeeds, mDNS-discover the wireless-debugging
            // connect port, connect over the freshly paired ADB key to run
            // `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` (so the app can
            // toggle wireless debugging itself next time), then hand off to the starter
            // screen. On any failure we fall back to the plain "pairing succeeded"
            // notification with the start button.
            autoGrantAndStart()
            return
        }

        val title: String
        val text: String?

        title = getString(R.string.notification_adb_pairing_failed_title)

        text = when (exception) {
            is ConnectException -> {
                getString(R.string.cannot_connect_port)
            }
            is AdbInvalidPairingCodeException -> {
                getString(R.string.paring_code_is_wrong)
            }
            is AdbKeyException -> {
                getString(R.string.adb_error_key_store)
            }
            else -> {
                exception?.let { Log.getStackTraceString(it) }
            }
        }

        if (exception != null) {
            Timber.tag(tag).w(exception, "Pair failed")
        } else {
            Timber.tag(tag).w("Pair failed")
        }

        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(title)
                .setContentText(text)
                .apply {
                    addAction(retryNotificationAction)
                }
                .build()
        )
        stopSelf()
    }

    /**
     * One-tap wireless-debugging start, ported from Stellar's flow:
     * 1. mDNS-discover the connect port (_adb-tls-connect._tcp).
     * 2. Connect over the just-paired ADB key and run
     *    `pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS`, so the app can toggle
     *    wireless debugging itself on future launches (no manual developer-options step).
     * 3. Launch StarterActivity which connects and starts the service.
     *
     * Any failure falls back to the "pairing succeeded" notification with the start button.
     */
    private fun autoGrantAndStart() {
        var handled = false
        connectMdns = AdbMdns(
            this,
            AdbMdns.TLS_CONNECT,
            Observer<Int> { port ->
                if (port <= 0 || handled) return@Observer
                handled = true
                connectMdns?.stop()
                serviceScope.launch {
                    try {
                        waitForPortAvailable(port)
                        val key = try {
                            AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
                        } catch (e: Throwable) {
                            Timber.e("failed to load AdbKey for auto-grant", e)
                            null
                        }
                        if (key != null) {
                            runCatching {
                                AdbClient("127.0.0.1", port, key).use { client ->
                                    client.connect()
                                    client.command("shell:pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
                                }
                                Timber.tag(tag).i("WRITE_SECURE_SETTINGS auto-granted via ADB")
                            }.onFailure {
                                // Grant failure is non-fatal: the service can still start;
                                // only the next "auto-enable wireless debugging" step is lost.
                                Timber.tag(tag).w(it, "Auto-grant WRITE_SECURE_SETTINGS failed; continuing")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag(tag).w(e, "Auto-grant/connect failed; falling back to notification")
                        showPairingSucceededNotification()
                        return@launch
                    }
                    // Hand off at broadcast time: if a starter screen is alive right now it
                    // received the broadcast and continues to service startup — stop the
                    // service and clear the notification. Otherwise (pairing from the home
                    // screen without a starter) launch the starter screen ourselves.
                    sendBroadcast(
                        Intent(ACTION_PAIRING_SUCCEEDED)
                            .setPackage(packageName)
                            .putExtra(EXTRA_PORT, port)
                            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    )
                    if (StarterActivity.isActive) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        navigateToStarter(port)
                    }
                }
            }
        )
        connectMdns?.start()
        // Timeout fallback: if the connect port isn't discovered within 8s, keep the classic
        // "pairing succeeded" notification with the start button instead of hanging forever.
        serviceScope.launch {
            delay(8000)
            if (!handled) {
                handled = true
                connectMdns?.stop()
                showPairingSucceededNotification()
            }
        }
    }

    /** Waits (up to ~6s) until the connect port accepts TCP connections. */
    private suspend fun waitForPortAvailable(port: Int) {
        val maxWaitMs = 6000L
        val intervalMs = 200L
        var elapsed = 0L
        while (elapsed < maxWaitMs) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return
            } catch (_: Exception) {
                delay(intervalMs)
                elapsed += intervalMs
            }
        }
    }

    /** Launches the wireless-ADB starter screen for the given port, then ends this service. */
    private fun navigateToStarter(port: Int) {
        val intent = Intent(this, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_PORT, port)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Original "pairing succeeded" notification with the start button (fallback path). */
    private fun showPairingSucceededNotification() {
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setColor(getColor(R.color.notification))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentTitle(getString(R.string.notification_adb_pairing_succeed_title))
                .setContentText(getString(R.string.notification_adb_pairing_succeed_text))
                .apply {
                    setContentIntent(launchPendingIntent)
                    addAction(startNotificationAction)
                    setAutoCancel(true)
                }
                .build()
        )
        stopSelf()
    }

    private val launchIntent by unsafeLazy {
        Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
    }

    private val launchPendingIntent by unsafeLazy {
        PendingIntent.getActivity(
            this, launchRequestId, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private val startNotificationAction by unsafeLazy {
        val startIntent = Intent(launchIntent)
            .putExtra(HomeActivity.EXTRA_START_SERVICE_VIA_WADB, true)

        val pendingIntent = PendingIntent.getActivity(
            this, startRequestId, startIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Notification.Action.Builder(
            null,
            getString(R.string.home_root_button_start),
            pendingIntent
        )
            .build()
    }

    private val stopNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            stopRequestId,
            stopIntent(this),
            // FLAG_IMMUTABLE has existed since API 23; there's no reason to leave these mutable
            // pre-S like the >= S gate previously did.
            PendingIntent.FLAG_IMMUTABLE
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_stop_searching),
            pendingIntent
        )
            .build()
    }

    private val retryNotificationAction by unsafeLazy {
        val pendingIntent = PendingIntent.getService(
            this,
            retryRequestId,
            startIntent(this),
            // FLAG_IMMUTABLE has existed since API 23; there's no reason to leave these mutable
            // pre-S like the >= S gate previously did.
            PendingIntent.FLAG_IMMUTABLE
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_retry),
            pendingIntent
        )
            .build()
    }

    private val replyNotificationAction by unsafeLazy {
        val remoteInput = RemoteInput.Builder(remoteInputResultKey).run {
            setLabel(getString(R.string.dialog_adb_pairing_paring_code))
            build()
        }

        val pendingIntent = PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, -1),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        Notification.Action.Builder(
            null,
            getString(R.string.notification_adb_pairing_input_paring_code),
            pendingIntent
        )
            .addRemoteInput(remoteInput)
            .build()
    }

    private fun replyNotificationAction(port: Int): Notification.Action {
        // Ensure pending intent is created
        val action = replyNotificationAction

        PendingIntent.getForegroundService(
            this,
            replyRequestId,
            replyIntent(this, port),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else
                PendingIntent.FLAG_UPDATE_CURRENT
        )

        return action
    }

    private val searchingNotification by unsafeLazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(getString(R.string.notification_adb_pairing_searching_for_service_title))
            .addAction(stopNotificationAction)
            .build()
    }

    private fun createInputNotification(port: Int): Notification {
        if (ShizukuSettings.getLegacyPairing()) {
            val dialogIntent = Intent(this, AdbPairingDialogActivity::class.java)
                .putExtra(portKey, port)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val contentIntent = PendingIntent.getActivity(
                this, dialogRequestId + port, dialogIntent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(this, NOTIFICATION_CHANNEL)
                .setColor(getColor(R.color.notification))
                .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
                .setContentText(getString(R.string.notification_adb_pairing_input_paring_code))
                .setSmallIcon(R.drawable.ic_notification_icon)
                .setContentIntent(contentIntent)
                .setAutoCancel(false)
                .build()
        }
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_service_found_title))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .addAction(replyNotificationAction(port))
            .build()
    }

    private val workingNotification by unsafeLazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_working_title))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .build()
    }

    /** Shown right after pairing succeeds, while the connect port is being discovered. */
    private val startingNotification by unsafeLazy {
        Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setColor(getColor(R.color.notification))
            .setContentTitle(getString(R.string.notification_adb_pairing_starting_title))
            .setContentText(getString(R.string.notification_adb_pairing_starting_text))
            .setSmallIcon(R.drawable.ic_notification_icon)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
