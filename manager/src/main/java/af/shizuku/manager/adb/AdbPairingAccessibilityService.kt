package af.shizuku.manager.adb
import af.shizuku.manager.R

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.provider.Settings
import timber.log.Timber
import android.content.ActivityNotFoundException
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
import java.net.ConnectException
import io.sentry.Sentry
import io.sentry.Breadcrumb
import io.sentry.SentryLevel
import java.util.concurrent.atomic.AtomicBoolean

class AdbPairingAccessibilityService : AccessibilityService() {

    var port: Int? = null
    var password: String? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True once a pairing pop-up (IP:port) has been seen; starts the 60s completion budget. */
    private val timeoutScheduled = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()

        Sentry.addBreadcrumb(Breadcrumb("ADB Pairing Accessibility Service connected").apply {
            category = "adb.pairing"
        })

        val isSamsung = EnvironmentUtils.isSamsung()
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
            "Event type=%d source=%s text=%s port=%s password=%s",
            event.eventType, event.className, source.text, port, password?.let { "******" }
        )

        // Pass 1: find the pairing pop-up — an IP:port somewhere in the window tree.
        // Pass 2: only after a port is known, accept a 6-digit code from the same window.
        // A single pass can miss the code when the code node precedes the IP:port node
        // and the pop-up only ever fires one event.
        // Pass 1: find the pairing pop-up — an IP:port somewhere in the window tree.
        // Pass 2: only after a port is known, accept a 6-digit code from the same window.
        // A single pass can miss the code when the code node precedes the IP:port node
        // and the pop-up only ever fires one event. The pop-up always fires a
        // TYPE_WINDOW_STATE_CHANGED whose source is the window root, so walking the
        // source subtree (no getRoot(), which needs a newer API level) covers it.
        if (port == null) {
            findPortInNode(source)
        }
        if (port != null && password == null) {
            findPasswordInNode(source)
        }

        val currentPort = port
        val currentPassword = password
        if (currentPort != null && currentPassword != null) {
            val portValue = currentPort
            val passwordValue = currentPassword

            var toastMsg = getString(R.string.notification_adb_pairing_failed_title)

            serviceScope.launch {
                val host = "127.0.0.1"

                val key = try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizukux")
                } catch (e: Throwable) {
                    Timber.tag("AdbAccessibility").e(e, "Failed to load AdbKey")
                    Sentry.captureException(e)
                    toastMsg = getString(R.string.adb_error_key_store)
                    return@launch
                }

                AdbPairingClient(host, portValue, passwordValue, key).runCatching {
                    start()
                }.onFailure {
                    Timber.tag("AdbAccessibility").e(it, "Pairing client failed")
                    when (it) {
                        is ConnectException -> toastMsg = getString(R.string.cannot_connect_port)
                        is AdbInvalidPairingCodeException -> toastMsg = getString(R.string.paring_code_is_wrong)
                        is AdbKeyException -> toastMsg = getString(R.string.adb_error_key_store)
                        else -> Sentry.captureException(it)
                    }
                }.onSuccess {
                    if (it) {
                        Sentry.addBreadcrumb(Breadcrumb("Pairing client succeeded").apply {
                            category = "adb.pairing"
                        })
                        toastMsg = "${getString(R.string.notification_adb_pairing_succeed_title)}. ${getString(R.string.notification_adb_pairing_succeed_text)}"

                        val intent = Intent(this@AdbPairingAccessibilityService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                    } else {
                        Sentry.addBreadcrumb(Breadcrumb("Pairing client returned false").apply {
                            category = "adb.pairing"
                            level = SentryLevel.WARNING
                        })
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdbPairingAccessibilityService, toastMsg, Toast.LENGTH_LONG).show()
                }
                disableSelf()
            }
        }
    }

    /**
     * Starts the 60-second completion budget the first time a pairing pop-up (an IP:port)
     * shows up on screen. The service then disables itself if pairing didn't finish in time,
     * so it never lingers in the background after the user gave up.
     */
    private fun scheduleTimeoutIfNeeded() {
        if (timeoutScheduled.compareAndSet(false, true)) {
            serviceScope.launch(Dispatchers.Main) {
                delay(60_000)
                if (port == null || password == null) {
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

    private fun findPortInNode(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 10) return
        if (port != null) return
        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty()) {
            val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")
            ipPortRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let {
                port = it
                Timber.tag("AdbAccessibility").i("Pairing port found: %d", it)
                Sentry.addBreadcrumb(Breadcrumb("Pairing port found via standard regex").apply {
                    category = "adb.pairing"
                })
                scheduleTimeoutIfNeeded()
                return
            }
            // Samsung specific: sometimes the port is in a different view or has specific labels
            if (text.contains("Port", ignoreCase = true)) {
                Regex("""\d{5}""").find(text)?.value?.toIntOrNull()?.let {
                    port = it
                    Timber.tag("AdbAccessibility").i("Pairing port found via Samsung fallback: %d", it)
                    Sentry.addBreadcrumb(Breadcrumb("Pairing port found via Samsung fallback").apply {
                        category = "adb.pairing"
                    })
                    scheduleTimeoutIfNeeded()
                    return
                }
            }
        }
        for (i in 0 until node.childCount) {
            findPortInNode(node.getChild(i), depth + 1)
        }
    }

    private fun findPasswordInNode(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 10) return
        if (password != null) return
        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty()) {
            val passwordRegex = Regex("""\d{6}""")
            passwordRegex.find(text)?.value?.let {
                password = it
                Timber.tag("AdbAccessibility").i("Pairing password found: %s", it)
                Sentry.addBreadcrumb(Breadcrumb("Pairing password found").apply {
                    category = "adb.pairing"
                })
                return
            }
        }
        for (i in 0 until node.childCount) {
            findPasswordInNode(node.getChild(i), depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

}
