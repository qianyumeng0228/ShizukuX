package af.shizuku.manager

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.Surface as TvSurface
import androidx.tv.material3.Text as TvText
import af.shizuku.manager.adb.TvAdbPairingTutorialScreen
import af.shizuku.manager.home.HomeScreen
import af.shizuku.manager.home.HomeState
import af.shizuku.manager.home.HomeViewModel
import af.shizuku.manager.home.LocalNetworkPermissionState
import af.shizuku.manager.management.AppsViewModel
import af.shizuku.manager.management.TvApplicationManagementScreen
import af.shizuku.manager.module.AdbModule
import af.shizuku.manager.module.AdbModuleManager
import af.shizuku.manager.module.ModuleCheckReport
import af.shizuku.manager.module.ModuleHealthStatus
import af.shizuku.manager.module.ModuleSettings
import af.shizuku.manager.module.TvModulesScreen
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.settings.TvLabMenuScreen
import af.shizuku.manager.settings.TvSettingsScreen
import af.shizuku.manager.shell.TvShellTutorialScreen
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.ui.compose.TvShizukuTheme
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.UserHandleCompat
import af.shizuku.manager.Helps
import af.shizuku.manager.authorization.AuthorizationManager
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.airbnb.mvrx.Async
import com.airbnb.mvrx.Fail
import com.airbnb.mvrx.Loading
import com.airbnb.mvrx.MavericksView
import com.airbnb.mvrx.Success
import com.airbnb.mvrx.Uninitialized
import com.airbnb.mvrx.viewModel
import kotlinx.coroutines.launch
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * TV launcher host (declared in the manifest with LEANBACK_LAUNCHER). Single-Activity
 * navigation over the TV screens ported from Nightzuku (P2-4). The phone Home keeps
 * its existing hybrid architecture untouched; TV gets its own Compose host.
 */
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalMaterial3Api::class)
class TvActivity : AppCompatActivity(), MavericksView {

    private val homeModel: HomeViewModel by viewModel()
    private val appsModel: AppsViewModel by viewModels()

    private var screen by mutableStateOf(TvScreen.HOME)
    private var showAboutDialog by mutableStateOf(false)
    private var showStopDialog by mutableStateOf(false)
    private var showAdbCommandDialog by mutableStateOf(false)

    private var granted by mutableStateOf<Resource<Int>?>(null)
    private var packages by mutableStateOf<List<PackageInfo>>(emptyList())
    private var serviceStatus by mutableStateOf<Async<ServiceStatus>>(Uninitialized)

    private var modules by mutableStateOf<List<AdbModule>>(emptyList())
    private var moduleHealth by mutableStateOf<Map<String, ModuleHealthStatus>>(emptyMap())
    private var moduleCheckReport by mutableStateOf<ModuleCheckReport?>(null)
    private var moduleTick by mutableStateOf(0)
    private var isCheckingModules by mutableStateOf(false)

