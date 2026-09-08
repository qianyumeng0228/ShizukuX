package af.shizuku.manager.adb
import af.shizuku.manager.R

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.MainActivity
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.PreferenceAdbKeyStore
import af.shizuku.manager.adb.AdbKey
import af.shizuku.manager.adb.AdbPairingClient
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.utils.EnvironmentUtils
import io.sentry.Sentry
import io.sentry.Breadcrumb
import io.sentry.SentryLevel
import java.util.concurrent.atomic.AtomicBoolean

class AdbPairingAccessibilityService : AccessibilityService() {

    companion object {
        /** Broadcast from StarterActivity: start the fully-automatic pairing flow. */
        const val ACTION_AUTO_PAIRING = "af.shizuku.manager.action.AUTO_PAIRING"
        /** Broadcast back when the automatic flow could not complete; caller falls back to manual. */
        const val ACTION_AUTO_PAIRING_FAILED = "af.shizuku.manager.action.AUTO_PAIRING_FAILED"
        /** Broadcast from StarterActivity: automatically enable the wireless-debugging switch. */
        const val ACTION_AUTO_ENABLE_WIRELESS = "af.shizuku.manager.action.AUTO_ENABLE_WIRELESS"
        /** Broadcast back when wireless debugging was enabled by the assistant. */
        const val ACTION_AUTO_WIRELESS_ENABLED = "af.shizuku.manager.action.AUTO_WIRELESS_ENABLED"
        /** Broadcast back when enabling failed; caller falls back to manual. */
        const val ACTION_AUTO_WIRELESS_FAILED = "af.shizuku.manager.action.AUTO_WIRELESS_FAILED"
        private const val AUTO_PAIRING_TIMEOUT_MS = 90_000L
    }

    @Volatile
    var port: Int? = null
    @Volatile
    var password: String? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Accessibility window id the current candidates were found in. */
    @Volatile
    private var candidateWindowId = -1

    /** True once a pairing pop-up (port AND code) has been seen; starts the 60s budget. */
    private val timeoutScheduled = AtomicBoolean(false)

    /** Bumped on every window switch; invalidates any in-flight timeout coroutine. */
    @Volatile
    private var timeoutGeneration = 0

    /** Active (one-tap) mode: navigate to wireless debugging, click the pairing button for us. */
    @Volatile
    private var autoPairRequested = false

    /** True once the "pair with pairing code" button has been clicked in active mode. */
    @Volatile
    private var autoPairClicked = false

    /** Bumped on every auto-pairing request; invalidates any in-flight timeout coroutine. */
    @Volatile
    private var autoPairTimeoutGeneration = 0

    /** Active mode: automatically flip the wireless-debugging switch on the dev-options page. */
    @Volatile
    private var autoWirelessRequested = false

    /** True once the wireless-debugging switch has been clicked in active mode. */
    @Volatile
    private var autoWirelessClicked = false

    /** Bumped on every auto-wireless request; invalidates any in-flight timeout coroutine. */
    @Volatile
    private var autoWirelessTimeoutGeneration = 0

