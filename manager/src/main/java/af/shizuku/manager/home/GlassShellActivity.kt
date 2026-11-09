package af.shizuku.manager.home

import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.app.GlassBackground
import af.shizuku.manager.databinding.TerminalTutorialActivityBinding
import af.shizuku.manager.home.compose.GlassBottomBar
import af.shizuku.manager.home.compose.GlassLens
import af.shizuku.manager.home.compose.LensGeometry
import af.shizuku.manager.home.compose.LensLook
import af.shizuku.manager.home.compose.glassBackdrop
import af.shizuku.manager.home.compose.rememberGlassBackdropLayer
import af.shizuku.manager.management.AppsController
import af.shizuku.manager.management.AppsPage
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.module.AdbModule
import af.shizuku.manager.module.ModulesController
import af.shizuku.manager.module.ModulesScreen
import af.shizuku.manager.settings.SettingsController
import af.shizuku.manager.settings.compose.SettingsScreen
import af.shizuku.manager.shell.buildTerminalPage
import af.shizuku.manager.shell.exportTerminalAssets
import kotlin.math.roundToInt
import af.shizuku.manager.utils.GlassTabs
import af.shizuku.manager.utils.ShizukuStateMachine
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.airbnb.mvrx.viewModel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The single container the five destinations are folded into, and the app's home screen.
 *
 * Every destination used to be its own activity, so switching tabs started a new activity: the
 * content was replaced instead of sliding, and the floating pill re-seated itself instead of
 * following the finger. Now the five pages live in one [HorizontalPager], the pill is drawn once
 * by [GlassBottomBar] and fed the pager's live position, so both move continuously.
 *
 * It extends AppActivity (not a bare FragmentActivity) because the launcher reaches home through
 * MainActivity : HomeActivity : this class: only AppActivity applies the user's skin and window
 * setup, and only a MaterialActivity keeps Material components usable.
 */
