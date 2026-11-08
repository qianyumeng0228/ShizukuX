package af.shizuku.manager.home

import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.databinding.TerminalTutorialActivityBinding
import af.shizuku.manager.home.compose.GlassBottomBar
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
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    private val appsCtl by lazy { AppsController(this) }

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
        // A tab tap that arrives while this container is already running only changes requestedPage;
        // this turns that into the same animation the in-container bar uses.
        androidx.compose.runtime.LaunchedEffect(requestedPage.intValue) {
            pagerState.animateScrollToPage(requestedPage.intValue)
        }
        // currentPage + offset is exactly the continuous 0..4 position the pill expects, which is
        // why the bar gained a positionFraction parameter instead of animating on its own.
        val positionFraction = pagerState.currentPage + pagerState.currentPageOffsetFraction

        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    1 -> AppsPage(
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
                    2 -> ModulesPage()
                    3 -> TerminalPage()
                    4 -> SettingsPage()
                    0 -> HomePage(pagerScrollTo = { scope.launch { pagerState.animateScrollToPage(it) } })
                    else -> PendingPage(page)
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

            GlassBottomBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp),
                selectedIndex = pagerState.currentPage,
                positionFraction = positionFraction,
                onHomeClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_HOME) } },
                onAppsClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_APPS) } },
                onModulesClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_MODULES) } },
                onTerminalClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_TERMINAL) } },
                onSettingsClick = { scope.launch { pagerState.animateScrollToPage(GlassTabs.INDEX_SETTINGS) } }
            )
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
                af.shizuku.manager.utils.ShizukuStateMachine.set(
                    af.shizuku.manager.utils.ShizukuStateMachine.State.STOPPING
                )
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
    private fun SettingsPage() {
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
            showTabBar = false
        )
    }

    @Composable
    private fun PendingPage(index: Int) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 132.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (index == GlassTabs.INDEX_HOME) "首页" else "应用",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "这一页尚未迁入单容器\n仍从底栏进入独立页面",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
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