    private val autoPairReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_AUTO_PAIRING -> {
                    Log.i("AdbAccessibility", "AUTO_PAIRING request received")
                    startAutoPairing()
                }
                ACTION_AUTO_ENABLE_WIRELESS -> {
                    Log.i("AdbAccessibility", "AUTO_ENABLE_WIRELESS request received")
                    startAutoEnableWireless()
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i("AdbAccessibility", "onServiceConnected")
        Sentry.addBreadcrumb(Breadcrumb("ADB Pairing Accessibility Service connected").apply {
            category = "adb.pairing"
        })

        val isTv = EnvironmentUtils.isTelevision()

        if (!EnvironmentUtils.isTlsSupported()) {
            Toast.makeText(this, getString(R.string.toast_accessibility_tv_only), Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }

        // On Samsung/TV we don't necessarily want to jump to MainActivity immediately
        // as the user might be manually navigating Developer Options.
        if (isTv) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, true)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, R.string.accessibility_service_monitoring, Toast.LENGTH_SHORT).show()
        }

        // Listen for one-tap auto-pairing / auto-enable-wireless requests (from the starter screen).
        runCatching {
            val filter = IntentFilter(ACTION_AUTO_PAIRING).apply {
                addAction(ACTION_AUTO_ENABLE_WIRELESS)
            }
            registerReceiver(
                autoPairReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onFailure {
            Timber.tag("AdbAccessibility").w(it, "Failed to register auto-pairing receiver")
        }

        // No countdown starts here: the user may need a while to walk to the wireless
        // debugging page after enabling the service. The 60s budget only starts once an
        // actual pairing pop-up (an IP:port on screen) is detected below, so an idle
        // service stays enabled instead of timing out before the user gets there.
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(autoPairReceiver) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (port != null && password != null) return
        Log.i("AdbAccessibility", "EVENT type=" + event.eventType + " win=" + event.windowId + " cls=" + event.className + " pkg=" + event.packageName)
        Timber.tag("AdbAccessibility").d(
            "Event type=%d windowId=%d source=%s",
            event.eventType, event.windowId, event.className
        )

        val source = event.source ?: run {
            Log.i("AdbAccessibility", "EVENT source null, skipped")
            return
        }

        // Window isolation: the wireless-debugging settings page shows the *connect* port
        // (e.g. 10.0.52.183:5555), which is NOT the pairing port. The pairing pop-up has its
        // own accessibility window containing both the real pairing port and the 6-digit code.
        // Whenever the focused window changes, drop any candidates found in the previous
        // window so the page's connect port can never be paired with the pop-up's code.
        val windowId = event.windowId
        if (windowId != candidateWindowId && port == null && password == null) {
            candidateWindowId = windowId
            Log.i("AdbAccessibility", "WINDOW switch -> " + windowId + " (fresh)")
            Timber.tag("AdbAccessibility").d("Window switch to %d, scanning this window only", windowId)
        } else if (windowId != candidateWindowId) {
            candidateWindowId = windowId
            port = null
            password = null
            timeoutGeneration++
            Log.i("AdbAccessibility", "WINDOW switch -> " + windowId + " (candidates cleared, port was " + port + ")")
            Timber.tag("AdbAccessibility").w("Window switched to %d, stale candidates cleared", windowId)
        }

        // Debug Samsung-specific dialog titles
        if (EnvironmentUtils.isSamsung() && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (className.contains("AlertDialog") || className.contains("Dialog")) {
                val text = source.text ?: ""
                Timber.tag("AdbAccessibility").d("Samsung Dialog detected: $text")
                Sentry.addBreadcrumb(Breadcrumb("Samsung Dialog detected").apply {
                    category = "adb.pairing"
                    setData("text", text.toString())
                })
            }
        }

        Timber.tag("AdbAccessibility").d(
            "Event type=%d windowId=%d source=%s port=%s password=%s",
            event.eventType, windowId, event.className, port, password?.let { "******" }
        )

        // Pass 1: find the pairing pop-up — an IP:port somewhere in the window tree.
        // Pass 2: only after a port is known, accept a 6-digit code from the same window.
        // A single pass can miss the code when the code node precedes the IP:port node
        // and the pop-up only ever fires one event.
        //
        // The event source may be only the *changed* node (CONTENT_CHANGED fires per-node),
        // so the scan must cover the whole window: walk the parent chain up to the window
        // root first (source.root needs a newer API level than this project compiles
        // against, the parent chain does not).
        val windowRoot = run {
            var r: android.view.accessibility.AccessibilityNodeInfo = source
            while (r.parent != null) r = r.parent
            r
        }
        // Active (one-tap) mode: once we're on the wireless-debugging page, click the
        // "pair with pairing code" button for the user.
        if (autoPairRequested && !autoPairClicked && port == null && password == null) {
            findAndClickPairButton(windowRoot)
        }
        // Active mode: flip the wireless-debugging switch on the developer-options page.
        if (autoWirelessRequested && !autoWirelessClicked) {
            clickWirelessDebuggingSwitch(windowRoot)
        }
        if (port == null) {
            findPortInNode(windowRoot)
        }
        if (port != null && password == null) {
            findPasswordInNode(windowRoot)
        }

        val currentPort = port
        val currentPassword = password
        if (currentPort != null && currentPassword != null) {
            val portValue = currentPort
            val passwordValue = currentPassword
            Log.i("AdbAccessibility", "PAIRING start host=127.0.0.1 port=" + portValue + " code=" + passwordValue)

            serviceScope.launch {
                val host = "127.0.0.1"

                val key = try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
                } catch (e: Throwable) {
                    Timber.tag("AdbAccessibility").e(e, "Failed to load AdbKey")
                    Sentry.captureException(e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AdbPairingAccessibilityService, getString(R.string.adb_error_key_store), Toast.LENGTH_LONG).show()
                    }
                    disableSelf()
                    return@launch
                }

                AdbPairingClient(host, portValue, passwordValue, key).runCatching {
                    start()
                }.onFailure {
                    Log.i("AdbAccessibility", "PAIRING failed: " + it.javaClass.simpleName + ": " + it.message)
                    Timber.tag("AdbAccessibility").w(it, "Pairing attempt failed; will retry on next event")
                    when (it) {
                        // Deterministic failures — retrying cannot help.
                        is AdbInvalidPairingCodeException, is AdbKeyException -> {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@AdbPairingAccessibilityService,
                                    if (it is AdbInvalidPairingCodeException) {
                                        getString(R.string.paring_code_is_wrong)
                                    } else {
                                        getString(R.string.adb_error_key_store)
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            if (autoPairRequested) sendAutoPairingFailed()
                            resetAutoPairing()
                            disableSelf()
                        }
                        // Transient failures (pairing server still binding, connect refused,
                        // TLS handshake race): clear the candidates so the next event rescans
                        // the same window and retries. The 60s budget (started when the code
                        // was found) still bounds this, and window switches reset it.
                        else -> {
                            port = null
                            password = null
                        }
                    }
                }.onSuccess {
                    if (it) {
                        Log.i("AdbAccessibility", "PAIRING succeeded")
                        Sentry.addBreadcrumb(Breadcrumb("Pairing client succeeded").apply {
                            category = "adb.pairing"
                        })
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@AdbPairingAccessibilityService,
                                "${getString(R.string.notification_adb_pairing_succeed_title)}. ${getString(R.string.notification_adb_pairing_succeed_text)}",
                                Toast.LENGTH_LONG
                            ).show()
                            // The auto-pair path is independent of AdbPairingService: if that
                            // service was started (home card "pairing" flow / tutorial) it may
                            // still be foreground-searching. Stop it so the "searching for
                            // pairing service" notification does not linger after success, and
                            // broadcast success so a waiting StarterActivity continues its flow.
                            runCatching {
                                stopService(Intent(this@AdbPairingAccessibilityService, AdbPairingService::class.java))
                            }
                            runCatching {
                                sendBroadcast(
                                    Intent(AdbPairingService.ACTION_PAIRING_SUCCEEDED)
                                        .setPackage(packageName)
                                        .putExtra(AdbPairingService.EXTRA_PORT, portValue)
                                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                )
                            }
                            val intent = Intent(this@AdbPairingAccessibilityService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            startActivity(intent)
                        }
                    } else {
                        Sentry.addBreadcrumb(Breadcrumb("Pairing client returned false").apply {
                            category = "adb.pairing"
                            level = SentryLevel.WARNING
                        })
                    }
                    resetAutoPairing()
                    disableSelf()
                }
            }
        }
    }

    /**
     * Starts the 60-second completion budget the first time a *code* shows up on screen
     * (i.e. a real pairing pop-up is present). The wireless-debugging settings page alone
     * shows a connect port but no code, so it never starts the clock. The service disables
     * itself if pairing didn't finish in time, so it never lingers after the user gave up.
     * A window switch bumps timeoutGeneration, which voids any in-flight timeout coroutine.
     */
    private fun scheduleTimeoutIfNeeded() {
        if (timeoutScheduled.compareAndSet(false, true)) {
            val gen = timeoutGeneration
            serviceScope.launch(Dispatchers.Main) {
                delay(60_000)
                if (timeoutGeneration == gen && (port == null || password == null)) {
                    Timber.tag("AdbAccessibility").w("Pairing discovery timed out")
                    Sentry.addBreadcrumb(Breadcrumb("Pairing discovery timed out").apply {
                        level = SentryLevel.WARNING
                    })
                    Toast.makeText(this@AdbPairingAccessibilityService, getString(R.string.toast_pairing_timeout), Toast.LENGTH_LONG).show()
                    disableSelf()
                }
            }
        }
    }

    private val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")
    private val passwordRegex = Regex("""\d{6}""")
    private val fiveDigitRegex = Regex("""\d{5}""")

    /**
     * One-tap flow entry: navigate to the wireless-debugging page and start watching for the
     * "pair with pairing code" button. The button click itself happens in [findAndClickPairButton]
     * once the page's accessibility events arrive. A global budget guards the whole flow and
     * reports [ACTION_AUTO_PAIRING_FAILED] so the starter screen can fall back to manual pairing.
     */
    private fun startAutoPairing() {
        if (port != null || password != null) return // already mid-pairing
        autoPairRequested = true
        autoPairClicked = false
        candidateWindowId = -1
        Log.i("AdbAccessibility", "AUTO_PAIRING start: navigating to wireless debugging")
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                }
            )
        }.onFailure {
            Log.i("AdbAccessibility", "AUTO_PAIRING navigation failed: " + it.javaClass.simpleName)
            autoPairRequested = false
            sendAutoPairingFailed()
        }
        autoPairTimeoutGeneration++
        val gen = autoPairTimeoutGeneration
        serviceScope.launch(Dispatchers.Main) {
            delay(AUTO_PAIRING_TIMEOUT_MS)
            if (autoPairTimeoutGeneration == gen && autoPairRequested && (port == null || password == null)) {
                Log.i("AdbAccessibility", "AUTO_PAIRING timed out")
                autoPairRequested = false
                autoPairClicked = false
                sendAutoPairingFailed()
            }
        }
    }

    private fun sendAutoPairingFailed() {
        runCatching {
            sendBroadcast(
                Intent(ACTION_AUTO_PAIRING_FAILED).setPackage(packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            )
        }
    }

    /**
     * One-tap flow entry: navigate to the developer-options wireless-debugging page and flip
     * the switch for the user. The click happens in [clickWirelessDebuggingSwitch] once the
     * page's accessibility events arrive; a 2s settle delay then reports
     * [ACTION_AUTO_WIRELESS_ENABLED] so the starter screen retries port detection.
     */
    private fun startAutoEnableWireless() {
        if (autoWirelessRequested) return
        autoWirelessRequested = true
        autoWirelessClicked = false
        Log.i("AdbAccessibility", "AUTO_ENABLE_WIRELESS start: navigating to wireless debugging")
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                }
            )
        }.onFailure {
            Log.i("AdbAccessibility", "AUTO_ENABLE_WIRELESS navigation failed: " + it.javaClass.simpleName)
            autoWirelessRequested = false
            runCatching {
                sendBroadcast(
                    Intent(ACTION_AUTO_WIRELESS_FAILED).setPackage(packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                )
            }
        }
        autoWirelessTimeoutGeneration++
        val gen = autoWirelessTimeoutGeneration
        serviceScope.launch(Dispatchers.Main) {
            delay(AUTO_PAIRING_TIMEOUT_MS)
            if (autoWirelessTimeoutGeneration == gen && autoWirelessRequested && !autoWirelessClicked) {
                Log.i("AdbAccessibility", "AUTO_ENABLE_WIRELESS timed out")
                autoWirelessRequested = false
                runCatching {
                    sendBroadcast(
                        Intent(ACTION_AUTO_WIRELESS_FAILED).setPackage(packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    )
                }
            }
        }
    }

    /**
     * Clicks the wireless-debugging switch on the developer-options page. Matches the row by
     * localized text, then walks up to a clickable ancestor (the switch row). After the click,
     * reports [ACTION_AUTO_WIRELESS_ENABLED] once adbd has had a moment to bring up the port.
     */
    private fun clickWirelessDebuggingSwitch(root: AccessibilityNodeInfo?) {
        if (root == null || autoWirelessClicked) return
        val texts = setOf("无线调试", "Wireless debugging", "無線デバッグ", "디버깅")
        val nodes = ArrayList<AccessibilityNodeInfo>()
        collectNodesByText(root, nodes, texts, 0)
        for (node in nodes) {
            var target: AccessibilityNodeInfo? = node
            var hops = 0
            while (target != null && !target.isClickable && target.parent != null && hops < 4) {
                target = target.parent
                hops++
            }
            if (target != null && target.isClickable) {
                // If the switch row reports itself already checked, wireless debugging is
                // already on — do NOT click (that would toggle it OFF). Report success instead.
                val alreadyOn = runCatching { target.isChecked }.getOrDefault(false)
                if (alreadyOn) {
                    autoWirelessClicked = true
                    Log.i("AdbAccessibility", "AUTO_ENABLE_WIRELESS already enabled; skipping click")
                } else {
                    runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    autoWirelessClicked = true
                    Log.i("AdbAccessibility", "AUTO_ENABLE_WIRELESS clicked wireless debugging switch")
                }
                val gen = autoWirelessTimeoutGeneration
                serviceScope.launch(Dispatchers.Main) {
                    delay(2000)
                    if (autoWirelessRequested && autoWirelessTimeoutGeneration == gen) {
                        autoWirelessRequested = false
                        runCatching {
                            sendBroadcast(
                                Intent(ACTION_AUTO_WIRELESS_ENABLED).setPackage(packageName).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                            )
                        }
                    }
                }
                return
            }
        }
    }

    /** Clears the active mode; call when pairing succeeded, failed deterministically or timed out. */
    private fun resetAutoPairing() {
        autoPairRequested = false
        autoPairClicked = false
    }

    /**
     * Clicks the "pair with pairing code" button on the wireless-debugging page. Matches the
     * button by localized text first, then falls back to any clickable node containing "pair"
     * / "配对" so ROM-localized strings are covered.
     */
    private fun findAndClickPairButton(root: AccessibilityNodeInfo?) {
        if (root == null || autoPairClicked) return
        val exactTexts = setOf(
            "使用配对码配对设备", "使用配对码", "配对码配对", "通过配对码配对设备",
            "Pair device with pairing code", "Pair device", "pairing code", "Pair with code"
        )
        val found = ArrayList<AccessibilityNodeInfo>()
        collectNodesByText(root, found, exactTexts, 0)
        for (node in found) {
            if (node.isClickable) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                autoPairClicked = true
                Log.i("AdbAccessibility", "AUTO_PAIRING clicked pairing button")
                return
            }
        }
        // Fallback: walk again and click any clickable node whose text mentions pairing.
        // Deliberately narrow: "pair" alone would also match "Paired devices" (已配对设备)
        // on the same page and open the wrong sub-page.
        val fallback = ArrayList<AccessibilityNodeInfo>()
        collectNodesByText(root, fallback, setOf("Pair device", "pairing code", "配对码", "Pairing"), 0)
        for (node in fallback) {
            if (node.isClickable) {
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                autoPairClicked = true
                Log.i("AdbAccessibility", "AUTO_PAIRING clicked pairing button (fallback)")
                return
            }
        }
    }

    private fun collectNodesByText(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        texts: Set<String>,
        depth: Int
    ) {
        if (node == null || depth > MAX_DEPTH) return
        val text = try {
            node.text?.toString() ?: ""
        } catch (e: Throwable) {
            ""
        }
        if (text.isNotEmpty() && texts.any { text.contains(it, ignoreCase = true) }) {
            out.add(node)
        }
        try {
            for (i in 0 until node.childCount) {
                collectNodesByText(node.getChild(i), out, texts, depth + 1)
            }
        } catch (e: Throwable) {
            // node recycled mid-traversal; the next event will rescan
        }
    }

    /** Deep-enough cap for the recursive scan; guards against pathological UI trees. */
    private val MAX_DEPTH = 20

    private fun findPortInNode(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > MAX_DEPTH) return
        if (port != null) return
        val text = try {
            node.text?.toString() ?: ""
        } catch (e: Throwable) {
            "" // node may be recycled by the framework mid-scan; skip it
        }
        if (text.isNotEmpty()) {
            ipPortRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                port = it
                Log.i("AdbAccessibility", "PORT found=" + it + " text=[" + text + "] win=" + candidateWindowId)
                Timber.tag("AdbAccessibility").i("Pairing port found: %d (window %d)", it, candidateWindowId)
                Sentry.addBreadcrumb(Breadcrumb("Pairing port found via standard regex").apply {
                    category = "adb.pairing"
                })
                return
            }
            // Samsung specific: sometimes the port is in a different view or has specific labels
            if (text.contains("Port", ignoreCase = true)) {
                fiveDigitRegex.find(text)?.value?.toIntOrNull()?.let {
                    port = it
                    Log.i("AdbAccessibility", "PORT found(samsung)=" + it + " text=[" + text + "] win=" + candidateWindowId)
                    Timber.tag("AdbAccessibility").i("Pairing port found via Samsung fallback: %d (window %d)", it, candidateWindowId)
                    Sentry.addBreadcrumb(Breadcrumb("Pairing port found via Samsung fallback").apply {
                        category = "adb.pairing"
                    })
                    return
                }
            }
        }
        try {
            for (i in 0 until node.childCount) {
                findPortInNode(node.getChild(i), depth + 1)
            }
        } catch (e: Throwable) {
            // node recycled mid-traversal; the next event will rescan
        }
    }

    private fun findPasswordInNode(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > MAX_DEPTH) return
        if (password != null) return
        val text = try {
            node.text?.toString() ?: ""
        } catch (e: Throwable) {
            ""
        }
        if (text.isNotEmpty()) {
            passwordRegex.find(text)?.value?.let {
                password = it
                Log.i("AdbAccessibility", "PASSWORD found=" + it + " text=[" + text + "] win=" + candidateWindowId)
                Timber.tag("AdbAccessibility").i("Pairing password found: %s (window %d)", it, candidateWindowId)
                Sentry.addBreadcrumb(Breadcrumb("Pairing password found").apply {
                    category = "adb.pairing"
                })
                scheduleTimeoutIfNeeded()
                return
            }
        }
        try {
            for (i in 0 until node.childCount) {
                findPasswordInNode(node.getChild(i), depth + 1)
            }
        } catch (e: Throwable) {
            // node recycled mid-traversal; the next event will rescan
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

}
