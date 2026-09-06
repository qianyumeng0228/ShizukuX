package af.shizuku.manager.adb
import af.shizuku.manager.R

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.widget.Toast
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

    override fun onServiceConnected() {
        super.onServiceConnected()

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

        // No countdown starts here: the user may need a while to walk to the wireless
        // debugging page after enabling the service. The 60s budget only starts once an
        // actual pairing pop-up (an IP:port on screen) is detected below, so an idle
        // service stays enabled instead of timing out before the user gets there.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (port != null && password != null) return

        val source = event.source ?: return

        // Window isolation: the wireless-debugging settings page shows the *connect* port
        // (e.g. 10.0.52.183:5555), which is NOT the pairing port. The pairing pop-up has its
        // own accessibility window containing both the real pairing port and the 6-digit code.
        // Whenever the focused window changes, drop any candidates found in the previous
        // window so the page's connect port can never be paired with the pop-up's code.
        val windowId = event.windowId
        if (windowId != candidateWindowId && port == null && password == null) {
            candidateWindowId = windowId
            Timber.tag("AdbAccessibility").d("Window switch to %d, scanning this window only", windowId)
        } else if (windowId != candidateWindowId) {
            candidateWindowId = windowId
            port = null
            password = null
            timeoutGeneration++
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
                        Sentry.addBreadcrumb(Breadcrumb("Pairing client succeeded").apply {
                            category = "adb.pairing"
                        })
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@AdbPairingAccessibilityService,
                                "${getString(R.string.notification_adb_pairing_succeed_title)}. ${getString(R.string.notification_adb_pairing_succeed_text)}",
                                Toast.LENGTH_LONG
                            ).show()
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
