package af.shizuku.manager.home

import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.databinding.TerminalTutorialActivityBinding
import af.shizuku.manager.home.compose.GlassBottomBar
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The single container the five destinations are being folded into.
 *
 * Every destination used to be its own activity, so switching tabs started a new activity: the
 * content was replaced instead of sliding, and the floating pill re-seated itself instead of
 * following the finger. Here the five pages live in one [HorizontalPager], the pill is drawn once
 * by [GlassBottomBar] and fed the pager's live position, so both move continuously.
 *
 * Not wired to the launcher yet: home and apps still have to leave their activity-bound state
 * behind, and until they do, opening this from the tab bar would show two placeholder pages.
 */
class GlassShellActivity : FragmentActivity() {

    private val settingsCtl by lazy { SettingsController(this) }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        settingsCtl.observeBackStack()
        modulesCtl.refresh()

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
        val pagerState = rememberPagerState(pageCount = { 5 })
        val scope = rememberCoroutineScope()
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
                    2 -> ModulesPage()
                    3 -> TerminalPage()
                    4 -> SettingsPage()
                    else -> PendingPage(page)
                }
            }

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
        AndroidView(
            factory = { ctx ->
                TerminalTutorialActivityBinding.inflate(LayoutInflater.from(ctx), null, false).also { b ->
                    buildTerminalPage(ctx, b, onPickExportDir = { exportLauncher.launch(null) })
                    // The activity host used to paint this page's own background; inside the pager
                    // that would punch an opaque block through the shared glass gradient, so the
                    // inflated root has to stay transparent.
                    b.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }.root
            },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 116.dp)
        )
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
}
