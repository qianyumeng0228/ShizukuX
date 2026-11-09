package af.shizuku.manager.home

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.airbnb.mvrx.MavericksView
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.withState
import com.google.android.material.snackbar.Snackbar
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbPairingService
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.management.AppsViewModel
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.SettingsHelper
import af.shizuku.manager.utils.SettingsPage
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.core.ktx.unsafeLazy
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.addItemSpacing
import rikka.recyclerview.fixEdgeEffect

/**
 * The home list used to be assembled straight inside [HomeActivity]. The single-container shell
 * hosts the same page now, so the RecyclerView, its adapter, the Mavericks observers, the Shizuku
 * listener, edit-mode drag and the back handling all had to leave the Activity and live somewhere
 * both hosts can own.
 *
 * The two Mavericks models are passed in rather than created here: only a MavericksView owner can
 * resolve them, and both hosts are one.
 */
class HomeController(
    private val host: FragmentActivity,
    private val homeModel: HomeViewModel,
    private val appsModel: AppsViewModel,
) : MavericksView {

    // MavericksView lives on the host; the controller only borrows it so the models and their
    // property observers can be resolved from here.
    override val lifecycle: androidx.lifecycle.Lifecycle get() = host.lifecycle

    var showEmptyState by mutableStateOf(false)
        private set
    var isEditMode by mutableStateOf(HomeEditMode.isActive)
        private set
    var wallpaperTheme by mutableStateOf(ShizukuSettings.getWallpaperTheme())
        private set
    /** Bumped on every resume so settings changed elsewhere are re-read by the Compose tree. */
    var resumeTick by mutableStateOf(0)
        private set

    val adapter: HomeAdapter by unsafeLazy {
        HomeAdapter(homeModel, host.lifecycleScope)
    }

    val recyclerView: RecyclerView = RecyclerView(host).apply {
        id = android.R.id.list
        clipToPadding = false
    }

    // Show the "restart after update" prompt at most once per host instance.
    private var versionSkewSnackbarShown = false

    // Tracks the service state across successive updates so the celebratory reveal only fires on a
    // real transition. Null until the first Success, so a cold start with a running server does not
    // animate for no reason.
    private var wasServiceRunning: Boolean? = null

    private var itemTouchHelper: ItemTouchHelper? = null
    private var backCallback: OnBackPressedCallback? = null
    private var attached = false

    // Home's prompts (battery optimisation, Samsung Auto Blocker, stale server) used Material
    // snackbars. The container is a plain FragmentActivity on the platform theme, where inflating
    // mtrl_layout_snackbar_include throws on `textAllCaps` — the same failure that killed the apps
    // page undo. One hand-drawn bar serves both hosts and keeps the glass look.
    private var noticeBar: android.view.View? = null
    private var noticeTimeout: Runnable? = null

    private val notifPermissionLauncher = host.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The system handles permanently-denied suppression on its own. */ }

    private val stateListener: (ShizukuStateMachine.State) -> Unit = { state ->
        when (state) {
            ShizukuStateMachine.State.RUNNING -> {
                checkServerStatus()
                appsModel.load()
                ShizukuSettings.syncAllExtraFeaturesToServer()
                maybeShowVersionSkewSnackbar()
            }
            else -> checkServerStatus()
        }
    }

    /** The Compose tree asks for the list every frame it recomposes; padding is only touched when
     *  it actually changed, because setPadding schedules a full relayout. */
    fun provider(ctx: Context, paddingValues: PaddingValues): RecyclerView {
        val density = ctx.resources.displayMetrics.density
        val top = (paddingValues.calculateTopPadding().value * density).toInt()
        val bottom = (paddingValues.calculateBottomPadding().value * density).toInt()
        if (kotlin.math.abs(recyclerView.paddingTop - top) > 1 ||
            kotlin.math.abs(recyclerView.paddingBottom - bottom) > 1
        ) {
            recyclerView.setPadding(recyclerView.paddingLeft, top, recyclerView.paddingRight, bottom)
        }
        return recyclerView
    }

    fun attach() {
        if (attached) return
        attached = true

        homeModel.reload()

        homeModel.onEach(HomeState::serviceStatus) {
            if (it is Success) {
                val status = it.invoke()
                val previouslyRunning = wasServiceRunning
                wasServiceRunning = status.isRunning

                adapter.updateData()
                ShizukuSettings.setLastLaunchMode(
                    if (status.uid == 0) ShizukuSettings.LaunchMethod.ROOT else ShizukuSettings.LaunchMethod.ADB
                )

                if (status.isRunning && previouslyRunning == false) {
                    recyclerView.post {
                        val view = recyclerView
                        if (!view.isAttachedToWindow) return@post

                        if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                            // Position 1 is the server-status card: position 0 is the wallpaper hero.
                            val statusCard = view.findViewHolderForAdapterPosition(1)?.itemView
                            val cx = view.width / 2
                            val cy = statusCard?.let { it.top + it.height / 2 } ?: 100
                            val finalRadius = Math.hypot(view.width.toDouble(), view.height.toDouble()).toFloat()

                            val interpolator = if (EnvironmentUtils.isOneUi8()) {
                                androidx.core.view.animation.PathInterpolatorCompat.create(0.22f, 1f, 0.36f, 1f)
                            } else {
                                androidx.core.view.animation.PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)
                            }

                            android.view.ViewAnimationUtils.createCircularReveal(view, cx, cy, 0f, finalRadius).apply {
                                duration = ShizukuSettings.scaledAnimationDuration(if (EnvironmentUtils.isOneUi8()) 800L else 600L)
                                this.interpolator = interpolator
                                start()
                            }
                        }

                        if (EnvironmentUtils.isOneUi8()) HapticUtils.success(view)
                    }
                }
            }
        }

        homeModel.onEach(HomeState::shouldShowBatteryOptimizationSnackbar) {
            if (it) {
                showNotice(
                    message = host.getString(R.string.snackbar_battery_optimization_home),
                    actionText = host.getString(R.string.snackbar_action_fix)
                ) {
                    // MIUI/HyperOS "no limit" does not always land in the AOSP whitelist, so the
                    // stock API keeps returning false; having visited the battery page counts as
                    // handled and stops the prompt from reappearing.
                    if (EnvironmentUtils.isXiaomi()) SettingsHelper.markBatteryOptimizationAcknowledged(host)
                    if (EnvironmentUtils.isSamsung()) {
                        SettingsPage.Samsung.DeviceCareBattery.launch(host)
                    } else {
                        SettingsHelper.requestIgnoreBatteryOptimizations(host, null)
                    }
                }
            } else {
                dismissNotice()
            }
        }
        homeModel.checkBatteryOptimization()

        // Samsung Auto Blocker check for One UI 7/8+.
        if (EnvironmentUtils.isSamsung() && EnvironmentUtils.getOneUiVersion() >= 6) {
            homeModel.onEach(HomeState::serviceStatus) {
                if (it is Success && it.invoke().isRunning == false) {
                    showNotice(
                        message = host.getString(R.string.home_samsung_auto_blocker_message),
                        actionText = host.getString(R.string.home_check_security_action),
                        autoDismissMs = 4000
                    ) { SettingsPage.Samsung.AutoBlocker.launch(host) }
                }
            }
        }

        requestNotificationPermissionIfNeeded()

        // Single column on phones (the original Shizuku look), two on tablets/landscape/DeX where
        // one column would leave most of the width empty.
        val spanCount = if (host.resources.configuration.screenWidthDp >= 600 ||
            host.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) 2 else 1
        val layoutManager = GridLayoutManager(host, spanCount)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (position == 0) spanCount else 1
        }
        recyclerView.layoutManager = layoutManager

        // Samsung DeX: give the list a sidebar feel with larger horizontal margins.
        val isDeX = EnvironmentUtils.isSamsung() && EnvironmentUtils.isDeX(host)
        val dexPadding = if (isDeX) (48 * host.resources.displayMetrics.density).toInt() else 0

        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        recyclerView.fixEdgeEffect()

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val systemBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(
                systemBars.left + dexPadding,
                v.paddingTop,
                systemBars.right + dexPadding,
                v.paddingBottom
            )
            insets
        }

        adapter.onEmptyStateChanged = { isEmpty -> showEmptyState = isEmpty }

        val cardSpacing = host.resources.getDimension(R.dimen.card_spacing)
        val marginHorizontal = host.resources.getDimension(R.dimen.margin_horizontal)
        val marginVertical = host.resources.getDimension(R.dimen.margin_vertical)
        val itemSpacing = cardSpacing / 2f
        recyclerView.addItemSpacing(top = itemSpacing, bottom = itemSpacing)
        recyclerView.addEdgeSpacing(
            top = marginVertical - itemSpacing,
            bottom = marginVertical - itemSpacing,
            left = marginHorizontal,
            right = marginHorizontal
        )

        val dragCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                return if (adapter.isDraggable(vh.adapterPosition)) {
                    makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
                } else {
                    makeMovementFlags(0, 0)
                }
            }

            override fun onMove(rv: RecyclerView, src: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                if (!adapter.isDraggable(target.adapterPosition)) return false
                adapter.moveItem(src.adapterPosition, target.adapterPosition)
                HapticUtils.tap(target.itemView)
                return true
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    adapter.isDragging = true
                    viewHolder?.itemView?.let { HapticUtils.gestureStart(it) }
                    if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                        viewHolder?.itemView?.animate()
                            ?.scaleX(1.04f)
                            ?.scaleY(1.04f)
                            ?.translationZ(16f)
                            ?.setDuration(ShizukuSettings.scaledAnimationDuration(200))
                            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                            ?.start()
                    }
                } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                    adapter.isDragging = false
                }
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                adapter.isDragging = false
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    val animator = vh.itemView.animate()
                    if (animator != null) {
                        animator.scaleX(1f)
                            .scaleY(1f)
                            .translationZ(0f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                            .start()
                    } else {
                        vh.itemView.scaleX = 1f
                        vh.itemView.scaleY = 1f
                        vh.itemView.translationZ = 0f
                    }
                }
                adapter.persistCardOrder()
                adapter.updateData()
            }
        }
        val helper = ItemTouchHelper(dragCallback)
        helper.attachToRecyclerView(recyclerView)
        itemTouchHelper = helper

        HomeEditMode.startDragCallback = { vh -> helper.startDrag(vh) }
        HomeEditMode.exit()

        // Predictive back for edit mode, with the same scaling the standalone page had.
        val back = object : OnBackPressedCallback(HomeEditMode.isActive) {
            private var backThresholdReached = false

            override fun handleOnBackProgressed(backEvent: androidx.activity.BackEventCompat) {
                val progress = backEvent.progress
                if (progress > 0.1f && !backThresholdReached) {
                    backThresholdReached = true
                    HapticUtils.gestureThreshold(recyclerView)
                } else if (progress < 0.1f) {
                    backThresholdReached = false
                }

                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    val scale = 1f - (0.08f * progress * progress)
                    recyclerView.scaleX = scale
                    recyclerView.scaleY = scale
                    recyclerView.alpha = 1f - (0.15f * progress)
                }
            }

            override fun handleOnBackPressed() {
                backThresholdReached = false
                if (HomeEditMode.isActive) {
                    HomeEditMode.exit()
                    if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                        val animator = recyclerView.animate()
                        if (animator != null) {
                            animator.scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(ShizukuSettings.scaledAnimationDuration(400))
                                .setInterpolator(androidx.core.view.animation.PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f))
                                .start()
                        } else {
                            recyclerView.scaleX = 1f
                            recyclerView.scaleY = 1f
                            recyclerView.alpha = 1f
                        }
                    }
                }
            }

            override fun handleOnBackCancelled() {
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    recyclerView.animate()
                        ?.scaleX(1f)
                        ?.scaleY(1f)
                        ?.alpha(1f)
                        ?.setDuration(ShizukuSettings.scaledAnimationDuration(300))
                        ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                        ?.start()
                } else {
                    recyclerView.scaleX = 1f
                    recyclerView.scaleY = 1f
                    recyclerView.alpha = 1f
                }
            }
        }
        host.onBackPressedDispatcher.addCallback(host, back)
        backCallback = back

        HomeEditMode.onChanged = {
            host.lifecycleScope.launch {
                delay(150)
                isEditMode = HomeEditMode.isActive
                homeModel.setEditMode(HomeEditMode.isActive)
                adapter.updateData()
                backCallback?.isEnabled = HomeEditMode.isActive
            }
        }

        ShizukuStateMachine.addListener(stateListener)
    }

    fun onResume() {
        if (!attached) return
        resumeTick++
        wallpaperTheme = ShizukuSettings.getWallpaperTheme()
        checkServerStatus()
        // Re-check battery optimisation so "no limit" set in system settings registers immediately.
        homeModel.checkBatteryOptimization()
        appsModel.load()

        // The home-card switches live in another screen, so returning from Settings must re-read
        // them. checkServerStatus() only renders when the service state changed, which left a
        // hidden card visible until the process was restarted.
        adapter.updateData()

        // One-tap flow-fix: the user tapped 一键启动, was sent to enable the pairing assistant and
        // has now returned, so continue the flow instead of asking for a second tap.
        if (StartWirelessAdbViewHolder.pendingOneTapAfterAssistant && host.isPairingAssistantEnabled()) {
            StartWirelessAdbViewHolder.pendingOneTapAfterAssistant = false
            StartWirelessAdbViewHolder.launchOneTapDirect(host)
        }
    }

    fun onPause() {
        if (!attached) return
        SnackbarHelper.dismiss()
        dismissNotice()
    }

    fun dispose() {
        if (!attached) return
        attached = false
        HomeEditMode.onChanged = null
        HomeEditMode.startDragCallback = null
        HomeEditMode.removeCardCallback = null
        ShizukuStateMachine.removeListener(stateListener)
    }

    /** Cold-start launch from the ADB pairing success notification. */
    fun handleStartViaMdnsNotification() {
        val nm = host.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AdbPairingService.NOTIFICATION_ID)
        val discoveredPort = withState(homeModel) { s -> s.discoveredAdbPort }
        StartWirelessAdbViewHolder.start(host, host.lifecycleScope, discoveredPort)
    }

    private fun checkServerStatus() {
        homeModel.reload()
    }

    private fun maybeShowVersionSkewSnackbar() {
        if (versionSkewSnackbarShown) return
        if (!ShizukuStateMachine.isServerVersionSkewed()) return
        versionSkewSnackbarShown = true
        showNotice(
            message = host.getString(R.string.snackbar_server_version_skew),
            actionText = host.getString(R.string.snackbar_action_restart)
        ) { ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPING) }
    }

    private fun dp(value: Int): Int = (value * host.resources.displayMetrics.density).toInt()

    fun dismissNotice() {
        noticeTimeout?.let { host.window.decorView.removeCallbacks(it) }
        noticeTimeout = null
        noticeBar?.let { bar -> (bar.parent as? android.view.ViewGroup)?.removeView(bar) }
        noticeBar = null
    }

    /**
     * A hand-drawn prompt above the container's floating bar. It exists because the platform theme
     * cannot inflate a Material snackbar, and because a Material snackbar added to the content view
     * would sit under the bar on the standalone page too.
     */
    private fun showNotice(
        message: String,
        actionText: String? = null,
        autoDismissMs: Long = 0L,
        action: (() -> Unit)? = null
    ) {
        dismissNotice()
        val anchor = host.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return

        val bar = android.widget.LinearLayout(host).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
            minimumHeight = dp(52)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF2E2F38.toInt())
            }
            elevation = dp(6).toFloat()
        }

        bar.addView(android.widget.TextView(host).apply {
            text = message
            textSize = 15f
            setTextColor(0xFFECECF1.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        })

        if (actionText != null) {
            bar.addView(android.widget.TextView(host).apply {
                text = actionText
                textSize = 15f
                setTextColor(0xFFB9A7FF.toInt())
                setPadding(dp(16), dp(14), dp(16), dp(14))
                isClickable = true
                setOnClickListener {
                    dismissNotice()
                    action?.invoke()
                }
            })
        }

        anchor.addView(
            bar,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                bottomMargin = dp(138 + 12)
                leftMargin = dp(12)
                rightMargin = dp(12)
            }
        )
        noticeBar = bar

        if (autoDismissMs > 0) {
            val dismiss = Runnable { dismissNotice() }
            noticeTimeout = dismiss
            host.window.decorView.postDelayed(dismiss, autoDismissMs)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ShizukuSettings.hasRequestedHomeNotificationPermission()) return
        if (host.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        ShizukuSettings.setRequestedHomeNotificationPermission(true)
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun invalidate() {
        // Individual property observers above handle the specific updates.
    }
}