    private val installZipLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                runCatching { AdbModuleManager.install(applicationContext, uri) }
                    .onSuccess { refreshModules() }
                    .onFailure { Timber.e(it, "TV module install failed") }
            }
        }
    }

    private val grantedObserver = androidx.lifecycle.Observer<Resource<Int>> { granted = it }
    private val packagesObserver = androidx.lifecycle.Observer<Resource<List<PackageInfo>>> {
        packages = it?.data?.orEmpty().orEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreenCompat()
        appsModel.grantedCount.observeForever(grantedObserver)
        appsModel.packages.observeForever(packagesObserver)
        homeModel.onEach(HomeState::serviceStatus) { serviceStatus = it }
        setContent {
            TvShizukuTheme {
                BackHandler(enabled = screen != TvScreen.HOME) { screen = TvScreen.HOME }
                when (screen) {
                    TvScreen.HOME -> HomeContent()
                    TvScreen.MODULES -> TvModulesContent()
                    TvScreen.SETTINGS -> TvSettingsContent()
                    TvScreen.APPS -> TvAppsContent()
                    TvScreen.TERMINAL -> TvTerminalContent()
                    TvScreen.LAB -> TvLabContent()
                    TvScreen.ADB_TUTORIAL -> TvAdbTutorialContent()
                }
            }
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    private fun installSplashScreenCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            installSplashScreen()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        homeModel.reload()
        refreshModules()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        homeModel.reload()
    }

    override fun invalidate() {
        // Mavericks state changed - individual property observers (onEach) handle specific updates
    }

    override fun onResume() {
        super.onResume()
        homeModel.reload()
        if (screen == TvScreen.APPS) appsModel.load()
    }

    override fun onDestroy() {
        appsModel.grantedCount.removeObserver(grantedObserver)
        appsModel.packages.removeObserver(packagesObserver)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        super.onDestroy()
    }

    private fun refreshModules() {
        lifecycleScope.launch {
            modules = AdbModuleManager.listModules(applicationContext)
            moduleTick++
        }
    }

    private fun buildCheckReport() {
        lifecycleScope.launch {
            isCheckingModules = true
            val checked = AdbModuleManager.listModules(applicationContext).map {
                it.id to AdbModuleManager.inspectModuleHealth(it)
            }.toMap()
            moduleHealth = checked
            val healthy = checked.values.count { it.healthy }
            moduleCheckReport = ModuleCheckReport(
                healthyModules = healthy,
                totalModules = checked.size,
                updatesAvailableCount = 0,
                issuesCount = checked.size - healthy,
                healthByModule = checked,
                updatesByModule = emptyMap()
            )
            isCheckingModules = false
        }
    }

    @Composable
    private fun HomeContent() {
        val context = LocalContext.current
        val localNetwork = remember {
            buildLocalNetworkPermissionState()
        }
        HomeScreen(
            serviceResource = serviceStatus.toRikkaResource(),
            grantedResource = granted,
            localNetworkPermissionState = localNetwork,
            isPrimaryUser = UserHandleCompat.myUserId() == 0,
            isRooted = EnvironmentUtils.isRooted(),
            onRefresh = {
                homeModel.reload()
                appsModel.load()
                refreshModules()
            },
            onSettings = { screen = TvScreen.SETTINGS },
            onAbout = { showAboutDialog = true },
            onStop = { showStopDialog = true },
            onModules = {
                refreshModules()
                screen = TvScreen.MODULES
            },
            onManageApps = {
                appsModel.load()
                screen = TvScreen.APPS
            },
            onTerminal = { screen = TvScreen.TERMINAL },
            onStartRoot = { startActivity(Intent(this@TvActivity, af.shizuku.manager.starter.StarterActivity::class.java)) },
            onStartWirelessAdb = { startActivity(Intent(this@TvActivity, af.shizuku.manager.starter.StarterActivity::class.java)) },
            onPairWirelessAdb = { startActivity(Intent(this@TvActivity, af.shizuku.manager.adb.AdbPairingDialogActivity::class.java)) },
            onOpenWirelessGuide = { CustomTabsHelper.launchUrlOrCopy(context, Helps.ADB_ANDROID11.get()) },
            onShowAdbCommand = { showAdbCommandDialog = true },
            onOpenAdbHelp = { CustomTabsHelper.launchUrlOrCopy(context, Helps.ADB.get()) },
            onOpenAdbPermissionHelp = { CustomTabsHelper.launchUrlOrCopy(context, Helps.ADB_PERMISSION.get()) },
            onLearnMore = { CustomTabsHelper.launchUrlOrCopy(context, Helps.HOME.get()) },
            onCopyDiagnostics = { copyDiagnostics(it) },
            onRequestLocalNetworkPermission = { /* local network permission flows are phone-specific */ }
        )

        if (showAboutDialog) {
            TvAboutDialog(onDismiss = { showAboutDialog = false })
        }
        if (showStopDialog) {
            TvStopDialog(
                onDismiss = { showStopDialog = false },
                onConfirm = {
                    showStopDialog = false
                    runCatching { Shizuku.exit() }
                    homeModel.reload()
                }
            )
        }
        if (showAdbCommandDialog) {
            TvAdbCommandDialog(
                command = Starter.adbCommand,
                onDismiss = { showAdbCommandDialog = false },
                onCopy = {
                    copyDiagnostics(Starter.adbCommand)
                    showAdbCommandDialog = false
                },
                onTutorial = {
                    showAdbCommandDialog = false
                    screen = TvScreen.ADB_TUTORIAL
                }
            )
        }
    }

    @Composable
    private fun TvModulesContent() {
        TvModulesScreen(
            modules = modules,
            isCheckingModules = isCheckingModules,
            checkReport = moduleCheckReport,
            onNavigateUp = { screen = TvScreen.HOME },
            onInstallZip = { installZipLauncher.launch(arrayOf("application/zip", "application/vnd.android.package-archive")) },
            onCheckModules = { buildCheckReport() },
            onUpdateAllModules = { /* no remote update source in ShizukuX */ },
            onUpdateModule = { _, _ -> },
            onToggle = { module ->
                lifecycleScope.launch {
                    runCatching { AdbModuleManager.setEnabled(module, !module.enabled) }
                    refreshModules()
                }
            },
            onRunAction = { module ->
                lifecycleScope.launch {
                    runCatching { AdbModuleManager.runAction(module) }
                }
            },
            onRunService = { module ->
                lifecycleScope.launch {
                    runCatching { AdbModuleManager.runService(module) }
                }
            },
            onDelete = { module ->
                lifecycleScope.launch {
                    runCatching { AdbModuleManager.delete(module) }
                    refreshModules()
                }
            },
            onTrustChange = { module, trusted ->
                ModuleSettings.setModuleTrusted(module.id, trusted)
            },
            onOpenWebUi = { module ->
                module.webRoot?.let {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.fromFile(it.resolve("index.html") ?: it)))
                    }
                }
            },
            onOpenCatalog = { /* no module marketplace in ShizukuX */ },
            onLabFeatures = { screen = TvScreen.LAB }
        )
    }

    @Composable
    private fun TvSettingsContent() {
        val context = LocalContext.current
        TvSettingsScreen(
            onNavigateUp = { screen = TvScreen.HOME },
            startOnBoot = ShizukuSettings.getStartOnBoot(context),
            onStartOnBootChange = { ShizukuSettings.setStartOnBoot(context, it) },
            languageSummary = java.util.Locale.getDefault().getDisplayName(java.util.Locale.getDefault()),
            onLanguageClick = { /* language selection is phone-settings only for now */ },
            nightSummary = "",
            onNightModeClick = { /* night mode dialog is phone-settings only */ },
            blackNightTheme = false,
            onBlackNightThemeChange = { },
            useSystemColor = false,
            onUseSystemColorChange = { },
            moduleAccessMode = ModuleSettings.getAccessMode(),
            onModuleAccessModeClick = { /* access mode picker is phone-settings only for now */ },
            allowBackgroundActions = ModuleSettings.allowBackgroundActions(),
            onAllowBackgroundActionsChange = { ModuleSettings.setAllowBackgroundActions(it) },
            backgroundCheckEnabled = false,
            onBackgroundCheckEnabledChange = { },
            recommandWebUi = false,
            onRecommandWebUiChange = { },
            recommandAction = false,
            onRecommandActionChange = { },
            onLabFeaturesClick = { screen = TvScreen.LAB },
            onGitHubTokenClick = { /* no GitHub update source in ShizukuX */ }
        )
    }

    @Composable
    private fun TvAppsContent() {
        val list = packages
        LaunchedEffect(Unit) { appsModel.load() }
        TvApplicationManagementScreen(
            packages = list,
            tick = moduleTick,
            onNavigateUp = { screen = TvScreen.HOME },
            onToggle = { pkg ->
                lifecycleScope.launch {
                    val uid = runCatching { pkg.applicationInfo?.uid }.getOrDefault(-1)
                    if (uid != null && uid > 0) {
                        if (AuthorizationManager.granted(pkg.packageName, uid)) {
                            AuthorizationManager.revoke(pkg.packageName, uid)
                        } else {
                            AuthorizationManager.grant(pkg.packageName, uid)
                        }
                        appsModel.load()
                    }
                }
            },
            onSelectAll = { selectAll ->
                lifecycleScope.launch {
                    list.forEach { pkg ->
                        val uid = runCatching { pkg.applicationInfo?.uid }.getOrDefault(-1)
                        if (uid != null && uid > 0) {
                            if (selectAll) AuthorizationManager.grant(pkg.packageName, uid)
                            else AuthorizationManager.revoke(pkg.packageName, uid)
                        }
                    }
                    appsModel.load()
                }
            }
        )
    }

    @Composable
    private fun TvTerminalContent() {
        val context = LocalContext.current
        TvShellTutorialScreen(
            onNavigateUp = { screen = TvScreen.HOME },
            shName = "rish",
            dexName = "shizuku.dex",
            onExportFiles = { /* file export is phone-settings only for now */ },
            onViewHelp = { CustomTabsHelper.launchUrlOrCopy(context, Helps.RISH.get()) }
        )
    }

    @Composable
    private fun TvLabContent() {
        TvLabMenuScreen(onNavigateUp = { screen = TvScreen.HOME })
    }

    @Composable
    private fun TvAdbTutorialContent() {
        val context = LocalContext.current
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        TvAdbPairingTutorialScreen(
            onNavigateUp = { screen = TvScreen.HOME },
            notificationEnabled = notificationManager.areNotificationsEnabled(),
            onOpenNotificationSettings = {
                runCatching {
                    startActivity(Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", packageName))
                }
            },
            onOpenDeveloperOptions = {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
            },
            onFinish = { screen = TvScreen.HOME }
        )
    }

    private fun buildLocalNetworkPermissionState(): LocalNetworkPermissionState {
        val required = EnvironmentUtils.getAdbTcpPort() <= 0
        return LocalNetworkPermissionState(
            permission = null,
            required = required,
            granted = true
        )
    }

    private fun copyDiagnostics(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("shizuku-diagnostics", text))
        Toast.makeText(this, R.string.home_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun Async<ServiceStatus>.toRikkaResource(): Resource<ServiceStatus>? = when (this) {
        is Uninitialized -> null
        is Loading -> Resource.success(ServiceStatus())
        is Success -> Resource.success(invoke())
        is Fail -> Resource.error(error)
    }

    @Composable
    private fun TvAboutDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { TvText(stringResource(R.string.app_name)) },
            text = {
                TvText(
                    text = "ShizukuX ${BuildConfig.VERSION_NAME}",
                    style = TvMaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TvSurface(
                    onClick = onDismiss,
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.ok),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            shape = TvMaterialTheme.shapes.extraLarge
        )
    }

    @Composable
    private fun TvStopDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { TvText(stringResource(R.string.action_stop)) },
            text = { TvText(stringResource(R.string.dialog_stop_message)) },
            confirmButton = {
                TvSurface(
                    onClick = onConfirm,
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.ok),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            dismissButton = {
                TvSurface(
                    onClick = onDismiss,
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    TvText(
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            shape = TvMaterialTheme.shapes.extraLarge
        )
    }

    @Composable
    private fun TvAdbCommandDialog(
        command: String,
        onDismiss: () -> Unit,
        onCopy: () -> Unit,
        onTutorial: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { TvText(stringResource(R.string.home_adb_title)) },
            text = {
                TvText(
                    text = command,
                    style = TvMaterialTheme.typography.bodyLarge
                )
            },
            confirmButton = {
                TvSurface(
                    onClick = onCopy,
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    TvText(
                        text = stringResource(R.string.home_diagnostics_copy),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            dismissButton = {
                TvSurface(
                    onClick = onTutorial,
                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(TvMaterialTheme.shapes.small),
                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                        containerColor = TvMaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    TvText(
                        text = stringResource(R.string.home_wireless_adb_view_guide_button),
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
            },
            containerColor = TvMaterialTheme.colorScheme.surfaceVariant,
            shape = TvMaterialTheme.shapes.extraLarge
        )
    }
}

private enum class TvScreen {
    HOME, MODULES, SETTINGS, APPS, TERMINAL, LAB, ADB_TUTORIAL
}
