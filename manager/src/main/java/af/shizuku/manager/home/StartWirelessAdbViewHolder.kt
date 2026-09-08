package af.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import af.shizuku.manager.Helps
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.R
import af.shizuku.manager.adb.AdbPairingTutorialActivity
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartWirelessAdbBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.home.showAccessibilityDialog
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.receiver.NotifCancelReceiver
import af.shizuku.manager.starter.StarterActivity
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.IconStyleHelper
import rikka.core.content.asActivity
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator
import com.airbnb.mvrx.withState
import af.shizuku.manager.utils.MotionUtils.applySpringTouch

class StartWirelessAdbViewHolder(
    private val binding: HomeStartWirelessAdbBinding,
    private val containerBinding: HomeItemContainerBinding,
    private val scope: CoroutineScope,
    private val homeModel: HomeViewModel
) : BaseViewHolder<Any?>(containerBinding.root) {

    companion object {
        fun creator(scope: CoroutineScope, homeModel: HomeViewModel): Creator<Any> {
            return Creator { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeStartWirelessAdbBinding.inflate(inflater, outer.cardContent, true)
                StartWirelessAdbViewHolder(inner, outer, scope, homeModel)
            }
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

            context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))

            val cr = context.contentResolver
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
                Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
                Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
                // Also enable wireless debugging itself (Android 11+ adbd switch), so the
                // one-tap flow works without a manual trip to developer options.
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            }

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
                val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
                if (adbEnabled == 0) {
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
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(binding.icon, originalIcon, "home_start_wireless_adb")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun onPairClicked(context: Context) {
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
            return
        }
        // Ask once whether to enable the auto-pairing assistant along with this flow.
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.dialog_enable_auto_pairing_title)
            .setMessage(R.string.dialog_enable_auto_pairing_message)
            .setPositiveButton(R.string.enable) { _, _ ->
                // Turn on the pairing assistant (accessibility service) first, then start the
                // normal pairing flow. Best-effort: if it cannot be enabled directly the
                // accessibility dialog guides the user through it.
                val ctx = context.applicationContext
                if (!ctx.enablePairingAssistant()) {
                    ctx.showAccessibilityDialog()
                }
                launchPairingFlow(context)
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
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
            return
        }
        val ctx = context.applicationContext
        if (!ctx.isPairingAssistantEnabled()) {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_one_tap_requires_assistant_title)
                .setMessage(R.string.dialog_one_tap_requires_assistant_message)
                .setPositiveButton(R.string.enable) { _, _ ->
                    if (!ctx.enablePairingAssistant()) {
                        ctx.showAccessibilityDialog()
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