open class GlassShellActivity : af.shizuku.core.ui.AppActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
    af.shizuku.manager.management.AppRowHost, com.airbnb.mvrx.MavericksView {

    private val settingsCtl by lazy { SettingsController(this) }
    private val appsCtl by lazy { AppsController(this, false, appsModel, exitOnDead = false) }

    // Home is the same page the standalone HomeActivity renders; it lives in HomeController so both
    // hosts can own it instead of the container keeping a placeholder.
    private val homeModel: HomeViewModel by viewModel()
    private val appsModel: af.shizuku.manager.management.AppsViewModel by viewModels()
    // Visible to HomeActivity: it still owns the startup intents that hand work to home.
    internal val homeCtl by lazy { HomeController(this, homeModel, appsModel) }

    // Which page the bar asked for. Kept on the activity (not in the composition) because a tab
    // tap can arrive through onNewIntent while the pager is already alive.
    private val requestedPage =
        androidx.compose.runtime.mutableIntStateOf(GlassTabs.INDEX_HOME)
    private val modulesCtl by lazy {
        ModulesController(this, lifecycleScope) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private val moduleInstallLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) modulesCtl.install(uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree: Uri? ->
            if (tree == null) return@registerForActivityResult
            lifecycleScope.launch {
                val (successCount, totalCount) = exportTerminalAssets(this@GlassShellActivity, tree)
                if (isFinishing || isDestroyed) return@launch
                val msg = if (successCount == totalCount) {
                    getString(R.string.shell_export_success)
                } else if (successCount == 0) {
                    getString(R.string.shell_export_failed)
                } else {
                    getString(R.string.shell_export_partial, successCount, totalCount)
                }
                Toast.makeText(
                    this@GlassShellActivity,
                    msg,
                    if (successCount == totalCount) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
            }
        }

    /**
     * The settings page draws its preference tree inside this container, so a preference carrying
     * `app:fragment` has to be resolved here: PreferenceFragmentCompat only ever asks its host
     * activity, and in the standalone page that host was SettingsActivity. Without this callback
     * the child page could still end up on the fragment manager, but the controller never received
     * the child's title, so the header stayed on "设置" instead of switching to the child's name.
     */
    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragmentName = pref.fragment ?: return false
        settingsCtl.openFragment(fragmentName, pref.extras, pref.title?.toString())
        return true
    }

    // The apps page moved into the pager, so the rows can no longer reach their host by casting the
    // context to ApplicationManagementActivity. These two are what the rows actually needed from
    // it: the "hidden" long-press action and the TalkBack versions of the swipe gestures.
    override fun onHideApp(packageName: String) {
        appsCtl.hideApp(packageName)
    }

    override fun onRowSwipe(item: android.content.pm.PackageInfo, action: String, itemView: android.view.View) {
        appsCtl.handleSwipeAction(item, action, itemView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The launcher entry is MainActivity -> HomeActivity -> this class, and the manifest gives
        // MainActivity the splash theme; installing here keeps the splash for every entry point.
        installSplashScreen()
            .setOnExitAnimationListener { provider ->
                // Some OEM/Android 15 builds return a non-null typed View that is actually null at
                // runtime (platform nullability contract violation). The safe cast prevents R8 from
                // eliding the null check.
                try {
                    val iconViewAny: Any? = provider.iconView
                    if (iconViewAny is android.view.View) {
                        val animator = iconViewAny.animate()
                        if (animator != null) {
                            animator.alpha(0f)
                                .scaleX(0.8f)
                                .scaleY(0.8f)
                                .setDuration(ShizukuSettings.scaledAnimationDuration(220))
                                .setInterpolator(
                                    android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
                                )
                                .withEndAction { provider.remove() }
                                .start()
                        } else {
                            provider.remove()
                        }
                    } else {
                        provider.remove()
                    }
                } catch (e: Exception) {
                    runCatching { provider.remove() }
                }
            }
        super.onCreate(savedInstanceState)
        requestedPage.intValue = intent?.getIntExtra(GlassTabs.EXTRA_START_PAGE, GlassTabs.INDEX_HOME)
            ?: GlassTabs.INDEX_HOME

        // AppActivity already applied the user's skin and enabled edge-to-edge for this activity;
        // nothing theme-related is re-applied here.

        // Compose's SnackbarHost draws over the AndroidView that hosts the app list, but the
        // native RecyclerView still wins the touch, so its "undo" button could never be pressed.
        // A bar added to the window's content view sits above the whole pager and receives the tap.
        appsCtl.snackbarAnchor = findViewById(android.R.id.content)

        settingsCtl.observeBackStack()
        modulesCtl.refresh()
        // Builds the home list (adapter, observers, edit-mode drag, back handling) before the
        // Compose tree asks page 0 for its RecyclerView.
        homeCtl.attach()

        // A settings child page lives on the fragment back stack, so back has to pop it before it
        // can close the whole container; otherwise the ← button and the hardware key disagree.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Selection mode is the innermost layer: it swallows back before the settings
                // child page does, exactly like the standalone apps page did.
                if (appsCtl.handleBack()) return
                if (settingsCtl.popInnerPage()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        setContent {
            AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                Shell()
            }
        }
    }

    @Composable
    private fun Shell() {
        val pagerState = rememberPagerState(
            initialPage = requestedPage.intValue,
            pageCount = { 5 }
        )
        val scope = rememberCoroutineScope()
        // Settings search is a modal input state: the bar would otherwise sit under the results
        // list and the IME, squeezing the last row out of reach. The page reports it here, and the
        // bar plus its scrim are dropped for the duration; backing out of search restores both.
        val settingsSearching = remember { mutableStateOf(false) }
        // A tab tap that arrives while this container is already running only changes requestedPage;
        // this turns that into the same animation the in-container bar uses.
        androidx.compose.runtime.LaunchedEffect(requestedPage.intValue) {
            pagerState.animateScrollToPage(requestedPage.intValue)
        }
        // The home-card switches live on the settings page of this same container, so returning to
        // the home page is a pager move, not an Activity resume — nothing re-read the preferences
        // and a card just hidden in Settings stayed on screen until the process restarted. Refresh
        // the list each time page 0 becomes the settled page.
        androidx.compose.runtime.LaunchedEffect(pagerState) {
            androidx.compose.runtime.snapshotFlow { pagerState.settledPage }.collect { page ->
                if (page == 0) homeCtl.adapter.updateData()
            }
        }
        // currentPage + offset is exactly the continuous 0..4 position the pill expects, which is
        // why the bar gained a positionFraction parameter instead of animating on its own.
        val positionFraction = pagerState.currentPage + pagerState.currentPageOffsetFraction
        // The bar's glass samples this layer, so the pager records itself once per frame and is
        // then blitted instead of drawn twice. Only the AGSL lens (API 33) needs it, so on older
        // devices no layer is created and the bar keeps its flat lozenge.
        val backdrop = rememberGlassBackdropLayer()
        val lensActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The page stage has to be inside the recorded layer, not behind it. The window
            // background (glass_window_background[_light]) is painted by the Activity, so a page
            // whose content is transparent (modules, terminal) recorded an empty frame there and
            // the lens read pure black. Painting the same gradient in Compose puts a real
            // backdrop under every pixel the lens can sample; it matches the window drawable
            // stop-for-stop, so pages keep their exact colour.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .let { if (lensActive) it.glassBackdrop(backdrop) else it }
            ) {
                GlassBackground {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                when (page) {
                    // The apps page hosts an AndroidView whose rows (and the "toggle all" header
                    // most of all) consume ACTION_MOVE, so a horizontal drag that starts on that
                    // band never reaches the pager and the page refuses to move. Claim the gesture
                    // in the Initial pass - before the interop view sees it - and only once the
                    // direction is clearly horizontal, so vertical list scrolling keeps working.
                    1 -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalSwipeToPager(pagerState, scope)
                    ) {
                        AppsPage(
                            controller = appsCtl,
                            onRootCompat = {
                                startActivity(
                                    android.content.Intent(
                                        this@GlassShellActivity,
                                        af.shizuku.manager.settings.RootCompatibilityActivity::class.java
                                    )
                                )
                            },
                            showTabBar = false
                        )
                    }
                    2 -> ModulesPage()
                    3 -> TerminalPage()
                    4 -> SettingsPage(settingsSearching)
                    0 -> HomePage(pagerScrollTo = { scope.launch { pagerState.animateScrollToPage(it) } })
                    // pageCount is 5 and 0..4 are all real pages, so this is only a guard for a
                    // future tab that has no content yet: render nothing rather than a placeholder
                    // that claims the page is still reachable from the bottom bar.
                    else -> {}
                }
                }
                }
            }

            // Same fade the XML pages get from GlassTabBar.attach, but for every page at once:
            // without it a hosted page's last cards run under the pill and stay readable through
            // the glass instead of retiring before they reach it.
            // The glass skin is a look, not a light/dark switch: forcing it on must not pin the
            // scrim to the light palette, or a dark system theme ends with a pale band under the
            // pill (and the light pages under it read as washed out).
            val dark = isSystemInDarkTheme()
            val scrim = if (dark) {
                if (ThemeHelper.isBlackNightTheme(this@GlassShellActivity)) Color.Black else Color(0xFF080B12)
            } else {
                Color(0xFFDDE4F6)
            }
            // The search state belongs to the settings page, but the pager can be swiped away from
            // it while the field still holds focus - so gate on both, otherwise entering search and
            // swiping to another tab leaves the whole shell with no bar and no way back.
            val barHidden = settingsSearching.value &&
                pagerState.currentPage == GlassTabs.INDEX_SETTINGS
            if (!barHidden) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(138.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.22f to scrim.copy(alpha = 0.34f),
                                0.55f to scrim.copy(alpha = 0.88f),
                                1f to scrim
                            )
                        )
                )

                val lensDensity = LocalDensity.current
                val lensUnit = (maxWidth - 36.dp) / 5
                val lensPad = 24.dp
                val lensRadius = 30.dp
                val lensPillW = lensUnit - 10.dp
                // Reference geometry: the glass body is 2*radius across and the box is that plus
                // 2*pad, so the refraction rim has room to bleed outside the bar.
                val lensW = lensRadius * 2 + lensPad * 2
                val lensH = lensW
                // While a finger holds the pill the page must stay put - the glass alone travels,
                // and only the slot it is dropped on decides which page loads. So the drag keeps
                // its own fraction instead of feeding the pager, and the pill reads either the
                // finger position or the pager's settled one.
                var dragFraction by remember { mutableStateOf<Float?>(null) }
                val shownFraction = dragFraction ?: positionFraction
                val lensCx = 18.dp + lensUnit * (shownFraction + 0.5f)
                val lensCy = maxHeight - 53.dp

                // Press feedback mirrors the reference interaction: the whole bar grows under the
                // finger and the glass grows a bit more on top of it, instead of a ripple or a
                // squashed lozenge. Only draw scale is touched, so nothing re-measures while the
                // animation runs. Both release specs overshoot slightly to keep the elastic feel.
                var lensPressed by remember { mutableStateOf(false) }
                val pressSx = remember { Animatable(1f) }
                val pressSy = remember { Animatable(1f) }
                val barScale = remember { Animatable(1f) }
                LaunchedEffect(lensPressed) {
                    if (lensPressed) {
                        launch {
                            pressSx.animateTo(
                                1.08f,
                                tween(durationMillis = 90, easing = FastOutSlowInEasing)
                            )
                        }
                        launch {
                            pressSy.animateTo(
                                1.08f,
                                tween(durationMillis = 90, easing = FastOutSlowInEasing)
                            )
                        }
                        barScale.animateTo(
                            1.03f,
                            tween(durationMillis = 90, easing = FastOutSlowInEasing)
                        )
                    } else {
                        launch {
                            pressSx.animateTo(
                                1f,
                                spring(dampingRatio = 0.30f, stiffness = Spring.StiffnessLow)
                            )
                        }
                        launch {
                            pressSy.animateTo(
                                1f,
                                spring(dampingRatio = 0.30f, stiffness = Spring.StiffnessLow)
                            )
                        }
                        barScale.animateTo(
                            1f,
                            spring(dampingRatio = 0.34f, stiffness = Spring.StiffnessLow)
                        )
                    }
                }

                GlassBottomBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 20.dp)
                        // Every cell owns a clickable, so a horizontal drag on the pill was
                        // consumed there and never reached the pager: the glass could only jump
                        // between five fixed slots. Claiming the drag here lets the glass track
                        // the finger continuously.
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { down ->
                                    // Start from the slot the finger actually landed on, not from
                                    // the page that happens to be showing: pressing "apps" while
                                    // "home" is open has to put the glass under the finger first,
                                    // otherwise a 75px drag only nudges it a third of a slot.
                                    val unitPx = lensUnit.toPx()
                                    dragFraction = if (unitPx > 0f) {
                                        ((down.x - 18.dp.toPx()) / unitPx - 0.5f)
                                            .coerceIn(0f, 4f)
                                    } else {
                                        pagerState.currentPage +
                                            pagerState.currentPageOffsetFraction
                                    }
                                },
                                onDragEnd = {
                                    val from = dragFraction
                                        ?: (pagerState.currentPage +
                                            pagerState.currentPageOffsetFraction)
                                    val target = from.roundToInt().coerceIn(0, 4)
                                    scope.launch {
                                        // Only now does the page move, and it moves to the slot the
                                        // glass was dropped on. The pill springs the last <0.5 slot
                                        // to centre while the pager slides underneath it.
                                        launch { pagerState.animateScrollToPage(target) }
                                        val settle = Animatable(from)
                                        settle.animateTo(
                                            targetValue = target.toFloat(),
                                            animationSpec = spring(
                                                dampingRatio = 0.55f,
                                                stiffness = Spring.StiffnessMedium
                                            )
                                        ) {
                                            dragFraction = value
                                        }
                                        // Same number again on both sides, so clearing it cannot
                                        // make the glass jump.
                                        dragFraction = null
                                    }
                                },
                                onDragCancel = { dragFraction = null }
                            ) { change, dragAmount ->
                                change.consume()
                                val unitPx = lensUnit.toPx()
                                if (unitPx > 0f) {
                                    val cur = dragFraction ?: positionFraction
                                    // A rightward drag selects the tab to the right: this is a
                                    // control, not content, so it does not invert the way a
                                    // finger on the page itself would. Subtracting here pinned
                                    // the value at 0 and the glass never left the home slot.
                                    dragFraction = (cur + dragAmount / unitPx)
                                        .coerceIn(0f, 4f)
                                }
                            }
                        },
                    selectedIndex = pagerState.currentPage,
                    positionFraction = shownFraction,
                    drawHighlight = !lensActive,
                    // Hung inside the bar rather than beside it: the icons have to stay above the
                    // glass, and the shader only returns colour inside the capsule, so the box
                    // being wider than the slot is invisible.
                    lensSlot = if (!lensActive) null else {
                        {
                            GlassLens(
                                backdrop = backdrop,
                                centreInBackdropPx = with(lensDensity) {
                                    Offset(lensCx.toPx(), lensCy.toPx())
                                },
                                sizeDp = DpSize(lensW, lensH),
                                geometry = with(lensDensity) {
                                    LensGeometry(
                                        radiusPx = lensRadius.toPx(),
                                        halfSpinePx = (lensPillW / 2 - lensRadius).toPx()
                                            .coerceAtLeast(0f),
                                        edgePx = 3.7f.dp.toPx()
                                    )
                                },
                                look = LensLook(),
                                dark = dark,
                                modifier = Modifier
                                    // Centred vertically rather than offset from the top: the box is
                                    // 108dp and the bar only 66dp, and align alone puts the surplus
                                    // evenly above and below - which is exactly the overhang the
                                    // reference has. A y offset on top of a top-start align landed
                                    // the glass 22dp too high.
                                    .align(Alignment.CenterStart)
                                    .offset(x = lensCx - 18.dp - lensW / 2)
                                    // Scale last so it wraps the whole glass body - shadow, clip
                                    // and the shader layer - around its centre. Two axes so a
                                    // press can squash rather than merely shrink.
                                    .scale(pressSx.value, pressSy.value)
                            )
                        }
                    },
                    onPressedChange = { lensPressed = it != null },
                    scale = barScale.value,
                    onHomeClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_HOME) } },
                    onAppsClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_APPS) } },
                    onModulesClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_MODULES) } },
                    onTerminalClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_TERMINAL) } },
                    onSettingsClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_SETTINGS) } }
                )
            }
        }
    }

    @Composable
    private fun HomePage(pagerScrollTo: (Int) -> Unit) {
        af.shizuku.manager.home.compose.HomeScreen(
            isEditMode = homeCtl.isEditMode,
            showEmptyState = homeCtl.showEmptyState,
            wallpaperTheme = homeCtl.wallpaperTheme,
            onStopClick = { confirmStopServer() },
            // Inside the container a tab action has to slide the pager, not open an activity.
            onSettingsClick = { pagerScrollTo(GlassTabs.INDEX_SETTINGS) },
            onHelpClick = { showHomeHelpDialog() },
            onRestoreHomeCards = { homeCtl.adapter.restoreAllCards() },
            onAppsClick = { pagerScrollTo(GlassTabs.INDEX_APPS) },
            onModulesClick = { pagerScrollTo(GlassTabs.INDEX_MODULES) },
            onTerminalClick = { pagerScrollTo(GlassTabs.INDEX_TERMINAL) },
            recyclerViewProvider = { ctx, paddingValues -> homeCtl.provider(ctx, paddingValues) },
            // One bar for five pages: the container draws it, page 0 only supplies the list.
            showTabBar = false
        )
    }

    /**
     * The container runs on the platform theme (`AppTheme parent="Theme.Light"`), not on a
     * MaterialComponents theme, so MaterialAlertDialogBuilder cannot inflate here - that is the
     * same reason the Material snackbar crashed the apps page. Platform dialogs are used instead.
     */
    private fun confirmStopServer() {
        if (!af.shizuku.manager.utils.ShizukuStateMachine.isRunning()) return
        android.app.AlertDialog.Builder(this)
            .setMessage(R.string.dialog_stop_message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                af.shizuku.manager.utils.ShizukuStateMachine.markUserStopped()
                af.shizuku.manager.receiver.ShizukuReceiverStarter.killServerProcess()
                runCatching { rikka.shizuku.Shizuku.exit() }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showHomeHelpDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.settings_shizuku_extra_features)
            .setMessage(getString(R.string.help_general_plus_summary).toHtml())
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.home_help_open_docs) { _, _ ->
                af.shizuku.manager.utils.CustomTabsHelper.launchUrlOrCopy(
                    this,
                    getString(R.string.help_url)
                )
            }
            .show()
    }

    @Composable
    private fun ModulesPage() {
        ModulesScreen(
            modules = modulesCtl.modules,
            health = modulesCtl.health,
            healthChecked = modulesCtl.healthChecked,
            trustedIds = modulesCtl.trustedIds,
            loaded = modulesCtl.loaded,
            installing = modulesCtl.installing,
            checking = modulesCtl.checking,
            busyModule = modulesCtl.busyModule,
            serviceRunning = modulesCtl.serviceRunning(),
            message = modulesCtl.message,
            output = modulesCtl.output,
            pendingDelete = modulesCtl.pendingDelete,
            onBack = {},
            showBack = false,
            onSelectTab = {},
            onInstall = {
                moduleInstallLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
            },
            onCheck = { modulesCtl.checkHealth() },
            onToggle = { modulesCtl.toggle(it) },
            onTrust = { module: AdbModule, trusted: Boolean -> modulesCtl.setTrusted(module, trusted) },
            onRunAction = { modulesCtl.runScript(it, isService = false) },
            onRunService = { modulesCtl.runScript(it, isService = true) },
            onOpenWebUi = { module ->
                module.webRoot?.let {
                    startActivity(af.shizuku.manager.module.ModuleWebUiActivity.intent(this, module))
                }
            },
            onDelete = { modulesCtl.askDelete(it) },
            onDismissMessage = { modulesCtl.dismissMessage() },
            onDismissOutput = { modulesCtl.dismissOutput() },
            onConfirmDelete = { modulesCtl.delete(it) },
            onCancelDelete = { modulesCtl.cancelDelete() },
            onOpenStore = {
                startActivity(
                    android.content.Intent(
                        this,
                        af.shizuku.manager.module.ModuleStoreActivity::class.java
                    )
                )
            },
            showTabBar = false
        )
    }

    @Composable
    private fun TerminalPage() {
        val context = LocalContext.current
        // The guide page has no app bar of its own: on the activity the title came from the
        // window's toolbar, so inside the container it has to be drawn here or the page starts
        // with a bare card.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.home_terminal_title),
                modifier = Modifier.padding(start = 26.dp, top = 12.dp, bottom = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            AndroidView(
                factory = { ctx ->
                    TerminalTutorialActivityBinding.inflate(LayoutInflater.from(ctx), null, false).also { b ->
                        buildTerminalPage(ctx, b, onPickExportDir = { exportLauncher.launch(null) })
                        // This layout is meant to sit under the window's AppBarLayout; inside the
                        // pager that app bar is dead weight (an empty strip above the first card)
                        // because the title is drawn here instead.
                        b.root.findViewById<android.view.View>(R.id.toolbar_container)?.visibility =
                            android.view.View.GONE
                        // The activity host used to paint this page's own background; inside the
                        // pager that would punch an opaque block through the shared glass
                        // gradient, so the inflated root has to stay transparent.
                        b.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }.root
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 116.dp)
            )
        }
    }

    @Composable
    private fun SettingsPage(searching: MutableState<Boolean>) {
        SettingsScreen(
            title = settingsCtl.currentTitle,
            // Inside the container settings is a peer page; the arrow only belongs to a preference
            // sub-page pushed onto the back stack.
            showBack = settingsCtl.hasInnerPage,
            onSelectTab = {},
            onNavigateUp = { settingsCtl.popInnerPage() },
            onNavigateToSetting = { settingsCtl.navigateToSetting(it) },
            searchResults = settingsCtl.searchResults,
            onSearchQueryChanged = { settingsCtl.search(it) },
            onContainerCreated = { settingsCtl.attachContainerIfNeeded() },
            onSearchActiveChanged = { searching.value = it },
            showTabBar = false
        )
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A tab bar somewhere else in the app re-used this container instead of stacking another
        // one: land on the page it asked for.
        val page = intent.getIntExtra(GlassTabs.EXTRA_START_PAGE, -1)
        if (page in 0..4) requestedPage.intValue = page
    }

    override fun onResume() {
        super.onResume()
        // The state machine persists the last settled state, so a process that cold-starts after
        // the server was killed can come up believing it is still RUNNING and render rows it can
        // no longer act on. MainActivity pings the binder in onStart; the container is also a
        // launcher entry and has to do the same before any page reads the state.
        ShizukuStateMachine.update()
        // Both hosted pages have to re-read what the settings pages can change while the
        // container is in the background: the apps list (permissions, hidden apps, swipe
        // actions) and the module list (a module may have been installed elsewhere).
        appsCtl.refresh()
        modulesCtl.refresh()
        homeCtl.onResume()
    }

    override fun invalidate() {
        // Home's own property observers handle the updates.
    }

    override fun onDestroy() {
        appsCtl.dispose()
        homeCtl.dispose()
        super.onDestroy()
    }
}

