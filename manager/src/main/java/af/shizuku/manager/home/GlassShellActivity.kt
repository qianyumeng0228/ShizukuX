package af.shizuku.manager.home

import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.app.GlassBackground
import af.shizuku.manager.databinding.TerminalTutorialActivityBinding
import af.shizuku.manager.home.compose.GlassBottomBar
import af.shizuku.manager.home.compose.ShizukuLiquidTabs
import af.shizuku.manager.home.compose.ClassicBottomBar
import af.shizuku.manager.home.compose.LocalLiquidGlassBackdrop
import af.shizuku.manager.home.compose.LiquidGlassBackdropHolder
import af.shizuku.manager.home.compose.GlassLens
import af.shizuku.manager.home.compose.GlassPageScaffold
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

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

    /** Lets a card already inside this container switch pages without stacking another activity. */
    fun requestTab(index: Int) {
        if (index in GlassTabs.INDEX_HOME..GlassTabs.INDEX_SETTINGS) {
            requestedPage.intValue = index
        }
    }
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
    //
    // A row's context chain ends at this activity, so this class — not the controller — is the
    // host a row actually gets. filterIsHidden has to be forwarded here too, otherwise the row
    // reads the interface default and keeps offering "hide" while the list shows hidden apps.
    override val filterIsHidden: Boolean get() = appsCtl.filterIsHidden

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
        // Compose pages choose their own frame rate through RenderThread, but the settings page is
        // a native RecyclerView inside AndroidView and the system was content to keep it at 60 Hz
        // while the rest of the app ran at 120 Hz. preferredRefreshRate is available since API 21;
        // the explicit View hint only exists on API 35+, so guard it separately.
        window.attributes = window.attributes.apply { preferredRefreshRate = 120f }
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            window.decorView.setRequestedFrameRate(120f)
        }
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
                forceGlass = ShizukuSettings.isGlassUi(),
                // The shell owns a recorded background layer so the 24dp navigation lens can
                // sample wallpaper + page. Letting AppTheme draw it too was a second full-screen
                // bitmap under every home card.
                drawGlassWallpaper = false
            ) {
                Shell()
            }
        }
    }

    @Composable
    private fun Shell() {
        val pagerState = rememberPagerState(
            initialPage = requestedPage.intValue,
            pageCount = { 6 }
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
                else LiquidGlassBackdropHolder.setScrolling(false)
            }
        }
        val glass = ShizukuSettings.isGlassUi()
        val glassScrolling = glass && LiquidGlassBackdropHolder.isScrolling
        val barHidden = settingsSearching.value &&
            pagerState.currentPage == GlassTabs.INDEX_SETTINGS
        val navigationBarInset = WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()
        val classicBarSpace = 80.dp + navigationBarInset
        // Two independent recordings prevent glass surfaces from sampling themselves:
        // background-only for cards, background + page for the navigation tray and moving lens.
        val lensActive = glass && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (glass) Modifier
                    else Modifier.background(MaterialTheme.colorScheme.background)
                )
        ) {
            val backgroundBackdrop = rememberLayerBackdrop()
            val pageBackdrop = rememberLayerBackdrop()
            val navigationBackdrop = rememberCombinedBackdrop(backgroundBackdrop, pageBackdrop)
            DisposableEffect(backgroundBackdrop) {
                LiquidGlassBackdropHolder.background =
                    if (lensActive) backgroundBackdrop else null
                onDispose {
                    if (LiquidGlassBackdropHolder.background === backgroundBackdrop) {
                        LiquidGlassBackdropHolder.background = null
                    }
                }
            }
            if (glass) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .let { if (lensActive) it.layerBackdrop(backgroundBackdrop) else it }
                ) {
                    GlassBackground {}
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = if (!glass && !barHidden) classicBarSpace else 0.dp)
            ) {
                // PERF/visual: the page layer must not include the navigation tray itself.
                // Recording the bar inside pageBackdrop made the tray sample a copy of its own
                // previous frame, which showed as a second, trailing bar under the real one.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .let {
                            if (lensActive && !glassScrolling) it.layerBackdrop(pageBackdrop)
                            else it
                        }
                ) {
                CompositionLocalProvider(
                    LocalLiquidGlassBackdrop provides if (lensActive) backgroundBackdrop else null
                ) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                when (page) {
                    // The "Authorize" page hosts an AndroidView whose rows (and the "toggle all"
                    // header most of all) consume ACTION_MOVE, so a horizontal drag that starts on
                    // that band never reaches the pager and the page refuses to move. Claim the
                    // gesture in the Initial pass - before the interop view sees it - and only once
                    // the direction is clearly horizontal, so vertical list scrolling keeps working.
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
                            showTabBar = false,
                            selectedTab = GlassTabs.INDEX_AUTHORIZE
                        )
                    }
                    2 -> ModulesPage()
                    3 -> TerminalPage()
                    // The new "Apps" destination: a card catalogue of the capabilities built into
                    // ShizukuX, in the same shape as the bundled-tools grid users already know.
                    4 -> af.shizuku.manager.apps.AppsPage(
                        onSelectTab = { scope.launch { pagerState.animateScrollToPage(it) } },
                        showTabBar = false
                    )
                    5 -> SettingsPage(settingsSearching)
                    // Home hosts an AndroidView too: once its card list can scroll vertically,
                    // the RecyclerView competes for a horizontal drag and the pager only gets the
                    // leftover travel, which is what makes leaving Home feel damped. Claim the
                    // gesture in the Initial pass exactly like the Authorize page does; claims only
                    // happen once the movement is unmistakably horizontal, so list scrolling
                    // and long-press drag stay untouched.
                    0 -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .horizontalSwipeToPager(pagerState, scope)
                    ) {
                        HomePage(pagerScrollTo = { scope.launch { pagerState.animateScrollToPage(it) } })
                    }
                    // Every index 0..5 is a real page, so this only guards a future tab without
                    // content: render nothing rather than a placeholder that claims reachability.
                    else -> {}
                }
                }
                }
            }
            }

            // Same fade the XML pages get from GlassTabBar.attach, but for every page at once:
            // without it a hosted page's last cards run under the pill and stay readable through
            // the glass instead of retiring before they reach it. The new wallpaper is much calmer,
            // so this is a light fade rather than the old opaque shelf.
            val dark = isSystemInDarkTheme()
            val scrim = if (dark) {
                if (ThemeHelper.isBlackNightTheme(this@GlassShellActivity)) Color.Black else Color(0xFF080B12)
            } else {
                Color(0xFFDDE4F6)
            }
            if (!barHidden) {
                if (glass) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.45f to scrim.copy(alpha = 0.10f),
                                    1f to scrim.copy(alpha = 0.90f)
                                )
                            )
                    )
                }

                if (glass && lensActive && !glassScrolling) {
                    // Use the upstream component itself here. Its DampedDragAnimation owns the
                    // horizontal drag, the 64dp tray and the moving 56dp lens, so there is no
                    // parent pointerInput competing with six icon clickables any more.
                    ShizukuLiquidTabs(
                        // Pass the state read itself, not its current Int. LiquidBottomTabs
                        // collects this inside snapshotFlow; a captured value would freeze the
                        // moving lens at the old page while the page itself had already moved.
                        selectedIndex = { pagerState.currentPage },
                        onSelectTab = { page ->
                            scope.launch { pagerState.animateScrollToPage(page) }
                        },
                        // Only refract the quiet wallpaper, never the scrolling page. Sampling
                        // the page made the tray keep a blurred copy of the cards that visibly
                        // trailed the list ("拖影") and picked up the red warning card as a halo.
                        backdrop = backgroundBackdrop,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                start = 18.dp,
                                end = 18.dp,
                                bottom = 20.dp + navigationBarInset
                            )
                    )
                } else if (glass) {
                    // API < 33 has no AGSL Backdrop. Keep the same capsule geometry, but use the
                    // static low-alpha fallback instead of pretending to refract.
                    GlassBottomBar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                start = 18.dp,
                                end = 18.dp,
                                bottom = 20.dp + navigationBarInset
                            ),
                        selectedIndex = pagerState.currentPage,
                        liquidBackdrop = null,
                        onHomeClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_HOME) } },
                        onAuthorizeClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_AUTHORIZE) } },
                        onModulesClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_MODULES) } },
                        onTerminalClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_TERMINAL) } },
                        onAppsClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_APPS) } },
                        onSettingsClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_SETTINGS) } }
                    )
                } else {
                    ClassicBottomBar(
                        selectedIndex = pagerState.currentPage,
                        onSelectTab = { page ->
                            scope.launch { pagerState.animateScrollToPage(page) }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
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
            onAuthorizeClick = { pagerScrollTo(GlassTabs.INDEX_AUTHORIZE) },
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
            showTabBar = false,
            catalogLocalization = modulesCtl.catalogLocalization
        )
    }

    @Composable
    private fun TerminalPage() {
        val context = LocalContext.current
        // Same shared header as Modules/Apps/Authorize. The guide page has no app bar of its own;
        // inside the pager its old XML toolbar was dead weight above the first card.
        GlassPageScaffold(
            title = androidx.compose.ui.res.stringResource(R.string.home_terminal_title),
            showTabBar = false,
            selectedTab = af.shizuku.manager.utils.GlassTabs.INDEX_TERMINAL
        ) { innerPadding ->
            AndroidView(
                factory = { ctx ->
                    TerminalTutorialActivityBinding.inflate(LayoutInflater.from(ctx), null, false).also { b ->
                        buildTerminalPage(ctx, b, onPickExportDir = { exportLauncher.launch(null) })
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
                    .padding(innerPadding)
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
        if (page in 0..5) requestedPage.intValue = page
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
            // Where the gesture began. currentPage moves by itself once a drag crosses half a
            // page, so committing from it at release would advance twice on a long swipe.
            val startPage = state.currentPage
            var dx = 0f
            var dy = 0f
            var travelled = 0f
            var claimed = false
            var lastX = down.position.x
            var lastTime = down.uptimeMillis
            var velocity = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    // Once this gesture belongs to the pager, the UP has to be consumed too. Left
                    // alone, the hosted AndroidView sees a DOWN followed by an UP with no MOVE
                    // between them and treats it as a click - pressing a home card or a row
                    // fires its click feedback at the end of every swipe.
                    if (claimed) change.consume()
                    break
                }
                val stepX = change.position.x - change.previousPosition.x
                val stepY = change.position.y - change.previousPosition.y
                // Count every event exactly once. The claim test needs the total before it flips,
                // and the pager/target math below needs every step after it; adding in both
                // branches made the claiming event contribute twice.
                dx += stepX
                if (!claimed) {
                    dy += stepY
                    // Same rule the pager's own drag uses: past touch slop and clearly horizontal.
                    // The old 1.2x margin made a slightly slanted drag do nothing for the first
                    // stretch of travel, which read as the page being heavier than its neighbours.
                    // 0.7 slop, not the full touch slop: the page stays put for the whole slop
                    // distance otherwise, and that dead zone is what reads as "have to push
                    // harder to get it started". Still well past a jittery tap.
                    if (kotlin.math.abs(dx) > slop * 0.7f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        claimed = true
                        // The card under the finger got a DOWN and will never see a MOVE now, so
                        // its pressed scale has to be dropped here; otherwise it stays shrunk
                        // for the entire swipe and the page looks held back.
                        af.shizuku.manager.utils.MotionUtils.releaseActivePress()
                    }
                }
                if (claimed) {
                    // Total travel, not only the pre-claim part. The release decision reads it,
                    // and it used to freeze at touch slop, so nothing but a fast flick could
                    // ever commit - everything slower snapped back, which felt like the page
                    // refusing to move without force.
                    change.consume()
                    // One full page of slack. The commit below prefers the page the finger
                    // actually ended on, so a full-page drag cannot walk two pages, and the
                    // content tracks the finger 1:1 instead of dying out halfway.
                    // Viewport width is 0 until the first layout pass, and a swipe issued right
                    // after launch could therefore clamp every step to 0 and do nothing.
                    val pageWidth = state.layoutInfo.viewportSize.width.takeIf { it > 0 } ?: size.width
                    val room = pageWidth - kotlin.math.abs(travelled)
                    val step = if (room <= 0f) 0f else stepX.coerceIn(-room, room)
                    travelled += step
                    // Raw delta is synchronous, so the page tracks the finger without needing a
                    // suspending scroll call that this restricted gesture scope cannot make.
                    state.dispatchRawDelta(-step)
                    val elapsed = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                    velocity = (change.position.x - lastX) / elapsed
                    lastX = change.position.x
                    lastTime = change.uptimeMillis
                }
            }
            if (!claimed) return@awaitEachGesture
            val settled = when {
                dx < 0 -> startPage + 1
                dx > 0 -> startPage - 1
                else -> startPage
            }.coerceIn(0, state.pageCount - 1)
            // A flick commits even when the finger travelled less than a quarter of a page, which
            // is what makes the page feel as light as the ones the pager drags itself. Without it
            // a quick swipe below the threshold snapped back, and that snap is the "damping" users
            // could feel only between the two pages that carry this gesture.
            // Thresholds close to the pager's own: a slow drag only has to cover ~18% of the
            // viewport, and a flick commits at a much gentler speed than before. The old values
            // demanded either a hard flick or a long shove.
            val flicked = kotlin.math.abs(velocity) > 0.5f
            val far = kotlin.math.abs(dx) > size.width * 0.18f
            val target = when {
                // The drag already carried the pager past its neighbour's midpoint, so that
                // neighbour is the page under the finger; committing elsewhere would jump back.
                state.currentPage != startPage -> state.currentPage
                flicked || far -> settled
                else -> startPage
            }
            // A thrown page settles faster than a dragged one. ThisCompose's
            // animateScrollToPage takes no initial velocity, so the nearest equivalent is a
            // shorter spec: the old single 260ms spring made every gesture end with the same
            // slow crawl.
            scope.launch {
                state.animateScrollToPage(
                    target,
                    animationSpec = tween(
                        durationMillis = if (flicked) 170 else 240,
                        easing = FastOutSlowInEasing
                    )
                )
            }
        }
    }
