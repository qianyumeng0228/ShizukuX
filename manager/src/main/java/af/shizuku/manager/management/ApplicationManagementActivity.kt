package af.shizuku.manager.management

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.doOnLayout
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver
import com.google.android.material.chip.Chip
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.databinding.AppsActivityBinding
import af.shizuku.manager.databinding.AppsAppbarActivityBinding
import af.shizuku.manager.databinding.SwipeHintOverlayBinding
import timber.log.Timber
import af.shizuku.manager.management.AppViewHolder.Callbacks
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.ShizukuStateMachine
import rikka.lifecycle.Status
import rikka.recyclerview.addEdgeSpacing
import rikka.recyclerview.fixEdgeEffect
import java.util.Objects

open class ApplicationManagementActivity : AppBarActivity(), AppViewHolder.Callbacks, AppRowHost {

    // Apps is a peer destination behind the glass tab bar, so it never shows the app-bar up arrow -
    // not even when a deep link or shortcut opens it directly.
    override val showsUpNavigation = false

    override fun onRowSwipe(item: PackageInfo, action: String, itemView: View) =
        handleSwipeAction(item, action, itemView)

    private val viewModel: AppsViewModel by viewModels()
    private val adapter = AppsAdapter()
    private lateinit var recyclerView: RecyclerView
    private var firstLoad = true
    private var backCallback: androidx.activity.OnBackPressedCallback? = null
    private var swipeRightAction = "none"
    private var swipeLeftAction = "none"

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isDead() && !isFinishing) finish()
    }

    override fun getLayoutId() = R.layout.apps_appbar_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ShizukuStateMachine.isRunning()) {
            finish()
            return
        }

        val binding = AppsActivityBinding.inflate(layoutInflater, rootView, false)
        setContentView(binding.root)

        // Assign before observe() — LiveData delivers synchronously on config change
        // and the observer references recyclerView, so it must be ready first.
        recyclerView = binding.list

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Empty state view
        val emptyStateView = binding.emptyStateView
        emptyStateView.setIcon(R.drawable.ic_empty_search_24)
        emptyStateView.setTitle(R.string.empty_state_title_no_results)
        emptyStateView.setDescription(R.string.empty_state_description_no_results)
        emptyStateView.hideActionButton()

        // Predictive back support for selection mode
        backCallback = object : androidx.activity.OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (adapter.isSelectionMode) {
                    adapter.isSelectionMode = false
                    invalidateOptionsMenu()
                    supportActionBar?.title = getString(R.string.home_app_management_title)
                    this.isEnabled = false
                }
            }
        }
        backCallback?.let {
            onBackPressedDispatcher.addCallback(this, it)
        }

        // Search bar
        rootView.findViewById<android.widget.EditText>(R.id.search_edit_text).doOnTextChanged { text, _, _, _ ->
            viewModel.setSearch(text?.toString() ?: "")
        }

        rootView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.filter_chip_group).setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds.first()) {
                R.id.chip_all -> viewModel.setFilter(FilterState.ALL)
                R.id.chip_granted -> viewModel.setFilter(FilterState.GRANTED)
                R.id.chip_denied -> viewModel.setFilter(FilterState.DENIED)
                R.id.chip_hidden -> viewModel.setFilter(FilterState.HIDDEN)
            }
        }

        // Same explicit reload entry as the container page; the standalone activity keeps its
        // toolbar, but this row is where the user already looks for list controls.
        rootView.findViewById<android.widget.ImageButton>(R.id.action_refresh_apps)?.setOnClickListener { v ->
            viewModel.refresh()
            v.animate().rotationBy(360f).setDuration(900L).start()
        }

        viewModel.packages.observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    val data = it.data ?: emptyList()
                    adapter.updateData(data)
                    pruneSelectionToVisible(data)

                    // Show empty state when filtered results are empty
                    val hasData = data.isNotEmpty()
                    emptyStateView.visibility = if (hasData) View.GONE else View.VISIBLE
                    recyclerView.visibility = if (hasData) View.VISIBLE else View.GONE

                    if (firstLoad && !data.isNullOrEmpty()) {
                        firstLoad = false
                        recyclerView.scheduleLayoutAnimation()
                        maybeShowSwipeHint()
                    }
                }
                Status.ERROR -> {
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        finish()
                        Toast.makeText(this, Objects.toString(it.error, getString(R.string.error_unknown)), Toast.LENGTH_SHORT).show()
                    }
                    Timber.w("load apps failed", it.error)
                }
                Status.LOADING -> {}
            }
        }
        viewModel.load()

        recyclerView.adapter = adapter
        recyclerView.clipToPadding = false

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            // 116dp = the floating tab bar (66dp) + its 20dp lift + breathing room, so the last
            // card can still be scrolled clear of the bar.
            val extraBottomPadding = (116 * resources.displayMetrics.density).toInt()
            v.setPadding(bars.left, v.paddingTop, bars.right, bars.bottom + extraBottomPadding)
            insets
        }

        // M3E 2026 Layout Animation for fluid motion on entry
        recyclerView.layoutAnimation = AnimationUtils.loadLayoutAnimation(
            this, R.anim.layout_animation_slide_bottom
        )

        recyclerView.setBackgroundColor(Color.TRANSPARENT)
        recyclerView.addItemDecoration(AppListItemDecoration(this))
        recyclerView.fixEdgeEffect()
        recyclerView.addEdgeSpacing(top = 8f, bottom = 8f, unit = TypedValue.COMPLEX_UNIT_DIP)

        adapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onChanged() {
                backCallback?.isEnabled = adapter.isSelectionMode
                if (adapter.isSelectionMode) {
                    supportActionBar?.title = getString(R.string.app_management_selected, adapter.selectedPackages.size)
                    invalidateOptionsMenu()
                }
            }
        })

        setupSwipe(recyclerView)
        ShizukuStateMachine.addListener(stateListener)

        // The tab bar has to stay visible here too, otherwise tapping "应用" reads as leaving the
        // app's navigation instead of switching a tab.
        af.shizuku.manager.utils.GlassTabBar.attach(this, rootView, af.shizuku.manager.utils.GlassTabs.INDEX_APPS)
    }

    /**
     * Leaves multi-select: drops the tick set, restores the plain title and rebuilds the overflow
     * menu. Bulk actions used to flip only [adapter.isSelectionMode], which left the app bar on
     * "已选择 N 项" with the bulk menu items still in place after the list had already changed.
     */
    private fun exitSelectionUi() {
        adapter.selectedPackages.clear()
        adapter.isSelectionMode = false
        backCallback?.isEnabled = false
        supportActionBar?.title = getString(R.string.home_app_management_title)
        invalidateOptionsMenu()
        adapter.notifyDataSetChanged()
    }

    /**
     * Drops ticks for rows the current filter/search no longer shows, so "已选择 N 项" never counts
     * an app that is off screen and the bulk actions can only reach what the user can see. Mirrors
     * the container's implementation in [AppsController].
     */
    private fun pruneSelectionToVisible(data: List<android.content.pm.PackageInfo>) {
        if (!adapter.isSelectionMode) return
        val visible = HashSet<String>(data.size)
        data.forEach { visible.add(it.packageName) }
        if (!adapter.selectedPackages.retainAll(visible)) return
        if (adapter.selectedPackages.isEmpty()) {
            exitSelectionUi()
        } else {
            adapter.notifyDataSetChanged()
        }
    }

    // ----- Options menu: sort -----
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (adapter.isSelectionMode) {
            menu.add(0, 10, 0, R.string.app_management_bulk_select_all).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(0, 11, 0, R.string.app_management_bulk_grant).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add(0, 12, 0, R.string.app_management_bulk_revoke).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

            if (viewModel.filterState == FilterState.HIDDEN) {
                menu.add(0, 14, 0, R.string.app_management_bulk_unhide).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            } else {
                menu.add(0, 13, 0, R.string.app_management_bulk_hide).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
            return true
        }
        menuInflater.inflate(R.menu.apps_management_menu, menu)
        val sortId = when (viewModel.sortOrder) {
            SortOrder.NAME_ASC -> R.id.sort_name
            SortOrder.LAST_INSTALLED -> R.id.sort_last_installed
            SortOrder.LAST_UPDATED -> R.id.sort_last_updated
        }
        menu.findItem(sortId)?.isChecked = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (adapter.isSelectionMode) {
            when (item.itemId) {
                10 -> { // Select all
                    viewModel.packages.value?.data?.forEach { adapter.selectedPackages.add(it.packageName) }
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
                11 -> { // Grant all
                    adapter.selectedPackages.toList().forEach { pkg ->
                        val pi = viewModel.packages.value?.data?.find { it.packageName == pkg }
                        pi?.applicationInfo?.uid?.let { uid -> AuthorizationManager.grant(pkg, uid) }
                    }
                    exitSelectionUi()
                    viewModel.load()
                }
                12 -> { // Revoke all
                    adapter.selectedPackages.toList().forEach { pkg ->
                        val pi = viewModel.packages.value?.data?.find { it.packageName == pkg }
                        pi?.applicationInfo?.uid?.let { uid -> AuthorizationManager.revoke(pkg, uid) }
                    }
                    exitSelectionUi()
                    viewModel.load()
                }
                13 -> { // Hide all
                    val hidden = adapter.selectedPackages.toList()
                    hidden.forEach { viewModel.hidePackage(it) }
                    exitSelectionUi()
                    viewModel.load()
                    // Bulk hide is the same destructive action as hiding one row, so it has to offer
                    // the same way back instead of dropping the user into a shorter list.
                    af.shizuku.manager.utils.GlassUndoBar.show(
                        this,
                        this,
                        findViewById(android.R.id.content),
                        getString(R.string.app_management_hidden),
                        getString(R.string.app_management_undo)
                    ) {
                        hidden.forEach { viewModel.unhidePackage(it) }
                        viewModel.load()
                    }
                }
                14 -> { // Unhide all
                    val restored = adapter.selectedPackages.toList()
                    restored.forEach { viewModel.unhidePackage(it) }
                    exitSelectionUi()
                    viewModel.load()
                    af.shizuku.manager.utils.GlassUndoBar.show(
                        this,
                        this,
                        findViewById(android.R.id.content),
                        getString(R.string.app_management_unhidden),
                        getString(R.string.app_management_undo)
                    ) {
                        restored.forEach { viewModel.hidePackage(it) }
                        viewModel.load()
                    }
                }
                android.R.id.home -> {
                    exitSelectionUi()
                }
            }
            return true
        }
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            R.id.sort_name -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.NAME_ASC); true }
            R.id.sort_last_installed -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.LAST_INSTALLED); true }
            R.id.sort_last_updated -> { item.isChecked = true; viewModel.setSortOrder(SortOrder.LAST_UPDATED); true }
            R.id.action_root_compat -> {
                startActivity(Intent(this, af.shizuku.manager.settings.RootCompatibilityActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ----- Hide callback -----

    /**
     * The platform Snackbar used to be shown on this page's coordinator root, but its action button
     * cannot be inflated on this theme and it landed behind the floating tab bar, so a hide from a
     * deep link or shortcut had no way back. Both hosts now raise the same self-drawn strip.
     */
    override fun onHideApp(packageName: String) {
        val fromHidden = viewModel.filterState == FilterState.HIDDEN
        if (fromHidden) viewModel.unhidePackage(packageName) else viewModel.hidePackage(packageName)
        viewModel.load()
        af.shizuku.manager.utils.GlassUndoBar.show(
            this,
            this,
            findViewById(android.R.id.content),
            getString(
                if (fromHidden) R.string.app_management_unhidden else R.string.app_management_hidden
            ),
            getString(R.string.app_management_undo)
        ) {
            if (fromHidden) viewModel.hidePackage(packageName)
            else viewModel.unhidePackage(packageName)
            viewModel.load()
        }
    }

    // ----- Swipe gestures -----

    private fun setupSwipe(rv: RecyclerView) {
        swipeRightAction = ShizukuSettings.getSwipeRightAction()
        swipeLeftAction = ShizukuSettings.getSwipeLeftAction()

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

            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.adapterPosition
                val items = adapter.getItems<Any>()
                val item = items.getOrNull(pos) as? PackageInfo
                adapter.notifyItemChanged(pos) // snap back
                item ?: return

                val action = if (direction == ItemTouchHelper.RIGHT) swipeRightAction else swipeLeftAction
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
    // equivalent of the swipe gestures (#41) - swiping to trigger an action has no screen-reader
    // affordance, so AppViewHolder exposes the same actions via custom accessibility actions.
    internal fun handleSwipeAction(item: PackageInfo, action: String, itemView: View) {
        val opts = ActivityOptions.makeCustomAnimation(
            this, android.R.anim.fade_in, android.R.anim.fade_out
        ).toBundle()

        val appLabel = item.applicationInfo?.loadLabel(packageManager)?.toString() ?: item.packageName
        ActivityLogManager.log(appLabel, item.packageName, "Swipe: $action")

        if (action == "none") {
            HapticUtils.error(itemView)
        } else {
            HapticUtils.success(itemView)
        }

        when (action) {
            "open_app" -> {
                val intent = packageManager.getLaunchIntentForPackage(item.packageName)
                if (intent != null) startActivity(intent, opts)
                else Toast.makeText(this, R.string.app_management_no_launcher, Toast.LENGTH_SHORT).show()
            }
            "app_info" -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", item.packageName, null)), opts
            )
            "toggle_permission" -> {
                val uid = item.applicationInfo?.uid ?: return
                try {
                    if (AuthorizationManager.granted(item.packageName, uid)) {
                        AuthorizationManager.revoke(item.packageName, uid)
                    } else {
                        AuthorizationManager.grant(item.packageName, uid)
                    }
                    adapter.notifyItemChanged(0) // update summary
                } catch (e: SecurityException) {
                    Toast.makeText(this, R.string.app_management_dialog_adb_is_limited_title, Toast.LENGTH_SHORT).show()
                }
            }
            "hide_from_list" -> onHideApp(item.packageName)
        }
    }

    private fun drawSwipeBackground(c: Canvas, v: android.view.View, dX: Float, action: String) {
        val (bgAttr, onAttr, iconRes) = when (action) {
            "open_app" -> Triple(android.R.attr.colorPrimary, com.google.android.material.R.attr.colorOnPrimary, R.drawable.ic_outline_play_arrow_24)
            "app_info" -> Triple(com.google.android.material.R.attr.colorSecondary, com.google.android.material.R.attr.colorOnSecondary, R.drawable.ic_outline_info_24)
            "toggle_permission" -> Triple(com.google.android.material.R.attr.colorTertiary, com.google.android.material.R.attr.colorOnTertiary, R.drawable.ic_shield_24)
            "hide_from_list" -> Triple(android.R.attr.colorError, com.google.android.material.R.attr.colorOnError, R.drawable.ic_visibility_off_24)
            else -> Triple(com.google.android.material.R.attr.colorSecondary, com.google.android.material.R.attr.colorOnSecondary, R.drawable.ic_outline_info_24)
        }
        val tv = TypedValue()
        theme.resolveAttribute(bgAttr, tv, true)
        val bgColor = tv.data
        theme.resolveAttribute(onAttr, tv, true)
        val iconTint = tv.data

        val bg = ColorDrawable(bgColor)
        val icon = AppCompatResources.getDrawable(this, iconRes)?.mutate()?.also { it.setTint(iconTint) }
        val margin = v.height / 4
        val intrinsicHeight = icon?.intrinsicHeight ?: 0
        val intrinsicWidth = icon?.intrinsicWidth ?: 0
        val top = v.top + (v.height - intrinsicHeight) / 2

        if (dX > 0) {
            bg.setBounds(v.left, v.top, v.left + dX.toInt(), v.bottom)
            bg.draw(c)
            icon?.setBounds(v.left + margin, top, v.left + margin + intrinsicWidth, top + intrinsicHeight)
            icon?.draw(c)
        } else {
            bg.setBounds(v.right + dX.toInt(), v.top, v.right, v.bottom)
            bg.draw(c)
            icon?.setBounds(v.right - margin - intrinsicWidth, top, v.right - margin, top + intrinsicHeight)
            icon?.draw(c)
        }
    }

    // ----- First-run swipe hint -----

    private fun maybeShowSwipeHint() {
        val prefs = getSharedPreferences("app_management_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("swipe_hint_shown", false)) return
        prefs.edit().putBoolean("swipe_hint_shown", true).apply()
        recyclerView.postDelayed({ if (!isFinishing) showSwipeHint() }, 500)
    }

    private fun showSwipeHint() {
        val hintBinding = SwipeHintOverlayBinding.inflate(layoutInflater, rootView as ViewGroup, false)
        val hint = hintBinding.root
        (rootView as ViewGroup).addView(hint)

        hint.doOnLayout { v ->
            // Start below screen, slide up
            v.translationY = v.height.toFloat()
            v.animate()
                .translationY(0f)
                .setDuration(ShizukuSettings.scaledAnimationDuration(350))
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()

            // Bounce the right-swipe icon right to hint the gesture
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

            // Bounce the left-swipe icon left
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

    private fun dpToPx(dp: Float) = dp * resources.displayMetrics.density

    // ----- Lifecycle -----

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
        // Re-read swipe settings: setupSwipe()'s callback closes over these fields, so a
        // change made in Settings while this activity was backgrounded takes effect here
        // without needing to rebuild the ItemTouchHelper.
        swipeRightAction = ShizukuSettings.getSwipeRightAction()
        swipeLeftAction = ShizukuSettings.getSwipeLeftAction()
    }
}

class AppListItemDecoration(context: Context) : af.shizuku.manager.utils.M3ECardItemDecoration(context) {
    // 26dp content padding + 48dp app icon + 24dp label margin: the hairline starts where the
    // title does instead of crossing the icon column.
    private val dividerInset = 98f * density

    override fun getDividerInset(view: View): Float = dividerInset
    override fun getDividerEndInset(view: View): Float = cardMargin

    // One hairline between rows: the whole list sits in a single card, so without dividers a
    // long run of icon/label/toggle triples reads as one undifferentiated block.
    override fun shouldDrawDivider(parent: RecyclerView, index: Int, count: Int): Boolean =
        index < count - 1
}