/**
 * Lets a horizontal drag that starts inside a hosted [AndroidView] drive the surrounding pager.
 *
 * Compose dispatches pointer events to the root first in [PointerEventPass.Initial], so consuming
 * the change there stops the interop view from ever seeing it. Nothing is consumed until the
 * movement is unmistakably horizontal, which leaves vertical list scrolling and taps untouched.
 */
private fun Modifier.horizontalSwipeToPager(
    state: androidx.compose.foundation.pager.PagerState,
    scope: kotlinx.coroutines.CoroutineScope
): Modifier =
    this.pointerInput(state, scope) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var claimed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val stepX = change.position.x - change.previousPosition.x
                val stepY = change.position.y - change.previousPosition.y
                if (!claimed) {
                    dx += stepX
                    dy += stepY
                    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                        claimed = true
                    }
                }
                if (claimed) {
                    change.consume()
                    // Raw delta is synchronous, so the page tracks the finger without needing a
                    // suspending scroll call that this restricted gesture scope cannot make.
                    state.dispatchRawDelta(-stepX)
                }
            }
            if (!claimed) return@awaitEachGesture
            val settled = when {
                dx < 0 -> state.currentPage + 1
                dx > 0 -> state.currentPage - 1
                else -> state.currentPage
            }.coerceIn(0, state.pageCount - 1)
            val far = kotlin.math.abs(dx) > size.width * 0.18f
            val target = if (far) settled else state.currentPage
            scope.launch { state.animateScrollToPage(target) }
        }
    }
