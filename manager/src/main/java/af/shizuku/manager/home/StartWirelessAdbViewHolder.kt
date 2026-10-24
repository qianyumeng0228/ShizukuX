package af.shizuku.manager.home

import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.Helps
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbArm
import af.shizuku.manager.adb.AdbPairingTutorialActivity
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartWirelessAdbBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.home.showAccessibilityDialog
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.receiver.NotifCancelReceiver
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.IconStyleHelper
import rikka.core.content.asActivity
import rikka.core.util.ClipboardUtils
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import com.airbnb.mvrx.withState
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import android.widget.Toast

class StartWirelessAdbViewHolder(
    private val binding: HomeStartWirelessAdbBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
    private val homeModel: HomeViewModel
) : BaseViewHolder<Any?>(containerBinding.root) {

    companion object {
        /**
         * Set when the one-tap flow asked the user to enable the pairing assistant and the
         * assistant could not be enabled directly (no WRITE_SECURE_SETTINGS). The user goes to
         * system accessibility settings; when they return home and the assistant is now on,
         * HomeActivity.onResume() consumes this flag and auto-starts the one-tap flow so the
         * user does not have to tap "一键启动" a second time (k2013 one-tap flow-fix).
         */
        @JvmStatic
        var pendingOneTapAfterAssistant = false

        fun creator(scope: CoroutineScope, homeModel: HomeViewModel): Creator<Any> {
            return Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeStartWirelessAdbBinding.inflate(inflater, outer.cardContent, true)
                StartWirelessAdbViewHolder(inner, outer, scope, homeModel)
            }
        }

        /** Launch the one-tap flow without a shared-element transition (used from HomeActivity.onResume). */
        fun launchOneTapDirect(context: android.content.Context) {
            val intent = android.content.Intent(context, af.shizuku.manager.starter.StarterActivity::class.java).apply {
                putExtra(af.shizuku.manager.starter.StarterActivity.EXTRA_AUTO_PAIRING, true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun start(context: android.content.Context, scope: CoroutineScope, discoveredPort: Int = -1) {
            // Always route through the step-by-step starter screen: it detects the port,
            // enables wireless debugging, pairs and starts the service by itself.
            val intent = android.content.Intent(context, StarterActivity::class.java).apply {
                if (discoveredPort in 1..65535) putExtra(StarterActivity.EXTRA_PORT, discoveredPort)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val originalIcon = binding.icon.drawable

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        binding.button1.setOnClickListener { v: View ->
            // Don't stack a second starter screen on top of an existing one; the new
            // step-by-step screen is safe to re-enter at any time (it re-checks state itself).
            // A stale STARTING state from a previously failed start must NOT block entry —
            // that deadlocked the button with "Shizuku is already starting" forever.
            if (StarterActivity.isActive) {
                return@setOnClickListener
            }

            // 4.1 merged "start service" card: on rooted devices (or Samsung system-uid
            // escalation), the primary button routes through the root starter instead of the
            // wireless-debugging flow. This replaces the former standalone StartRoot card.
            val isRootPath = EnvironmentUtils.isRooted() || ShizukuSettings.isSamsungSystemUidEscalationEnabled()
            if (isRootPath) {
                val rootIntent = Intent(context, StarterActivity::class.java).apply {
                    if (ShizukuSettings.isSamsungSystemUidEscalationEnabled() && !EnvironmentUtils.isRooted()) {
                        putExtra(StarterActivity.EXTRA_IS_SYSTEM, true)
                    } else {
                        putExtra(StarterActivity.EXTRA_IS_ROOT, true)
                    }
                }
                val rootActivity = context.asActivity<android.app.Activity>()
                if (rootActivity != null) {
                    rootActivity.startWithSceneTransition(rootIntent, binding.icon, "icon_root")
                } else {
                    context.startActivity(rootIntent)
                }
                return@setOnClickListener
            }

            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))

            // 「设备内 ADB over TCP」（on_device_adb_tcp，传统明文 TCP 模式）与无线调试
            // （adb_wifi_enabled，TLS）互斥。其残留的 service.adb.tcp.port 会让 adbd 停在
            // 明文 5555 模式，而下面读到的 sysPropPort=5555 会把一键启动引向连不上的明文
            // 端口（明文 AUTH 需要 adb_keys 白名单授权，无线调试配对 key 不通用），直接
            // 导致激活失败。先清理它并让 adbd 回到无线调试状态，再继续一键启动流程。
            if (EnvironmentUtils.getAdbTcpPort() in 1..65535) {
                scope.launch(Dispatchers.IO) {
                    runCatching { af.shizuku.manager.service.AdbProxyService.disableAdbTcp() }
                }
            }

            // One-tap arm through the AdbArm choke point: WRITE_SECURE_SETTINGS writes
            // ADB_ENABLED + adb_allowed_connection_time + adb_wifi_enabled; Device Owner
            // (dhizuku) writes the two allowlisted ADB keys as fallback.
            AdbArm.arm(context)

            // NOTE: no longer gate on Settings.Global.ADB_ENABLED (USB debugging). Wireless
            // debugging (Android 11+ TLS) works with USB debugging off; gating on it made the
            // one-tap flow bounce users to the "enable USB debugging" dialog for no reason.

            val sysPropPort = EnvironmentUtils.getAdbTcpPort()
            val discoveredPort = withState(homeModel) { it.discoveredAdbPort }
            val tcpPort = if (sysPropPort in 1..65535) sysPropPort else discoveredPort
            val lastPort = ShizukuSettings.getLastPort()
            val validTcpPort = when {
                tcpPort in 1..65535 -> tcpPort
                lastPort in 1..65535 -> lastPort
                else -> -1
            }

            if (validTcpPort <= 0 && !EnvironmentUtils.isTlsSupported()) {
                // Pre-Android-11 path: classic ADB-over-TCP needs USB debugging enabled.
                if (!EnvironmentUtils.isAdbEnabled()) {
                    WadbEnableUsbDebuggingDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
                    return@setOnClickListener
                }
                WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            } else if (validTcpPort <= 0) {
                // Android 11+ wireless debugging: no known port yet — launch the new
                // step-by-step starter screen, which detects the port, enables wireless
                // debugging, pairs and starts the service automatically (one-tap flow).
                val intent = Intent(context, StarterActivity::class.java)
                val activity = context.asActivity<android.app.Activity>()
                if (activity != null) {
                    activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
                } else {
                    context.startActivity(intent)
                }
            } else {
                // Known port (TCP mode on or off): the starter screen handles pairing itself.
                val intent = Intent(context, StarterActivity::class.java).apply {
                    putExtra(StarterActivity.EXTRA_PORT, validTcpPort)
                }
                val activity = context.asActivity<android.app.Activity>()
                if (activity != null) {
                    activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
                } else {
                    context.startActivity(intent)
                }
            }
        }

        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@StartWirelessAdbViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }


        if (EnvironmentUtils.isTlsSupported()) {
            binding.button3.setOnClickListener { v: View ->
                CustomTabsHelper.launchUrlOrCopy(v.context, Helps.ADB_ANDROID11.get())
            }
            binding.button2.setOnClickListener { v: View ->
                onPairClicked(v.context)
            }
            binding.button4.setOnClickListener { v: View ->
                onOneTapClicked(v.context)
            }
            binding.text1.movementMethod = LinkMovementMethod.getInstance()
            binding.text1.text = context.getString(R.string.home_wireless_adb_description)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        } else {
            binding.text1.text = context.getString(R.string.home_wireless_adb_description_pre_11)
                .toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
            binding.button2.isVisible = false
            binding.button3.isVisible = false
            binding.button4.isVisible = false
        }

        // 4.1 merged card: secondary "PC ADB command" viewer, migrated from the former
        // StartAdb card. Visibility is controlled in onBind() (pre-11 always; Android 11+ only
        // when the user explicitly enabled show_start_adb_home).
        binding.button5.setOnClickListener { v: View ->
            val activity = v.context.asActivity<android.app.Activity>() ?: return@setOnClickListener
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.home_adb_button_view_command)
                .setMessage(
                    HtmlCompat.fromHtml(
                        activity.getString(
                            R.string.home_adb_dialog_view_command_message,
                            Starter.adbCommand
                        )
                    )
                )
                .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                    if (ClipboardUtils.put(activity, Starter.adbCommand)) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.toast_copied_to_clipboard),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.home_adb_dialog_view_command_button_send) { _, _ ->
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, Starter.adbCommand)
                    }
                    activity.startActivity(
                        Intent.createChooser(
                            sendIntent,
                            activity.getString(R.string.home_adb_dialog_view_command_button_send)
                        )
                    )
                }
                .show()
        }
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_start_wireless_adb")

        // 4.1 merged card: primary button label toggles Start/Restart based on whether the
        // service is already running via root (data == rootRestart, passed from HomeAdapter).
        val rootRestart = data == true
        binding.button1.text = context.getString(
            if (rootRestart) R.string.home_root_button_restart else R.string.home_root_button_start
        )

        // Description: on rooted devices show the root/Sui description; on non-rooted devices
        // keep the wireless (or pre-11) description set in init().
        val isRootPath = EnvironmentUtils.isRooted() || ShizukuSettings.isSamsungSystemUidEscalationEnabled()
        if (isRootPath) {
            binding.text1.movementMethod = LinkMovementMethod.getInstance()
            binding.text1.text = context.getString(
                R.string.home_root_description,
                "<b><a href=\"${Helps.SUI.get()}\">Sui</a></b>",
                "Sui"
            ).toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
        }

        // Secondary "PC ADB command" viewer: pre-Android-11 devices need it (the old StartAdb
        // card's sole purpose); also surfaced when the user explicitly enabled show_start_adb_home.
        binding.button5.isVisible = !EnvironmentUtils.isTlsSupported() || ShizukuSettings.showStartAdbHome()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onPairClicked(context: Context) {
        // MaterialAlertDialogBuilder requires an Activity theme (ThemeEnforcement checks
        // isLightTheme); v.context can be a theme-wrapped context under MaterialActivity.
        // Resolve the host Activity first — same fix as AccessibilityDialogHelper (k2013).
        val activity = context.asActivity<android.app.Activity>() ?: return
        if (EnvironmentUtils.isTelevision()) {
            activity.showAccessibilityDialog()
            return
        }
        // Ask once whether to enable the auto-pairing assistant along with this flow.
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.dialog_enable_auto_pairing_title)
            .setMessage(R.string.dialog_enable_auto_pairing_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                // Turn on the pairing assistant (accessibility service) first, then start the
                // normal pairing flow. Best-effort: if it cannot be enabled directly the
                // accessibility dialog guides the user through it. runCatching is belt-and-
                // suspenders on top of enablePairingAssistant()'s own permission guard — a
                // SecurityException here used to crash the app (点击"配对"闪退, k2010).
                val ctx = context.applicationContext
                if (!runCatching { ctx.enablePairingAssistant() }.getOrDefault(false)) {
                    // Cannot enable directly (usually no WRITE_SECURE_SETTINGS). Show the
                    // accessibility guidance dialog ONLY — do NOT also launch the full-screen
                    // pairing tutorial, otherwise two overlapping guides stack and the
                    // tutorial covers the dialog (k2013 flow-fix). Once the user finishes
                    // enabling/reading the steps they can tap "配对" again to proceed.
                    // Dialog must be built with the Activity context: the application context
                    // has no AppCompat theme and MaterialAlertDialogBuilder crashes with
                    // "requires Theme.AppCompat" on it (k2012 一键启动→启用 闪退).
                    context.showAccessibilityDialog()
                } else {
                    launchPairingFlow(context)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                // User opted out: proceed with the original pairing flow unchanged.
                launchPairingFlow(context)
            }
            .show()
    }

    private fun launchPairingFlow(context: Context) {
        // AdbPairingTutorialActivity provides a dedicated pairing flow: it starts the pairing
        // service, shows step-by-step instructions, handles notification permission, and
        // auto-dismisses once Shizuku is running.
        val activity = context.asActivity<FragmentActivity>() ?: return
        val intent = Intent(context, AdbPairingTutorialActivity::class.java)
        activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
    }

    /**
     * One-tap start: everything happens automatically. Requires the pairing assistant
     * (accessibility service) so the pairing code is read and entered without touching
     * the screen; if it is off, ask and enable it first.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun onOneTapClicked(context: Context) {
        // Same Activity-context guard as onPairClicked — MaterialAlertDialogBuilder needs
        // an Activity theme, v.context may be theme-wrapped under MaterialActivity (k2012
        // Theme.AppCompat crash class).
        val activity = context.asActivity<android.app.Activity>() ?: return
        if (EnvironmentUtils.isTelevision()) {
            activity.showAccessibilityDialog()
            return
        }
        val ctx = context.applicationContext
        if (!ctx.isPairingAssistantEnabled()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_one_tap_requires_assistant_title)
                .setMessage(R.string.dialog_one_tap_requires_assistant_message)
                .setPositiveButton(R.string.enable) { _, _ ->
                    if (!ctx.enablePairingAssistant()) {
                        // Same as the pairing flow: the accessibility dialog must use the
                        // Activity context, not the application context (theme crash, k2012).
                        context.showAccessibilityDialog()
                        // User will enable the assistant in system accessibility settings; when
                        // they come back home the flow resumes automatically (k2013 one-tap
                        // flow-fix) — no second tap needed.
                        pendingOneTapAfterAssistant = true
                    } else {
                        // Give the accessibility service a moment to bind its receiver before
                        // entering the one-tap flow, otherwise the request broadcast can drop.
                        binding.root.postDelayed({ launchOneTap(context) }, 1200)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        launchOneTap(context)
    }

    private fun launchOneTap(context: Context) {
        val intent = Intent(context, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_AUTO_PAIRING, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = context.asActivity<android.app.Activity>()
        if (activity != null) {
            activity.startWithSceneTransition(intent, binding.icon, "icon_wireless_adb")
        } else {
            context.startActivity(intent)
        }
    }
}
