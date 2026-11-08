package af.shizuku.manager.management

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.databinding.AppsActivityBinding
import af.shizuku.manager.databinding.SwipeHintOverlayBinding
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import timber.log.Timber
import java.util.Objects

/**
 * Everything the apps page needs once its activity stops existing.
 *
 * The page is still rendered by XML ([AppsActivityBinding]): a list of app rows with icon, label,
 * permission summary and a toggle does not gain anything from being rebuilt in Compose, and
 * keeping the same binding is what keeps the row layout, the swipe background and the decorations
 * byte-identical to the standalone page.
 *
 * What did have to leave the activity is the *host*: the options menu (the container has no
 * OptionsMenu at all, so sort and the bulk actions are exposed as plain functions for the Compose
 * app bar to call) and the back handling (the container owns the back dispatcher).
 */
class AppsController(
    private val host: FragmentActivity,
    /** Whether a horizontal drag on a row runs the row's swipe action. See [setupSwipe]. */
    private val rowSwipeEnabled: Boolean = false
) : AppViewHolder.Callbacks, AppRowHost {

    private companion object {
        /** Height of the container's floating tab bar plus its fade; the undo snackbar clears it. */
        const val CONTAINER_TAB_BAR_HEIGHT_DP = 138
    }

    val viewModel: AppsViewModel =
        ViewModelProvider(host)[AppsViewModel::class.java]

    val adapter = AppsAdapter()

    /** Mirrors [AppsAdapter.isSelectionMode] so the Compose app bar can react to it. */
    var selectionMode by mutableStateOf(false)
        private set

    /** How many rows are ticked; the app bar shows "已选择 N 项" while selection is on. */
    var selectionCount by mutableStateOf(0)
        private set

    private lateinit var binding: AppsActivityBinding
    private lateinit var hostRoot: View
    private lateinit var recyclerView: RecyclerView
    private var cachedRoot: View? = null
    private var attached = false
    private var firstLoad = true
    private var swipeRightAction = "none"
    private var swipeLeftAction = "none"

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isDead() && !host.isFinishing) host.finish()
    }

    val sortOrder: SortOrder get() = viewModel.sortOrder
    val filterState: FilterState get() = viewModel.filterState

    /**
     * The page view, created once and then handed back every time the pager re-composes this
     * destination. The pager does evict pages beyond its offscreen window, and a freshly inflated
     * binding would come up with no adapter attached — a blank white list. Reusing the same view
     * keeps the RecyclerView, its scroll position and its decorations intact.
     *
     * The page is assembled exactly the way the standalone activity assembled it: the coordinator
     * + app bar (search field and filter chips) comes from `apps_appbar_activity`, and the list
     * from `apps_activity` is added as its scrolling view. Only the title toolbar is dropped —
     * the container's Compose app bar owns the title now.
     */
    fun pageView(context: Context): View {
        cachedRoot?.let { return it }
        val inflater = android.view.LayoutInflater.from(context)
        val page = inflater.inflate(R.layout.apps_appbar_activity, null, false)
                as androidx.coordinatorlayout.widget.CoordinatorLayout
        hostRoot = page

        val appbar = page.findViewById<com.google.android.material.appbar.AppBarLayout>(
            R.id.toolbar_container
        )
        appbar?.findViewById<View>(R.id.toolbar)?.visibility = View.GONE
        if (appbar != null && af.shizuku.core.ui.compose.isGlassTheme(context)) {
            val night = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            appbar.background = androidx.core.content.res.ResourcesCompat.getDrawable(
                context.resources,
                if (night) R.drawable.glass_appbar_scrim else R.drawable.glass_appbar_scrim_light,
                context.theme
            )
            appbar.isLiftOnScroll = false
        }

        val b = AppsActivityBinding.inflate(inflater, page, false)
        attach(b)
        val lp = androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        lp.behavior = com.google.android.material.appbar.AppBarLayout.ScrollingViewBehavior()
        page.addView(b.root, lp)

        cachedRoot = page
        return page
    }

    /**
     * Binds the inflated page once. The pager can keep the page alive across swipes, so this has
     * to be idempotent — a second attach on the same binding would add a duplicate decoration,
     * a second ItemTouchHelper and a second data observer.
     */
    fun attach(binding: AppsActivityBinding) {
        if (attached) return
        attached = true
        this.binding = binding
        recyclerView = binding.list

        val emptyStateView = binding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_empty_search_24)
        emptyStateView.setTitle(R.string.empty_state_title_no_results)
        emptyStateView.setDescription(R.string.empty_state_description_no_results)
        emptyStateView.hideActionButton()

        // These two live in the app bar of the page root, not in the list binding, so they are
        // looked up by id exactly like the standalone activity did.
        hostRoot.findViewById<android.widget.EditText>(R.id.search_edit_text)
            .doOnTextChanged { text, _, _, _ ->
                viewModel.setSearch(text?.toString() ?: "")
            }
        hostRoot.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filter_chip_group)
            .setOnCheckedStateChangeListener { _, checkedIds ->
                val first = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
                when (first) {
                    R.id.chip_all -> viewModel.setFilter(FilterState.ALL)
                    R.id.chip_granted -> viewModel.setFilter(FilterState.GRANTED)
                    R.id.chip_denied -> viewModel.setFilter(FilterState.DENIED)
                    R.id.chip_hidden -> viewModel.setFilter(FilterState.HIDDEN)
                }
            }

        viewModel.packages.observe(host) {
            when (it.status) {
                Status.SUCCESS -> {
                    val data = it.data ?: emptyList()
                    adapter.updateData(data)
                    val hasData = data.isNotEmpty()
                    emptyStateView.visibility = if (hasData) View.GONE else View.VISIBLE
                    recyclerView.visibility = if (hasData) View.VISIBLE else View.GONE
                    if (firstLoad && data.isNotEmpty()) {
                        firstLoad = false
                        recyclerView.scheduleLayoutAnimation()
                        maybeShowSwipeHint()
                    }
                }
                Status.ERROR -> {
                    host.runOnUiThread {
                        if (host.isFinishing || host.isDestroyed) return@runOnUiThread
                        host.finish()
                        Toast.makeText(
                            host,
                            Objects.toString(it.error, host.getString(R.string.error_unknown)),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    Timber.w("load apps failed", it.error)
                }
                Status.LOADING -> {}
            }
        }
        viewModel.load()

        recyclerView.adapter = adapter
        recyclerView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 116dp = the floating tab bar (66dp) + its 20dp lift + breathing room, so the last
            // card can still be scrolled clear of the bar.
            val extraBottomPadding = (116 * host.resources.displayMetrics.density).toInt()
            v.setPadding(bars.left, v.paddingTop, bars.right, bars.bottom + extraBottomPadding)
            insets
        }

        recyclerView.layoutAnimation =
            AnimationUtils.loadLayoutAnimation(host, R.anim.layout_animation_slide_bottom)
        recyclerView.setBackgroundColor(Color.TRANSPARENT)
        recyclerView.addItemDecoration(AppListItemDecoration(host))
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                selectionMode = adapter.isSelectionMode
                selectionCount = adapter.selectedPackages.size
            }
        })

        setupSwipe(recyclerView)
        ShizukuStateMachine.addListener(stateListener)
    }

    /**
     * Called when the container is destroyed. The pager may drop the page's fragment view earlier
     * than the activity dies, but the state listener lives as long as the activity, so it is
     * removed here rather than from any per-view callback.
     */
    fun dispose() {
        ShizukuStateMachine.removeListener(stateListener)
    }

    fun refresh() {
        if (!attached) return
        viewModel.refresh()
        swipeRightAction = ShizukuSettings.getSwipeRightAction()
        swipeLeftAction = ShizukuSettings.getSwipeLeftAction()
    }

    // ----- Selection / bulk actions (were the options menu) -----

    fun exitSelection() {
        if (!adapter.isSelectionMode) return
        adapter.isSelectionMode = false
        selectionMode = false
        selectionCount = 0
    }

    /** Returns true when back was consumed by leaving selection mode. */
    fun handleBack(): Boolean {
        if (!adapter.isSelectionMode) return false
        exitSelection()
        return true
    }

    fun selectAll() {
        viewModel.packages.value?.data?.forEach { adapter.selectedPackages.add(it.packageName) }
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
        selectionCount = adapter.selectedPackages.size
    }

    fun grantSelected() {
        adapter.selectedPackages.toList().forEach { pkg ->
            val pi = viewModel.packages.value?.data?.find { it.packageName == pkg }
            pi?.applicationInfo?.uid?.let { uid -> AuthorizationManager.grant(pkg, uid) }
        }
        exitSelection()
        viewModel.load()
    }

    fun revokeSelected() {
        adapter.selectedPackages.toList().forEach { pkg ->
            val pi = viewModel.packages.value?.data?.find { it.packageName == pkg }
            pi?.applicationInfo?.uid?.let { uid -> AuthorizationManager.revoke(pkg, uid) }
        }
        exitSelection()
        viewModel.load()
    }

    fun hideSelected() {
        val hidden = adapter.selectedPackages.toList()
        hidden.forEach { viewModel.hidePackage(it) }
        exitSelection()
        viewModel.load()
        showUndo(host.getString(R.string.app_management_hidden)) {
            hidden.forEach { viewModel.unhidePackage(it) }
            viewModel.load()
        }
    }

    fun unhideSelected() {
        val restored = adapter.selectedPackages.toList()
        restored.forEach { viewModel.unhidePackage(it) }
        exitSelection()
        viewModel.load()
        showUndo(host.getString(R.string.app_management_unhidden)) {
            restored.forEach { viewModel.hidePackage(it) }
            viewModel.load()
        }
    }

    fun setSort(order: SortOrder) {
        viewModel.setSortOrder(order)
    }

    // ----- Hide callback -----

    /** Set by the hosting activity: the window content view that the undo snackbar attaches to. */
    var snackbarAnchor: android.view.ViewGroup? = null

    override fun onHideApp(packageName: String) {
        if (viewModel.filterState == FilterState.HIDDEN) {
            viewModel.unhidePackage(packageName)
            showUndo(host.getString(R.string.app_management_unhidden)) {
                viewModel.hidePackage(packageName)
                viewModel.load()
            }
            viewModel.load()
        } else {
            viewModel.hidePackage(packageName)
            showUndo(host.getString(R.string.app_management_hidden)) {
                viewModel.unhidePackage(packageName)
                viewModel.load()
            }
            viewModel.load()
        }
    }

    /**
     * The standalone page offered "undo" through a Snackbar on its coordinator root. That root no
     * longer exists, and a Toast cannot carry an action, so hiding an app from the container would
     * have quietly lost the way back.
     *
     * The strip itself lives in [af.shizuku.manager.utils.GlassUndoBar] and is shared with the
     * standalone activity, so both hosts offer the same way back.
     */
    private fun showUndo(message: String, undo: () -> Unit) {
        val anchor = snackbarAnchor
        if (anchor == null) {
            Toast.makeText(host, message, Toast.LENGTH_LONG).show()
            return
        }
        af.shizuku.manager.utils.GlassUndoBar.show(
            host,
            host,
            anchor,
            message,
            host.getString(R.string.app_management_undo),
            undo
        )
    }

    private fun dismissUndoBar() {
        af.shizuku.manager.utils.GlassUndoBar.dismiss(host)
    }

    fun hideApp(packageName: String) = onHideApp(packageName)

    // ----- Swipe gestures -----

    private fun setupSwipe(rv: RecyclerView) {
        swipeRightAction = ShizukuSettings.getSwipeRightAction()
        swipeLeftAction = ShizukuSettings.getSwipeLeftAction()

        // Inside the container the page shares a horizontal pager with its four siblings, and a
        // horizontal drag on a row is indistinguishable from a page swipe. With the ItemTouchHelper
        // attached the row always won - one left flick on the list jumped straight to the system
        // app-info screen instead of turning the page, which breaks the one thing this rework is
        // about. The gestures stay available through the TalkBack actions (AppRowHost.onRowSwipe),
        // and `rowSwipeEnabled` switches the touch behaviour back on for any host that is not a
        // pager page.
        if (!rowSwipeEnabled) return

        val cb = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun getSwipeDirs(r: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                if (vh !is AppViewHolder) return 0
                var dirs = 0
                if (swipeRightAction != "none") dirs = dirs or ItemTouchHelper.RIGHT
                if (swipeLeftAction != "none") dirs = dirs or ItemTouchHelper.LEFT
                return dirs
            }

            override fun onMove(
                r: RecyclerView,
                v: RecyclerView.ViewHolder,
                t: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                val items = adapter.getItems<Any>()
                val item = items.getOrNull(pos) as? PackageInfo
                adapter.notifyItemChanged(pos) // snap back
                item ?: return

                val action =
                    if (direction == ItemTouchHelper.RIGHT) swipeRightAction else swipeLeftAction
                handleSwipeAction(item, action, vh.itemView)
            }

            override fun onChildDraw(
                c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val v = vh.itemView
                val action = if (dX > 0) swipeRightAction else swipeLeftAction
                if (action != "none") {
                    drawSwipeBackground(c, v, dX, action)
                }
                super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(rv)
    }

    // internal (not private): also invoked from AppViewHolder for the TalkBack-accessible
    // equivalent of the swipe gestures (#41), and from the container's AppRowHost bridge.
    override fun onRowSwipe(item: PackageInfo, action: String, itemView: View) =
        handleSwipeAction(item, action, itemView)

    fun handleSwipeAction(item: PackageInfo, action: String, itemView: View) {
        val opts = ActivityOptions.makeCustomAnimation(
            host, android.R.anim.fade_in, android.R.anim.fade_out
        ).toBundle()

        val appLabel =
            item.applicationInfo?.loadLabel(host.packageManager)?.toString() ?: item.packageName
        ActivityLogManager.log(appLabel, item.packageName, "Swipe: $action")

        if (action == "none") {
            HapticUtils.error(itemView)
        } else {
            HapticUtils.success(itemView)
        }

        when (action) {
            "open_app" -> {
                val intent = host.packageManager.getLaunchIntentForPackage(item.packageName)
                if (intent != null) host.startActivity(intent, opts)
                else Toast.makeText(host, R.string.app_management_no_launcher, Toast.LENGTH_SHORT).show()
            }
            "app_info" -> host.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", item.packageName, null)
                ), opts
            )
            "toggle_permission" -> {
                val uid = item.applicationInfo?.uid ?: return
                try {
                    if (AuthorizationManager.granted(item.packageName, uid)) {
                        AuthorizationManager.revoke(item.packageName, uid)
                    } else {
                        AuthorizationManager.grant(item.packageName, uid)
                    }
                    adapter.notifyItemChanged(0)
                } catch (e: SecurityException) {
                    Toast.makeText(
                        host,
                        R.string.app_management_dialog_adb_is_limited_title,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            "hide_from_list" -> onHideApp(item.packageName)
        }
    }

    private fun drawSwipeBackground(c: Canvas, v: View, dX: Float, action: String) {
        val (bgAttr, onAttr, iconRes) = when (action) {
            "open_app" -> Triple(
                android.R.attr.colorPrimary,
                com.google.android.material.R.attr.colorOnPrimary,
                R.drawable.ic_outline_play_arrow_24
            )
            "app_info" -> Triple(
                com.google.android.material.R.attr.colorSecondary,
                com.google.android.material.R.attr.colorOnSecondary,
                R.drawable.ic_outline_info_24
            )
            "toggle_permission" -> Triple(
                com.google.android.material.R.attr.colorTertiary,
                com.google.android.material.R.attr.colorOnTertiary,
                R.drawable.ic_shield_24
            )
            "hide_from_list" -> Triple(
                android.R.attr.colorError,
                com.google.android.material.R.attr.colorOnError,
                R.drawable.ic_visibility_off_24
            )
            else -> Triple(
                com.google.android.material.R.attr.colorSecondary,
                com.google.android.material.R.attr.colorOnSecondary,
                R.drawable.ic_outline_info_24
            )
        }
        val tv = TypedValue()
        host.theme.resolveAttribute(bgAttr, tv, true)
        val bgColor = tv.data
        host.theme.resolveAttribute(onAttr, tv, true)
        val iconTint = tv.data

        val bg = ColorDrawable(bgColor)
        val icon = AppCompatResources.getDrawable(host, iconRes)?.mutate()?.also {
            it.setTint(iconTint)
        }
        val margin = v.height / 4
        val intrinsicHeight = icon?.intrinsicHeight ?: 0
        val intrinsicWidth = icon?.intrinsicWidth ?: 0
        val top = v.top + (v.height - intrinsicHeight) / 2

        if (dX > 0) {
            bg.setBounds(v.left, v.top, v.left + dX.toInt(), v.bottom)
            bg.draw(c)
            icon?.setBounds(
                v.left + margin, top,
                v.left + margin + intrinsicWidth, top + intrinsicHeight
            )
            icon?.draw(c)
        } else {
            bg.setBounds(v.right + dX.toInt(), v.top, v.right, v.bottom)
            bg.draw(c)
            icon?.setBounds(
                v.right - margin - intrinsicWidth, top,
                v.right - margin, top + intrinsicHeight
            )
            icon?.draw(c)
        }
    }

    // ----- First-run swipe hint -----

    private fun maybeShowSwipeHint() {
        val prefs = host.getSharedPreferences("app_management_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("swipe_hint_shown", false)) return
        prefs.edit().putBoolean("swipe_hint_shown", true).apply()
        recyclerView.postDelayed({ if (!host.isFinishing) showSwipeHint() }, 500)
    }

    private fun showSwipeHint() {
        val root = hostRoot
        val hintBinding = SwipeHintOverlayBinding.inflate(
            android.view.LayoutInflater.from(host), root as ViewGroup, false
        )
        val hint = hintBinding.root
        root.addView(hint)

        hint.post {
            hint.translationY = hint.height.toFloat()
            hint.animate()
                .translationY(0f)
                .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()

            val iconRight = hintBinding.hintIconRight
            iconRight.postDelayed({
                iconRight.animate()
                    .translationX(dpToPx(18f))
                    .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction {
                        iconRight.animate()
                            .translationX(0f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                            .start()
                    }.start()
            }, 500)

            val iconLeft = hintBinding.hintIconLeft
            iconLeft.postDelayed({
                iconLeft.animate()
                    .translationX(-dpToPx(18f))
                    .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .withEndAction {
                        iconLeft.animate()
                            .translationX(0f)
                            .setDuration(ShizukuSettings.scaledAnimationDuration(250))
                            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                            .start()
                    }.start()
            }, 1000)
        }

        val dismiss = {
            hint.animate()
                .translationY(hint.height.toFloat())
                .alpha(0f)
                .setDuration(ShizukuSettings.scaledAnimationDuration(280))
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction { (hint.parent as? ViewGroup)?.removeView(hint) }
                .start()
        }
        hint.setOnClickListener { dismiss() }
        hint.postDelayed(dismiss, 4000)
    }

    private fun dpToPx(dp: Float) = dp * host.resources.displayMetrics.density
}
