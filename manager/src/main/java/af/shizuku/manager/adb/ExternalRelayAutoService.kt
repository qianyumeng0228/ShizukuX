package af.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.settings.BreventRelayManager
import af.shizuku.manager.settings.SceneRelayManager

/**
 * Accessibility-based auto-activation for external relay apps (Scene / Brevent).
 *
 * When the "auto authorize" toggle on the external relay screen is on, this service watches:
 *  - Scene (com.omarea.vtools): when the "please run this ADB command" dialog appears (the
 *    ADB-mode code page), it runs the Scene activation chain automatically — the user just taps
 *    Scene's ADB-authorize button and the relay completes by itself.
 *  - Brevent (me.piebridge.brevent): when the app is opened, it waits for Brevent to write
 *    /data/local/tmp/brevent.sh (it only does so once the app has been opened), then runs the
 *    activation chain automatically.
 *
 * Results are surfaced as toasts (silent mode) since this service has no window to host a dialog.
 */
class ExternalRelayAutoService : AccessibilityService() {

    companion object {
        private const val TAG = "ExternalRelayAuto"
        private const val SCENE_PACKAGE = "com.omarea.vtools"
        private const val BREVENT_PACKAGE = "me.piebridge.brevent"

        /** Debounce: never trigger the same app twice within this window. */
        private const val DEBOUNCE_MS = 15_000L

        /** How long to wait for Brevent to write its activation script after the app opens. */
        private const val BREVENT_SCRIPT_WAIT_MS = 4_000L

        private val isRunning = AtomicBoolean(false)

        @JvmStatic
        fun isRunning(): Boolean = isRunning.get()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastSceneTriggerMs = 0L

    @Volatile
    private var lastBreventTriggerMs = 0L

    /** One-shot flags: after a successful auto-activation the same app is not re-triggered for
     *  the rest of this service session (Brevent's window keeps emitting events while it is
     *  foreground, which would otherwise re-fire the toast every debounce window). Reset on
     *  service reconnect. */
    @Volatile
    private var breventTriggeredOnce = false

    @Volatile
    private var sceneTriggeredOnce = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning.set(true)
        breventTriggeredOnce = false
        sceneTriggeredOnce = false
        Timber.tag(TAG).d("External relay auto-activation service connected")
        logLine("onServiceConnected")
    }

    override fun onDestroy() {
        isRunning.set(false)
        serviceScope.cancel()
        logLine("onDestroy")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!ShizukuSettings.getExternalRelayAuto()) return
        if (event?.packageName == null) return

        val pkg = event.packageName.toString()
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                when (pkg) {
                    SCENE_PACKAGE -> handleSceneWindow()
                    BREVENT_PACKAGE -> handleBreventWindow()
                }
            }
        }
    }

    /** Diagnostic file log — Timber is not planted in release builds, so trace the service
     *  lifecycle and triggers to an app-private file for verification. */
    private fun logLine(msg: String) {
        try {
            val line = "[${System.currentTimeMillis()}] $msg\n".toByteArray(Charsets.UTF_8)
            java.io.FileOutputStream(java.io.File(applicationContext.filesDir, "external_relay_auto.log"), true)
                .use { it.write(line) }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        // No-op.
    }

    /**
     * Scene: trigger only when the ADB-mode code dialog is actually on screen (its text mentions
     * the up.sh command). Opening Scene normally must not fire activation. After one successful
     * activation the flag is set and the dialog (which stays up until the user acts) no longer
     * re-fires.
     */
    private fun handleSceneWindow() {
        if (sceneTriggeredOnce) return
        val now = System.currentTimeMillis()
        if (now - lastSceneTriggerMs < DEBOUNCE_MS) return

        val root = rootInActiveWindow ?: return
        val text = collectText(root)
        val isAdbCodePage = text.contains("请通过ADB") || text.contains("up.sh") || text.contains("adb shell sh")
        if (!isAdbCodePage) return

        lastSceneTriggerMs = now
        Timber.tag(TAG).i("Scene ADB code page detected; auto-activating")
        logLine("SCENE detected -> auto-activate")
        Toast.makeText(applicationContext, R.string.external_relay_auto_scene_detected, Toast.LENGTH_SHORT).show()
        serviceScope.launch {
            SceneRelayManager.startSceneAdbActivation(applicationContext, this, silent = true)
            // Confirm the daemon actually went resident before latching the one-shot flag.
            delay(2000)
            if (SceneRelayManager.queryDaemonPid().isNotEmpty()) {
                sceneTriggeredOnce = true
            }
        }
    }

    /**
     * Brevent: when the app opens, wait a moment for it to write its script, then run the
     * activation chain (which itself skips if the daemon is already resident). One-shot: once
     * the daemon is resident the flag latches and the constant window events Brevent emits while
     * foreground no longer re-fire the toast.
     */
    private fun handleBreventWindow() {
        if (breventTriggeredOnce) return
        val now = System.currentTimeMillis()
        if (now - lastBreventTriggerMs < DEBOUNCE_MS) return
        lastBreventTriggerMs = now

        serviceScope.launch {
            toastOnMain(R.string.external_relay_auto_brevent_detected)
            delay(BREVENT_SCRIPT_WAIT_MS)
            Timber.tag(TAG).i("Brevent opened; auto-activating")
            logLine("BREVENT detected -> auto-activate")
            BreventRelayManager.activateBrevent(applicationContext, this, silent = true)
            // Confirm the daemon actually went resident before latching the one-shot flag.
            delay(2000)
            if (BreventRelayManager.queryDaemonPid().isNotEmpty()) {
                breventTriggeredOnce = true
            }
        }
    }

    /** Toast safe to call from any thread (this service's IO coroutines included). */
    private fun toastOnMain(resId: Int, length: Int = Toast.LENGTH_SHORT) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, resId, length).show()
        }
    }

    /** Bounded text collection from a node tree; caps length so giant lists don't stall us. */
    private fun collectText(root: AccessibilityNodeInfo, budget: Int = 2048): String {
        val sb = StringBuilder()
        try {
            collectTextInternal(root, sb, budget)
        } finally {
            // rootInActiveWindow() nodes are caller-owned; recycle to avoid leaking the parcel.
            root.recycle()
        }
        return sb.toString()
    }

    private fun collectTextInternal(node: AccessibilityNodeInfo, sb: StringBuilder, budget: Int) {
        if (sb.length >= budget) return
        node.text?.let { if (it.isNotEmpty() && sb.length < budget) sb.append(it).append('\n') }
        node.contentDescription?.let { if (it.isNotEmpty() && sb.length < budget) sb.append(it).append('\n') }
        if (sb.length >= budget) return
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextInternal(child, sb, budget)
            child.recycle()
        }
    }
}
