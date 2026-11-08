package af.shizuku.manager.module

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.ui.compose.ShizukuIcon
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Phone-side entry point for the ADB module system.
 *
 * ShizukuX has shipped a complete [AdbModuleManager] (install / enable / trust / action /
 * service / health check / delete) for a while, but the only screen wired to it was the
 * TV variant, so phone users could not reach any of it. This activity is that missing
 * entry point and deliberately reuses the same manager, policy ([ModuleSettings]) and
 * strings instead of growing a second implementation.
 */
class ModulesActivity : ComponentActivity() {

    // All page state lives in the controller so the single-container shell can host the same
    // screen later; this activity is now only the standalone host.
    private val ctl by lazy {
        ModulesController(this, lifecycleScope) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private val installLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) ctl.install(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                ModulesScreen(
                    modules = ctl.modules,
                    health = ctl.health,
                    healthChecked = ctl.healthChecked,
                    trustedIds = ctl.trustedIds,
                    loaded = ctl.loaded,
                    installing = ctl.installing,
                    checking = ctl.checking,
                    busyModule = ctl.busyModule,
                    serviceRunning = ctl.serviceRunning(),
                    message = ctl.message,
                    output = ctl.output,
                    pendingDelete = ctl.pendingDelete,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    // Modules is a peer destination, not a sub page: no up arrow regardless of how
                    // the page was entered.
                    showBack = false,
                    onSelectTab = { af.shizuku.manager.utils.GlassTabs.open(this, it) },
                    onInstall = { installLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onCheck = { ctl.checkHealth() },
                    onToggle = { ctl.toggle(it) },
                    onTrust = { module, trusted -> ctl.setTrusted(module, trusted) },
                    onRunAction = { ctl.runScript(it, isService = false) },
                    onRunService = { ctl.runScript(it, isService = true) },
                    onOpenWebUi = { module ->
                        module.webRoot?.let { startActivity(ModuleWebUiActivity.intent(this, module)) }
                    },
                    onDelete = { ctl.askDelete(it) },
                    onDismissMessage = { ctl.dismissMessage() },
                    onDismissOutput = { ctl.dismissOutput() },
                    onConfirmDelete = { ctl.delete(it) },
                    onCancelDelete = { ctl.cancelDelete() }
                )
            }
        }
        ctl.refresh()

        // Tab switches should read as pages sliding sideways, not as a bare activity swap. The
        // app-bar activities already get this from MaterialSharedAxis; these Compose pages had no
        // transition at all.
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.glass_tab_in_right, R.anim.glass_tab_out_left)
    }

    override fun onResume() {
        super.onResume()
        ctl.onHostResumed()
    }
}

data class ModuleOutput(
    val title: String,
    val result: ModuleActionResult
)

@Composable
internal fun ModulesScreen(
    modules: List<AdbModule>,
    health: Map<String, ModuleHealthStatus>,
    healthChecked: Boolean,
    trustedIds: Set<String>,
    loaded: Boolean,
    installing: Boolean,
    checking: Boolean,
    busyModule: String?,
    serviceRunning: Boolean,
    message: String?,
    output: ModuleOutput?,
    pendingDelete: AdbModule?,
    onBack: () -> Unit,
    showBack: Boolean = true,
    onSelectTab: (Int) -> Unit = {},
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    onToggle: (AdbModule) -> Unit,
    onTrust: (AdbModule, Boolean) -> Unit,
    onRunAction: (AdbModule) -> Unit,
    onRunService: (AdbModule) -> Unit,
    onOpenWebUi: (AdbModule) -> Unit,
    onDelete: (AdbModule) -> Unit,
    onDismissMessage: () -> Unit,
    onDismissOutput: () -> Unit,
    onConfirmDelete: (AdbModule) -> Unit,
    onCancelDelete: () -> Unit
) {
    Scaffold(
        // AppTheme already paints the shared glass gradient behind every Compose page; an opaque
        // Scaffold surface cut it off right below the app bar, leaving this page flat white.
        containerColor = Color.Transparent,
        bottomBar = {
            // Same floating bar as every other destination - leaving it off this page made a tab
            // switch look like navigating away from the app's tab strip.
            af.shizuku.manager.home.compose.GlassTabStrip(
                selectedIndex = af.shizuku.manager.utils.GlassTabs.INDEX_MODULES,
                onSelectTab = onSelectTab
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResourceCompat(R.string.modules_title),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = if (!loaded) {
                                stringResourceCompat(R.string.modules_settings_title)
                            } else {
                                stringResourceCompat(R.string.modules_count, modules.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    // The tab bar opens this page as a sibling of home, so the up arrow would be
                    // lying about the nav graph; the system back gesture still returns there.
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            ShizukuIcon(
                                icon = R.drawable.ic_arrow_back_24,
                                contentDescription = stringResourceCompat(R.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onInstall, enabled = !installing) {
                        ShizukuIcon(
                            icon = R.drawable.ic_install_24,
                            contentDescription = stringResourceCompat(R.string.modules_install_zip)
                        )
                    }
                    IconButton(onClick = onCheck, enabled = !checking) {
                        ShizukuIcon(
                            icon = R.drawable.ic_refresh_24,
                            contentDescription = stringResourceCompat(R.string.modules_background_check_short)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // AppTheme already paints the shared glass gradient behind every page; an
                    // opaque app-bar fill cut a flat white band across it, so this page read as a
                    // different material from the rest of the skin.
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(32.dp))
                }

                modules.isEmpty() -> EmptyModules(onInstall = onInstall, installing = installing)

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!serviceRunning) {
                        item {
                            NoticeStrip(
                                text = stringResourceCompat(R.string.modules_need_service),
                                tone = NoticeTone.Warn
                            )
                        }
                    }
                    if (installing) {
                        item {
                            NoticeStrip(
                                text = stringResourceCompat(R.string.modules_installing),
                                tone = NoticeTone.Neutral,
                                busy = true
                            )
                        }
                    }
                    if (checking) {
                        item {
                            NoticeStrip(
                                text = stringResourceCompat(R.string.modules_background_check_running),
                                tone = NoticeTone.Neutral,
                                busy = true
                            )
                        }
                    }
                    items(modules, key = { it.id }) { module ->
                        ModuleCard(
                            module = module,
                            health = if (healthChecked) health[module.id] else null,
                            trusted = module.id in trustedIds,
                            busy = busyModule == module.id,
                            serviceRunning = serviceRunning,
                            onToggle = { onToggle(module) },
                            onTrust = { onTrust(module, it) },
                            onRunAction = { onRunAction(module) },
                            onRunService = { onRunService(module) },
                            onOpenWebUi = { onOpenWebUi(module) },
                            onDelete = { onDelete(module) }
                        )
                    }
                }
            }
        }
    }

    // Transient failure surface: every manager call reports through here instead of failing silently.
    message?.let { text ->
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResourceCompat(R.string.modules_action_failed)) },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text(stringResourceCompat(R.string.action_ok)) }
            }
        )
    }

    output?.let { state ->
        OutputDialog(state = state, onDismiss = onDismissOutput)
    }

    pendingDelete?.let { module ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResourceCompat(R.string.modules_delete)) },
            text = { Text(stringResourceCompat(R.string.modules_delete_confirm, module.name)) },
            confirmButton = {
                TextButton(
                    onClick = { onConfirmDelete(module) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResourceCompat(R.string.modules_delete)) }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete) {
                    Text(stringResourceCompat(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun EmptyModules(onInstall: () -> Unit, installing: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ShizukuIcon(
            icon = R.drawable.ic_extension_24,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResourceCompat(R.string.modules_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResourceCompat(R.string.modules_empty_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onInstall, enabled = !installing) {
            ShizukuIcon(icon = R.drawable.ic_install_24, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResourceCompat(R.string.modules_install_zip))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModuleCard(
    module: AdbModule,
    health: ModuleHealthStatus?,
    trusted: Boolean,
    busy: Boolean,
    serviceRunning: Boolean,
    onToggle: () -> Unit,
    onTrust: (Boolean) -> Unit,
    onRunAction: () -> Unit,
    onRunService: () -> Unit,
    onOpenWebUi: () -> Unit,
    onDelete: () -> Unit
) {
    val canRun = ModuleSettings.canRunAction(module)
    val canService = ModuleSettings.canRunService(module) && ModuleSettings.canRunBackground(module)
    val blockedByPolicy = !canRun && !trusted

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = module.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(module.version, module.author, module.formattedSize)
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(start = 4.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(
                    text = stringResourceCompat(
                        if (module.enabled) R.string.modules_state_enabled else R.string.modules_state_disabled
                    ),
                    tone = if (module.enabled) ChipTone.Ok else ChipTone.Muted
                )
                if (trusted) StatusChip(text = stringResourceCompat(R.string.modules_trust), tone = ChipTone.Ok)
                health?.let {
                    StatusChip(
                        text = if (it.healthy) stringResourceCompat(R.string.modules_healthy)
                        else it.issues.firstOrNull() ?: stringResourceCompat(R.string.modules_health_issue),
                        tone = if (it.healthy) ChipTone.Muted else ChipTone.Bad
                    )
                }
            }

            module.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (blockedByPolicy) {
                Text(
                    text = stringResourceCompat(R.string.modules_trust_required),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallAction(
                    label = stringResourceCompat(if (module.enabled) R.string.modules_disable else R.string.modules_enable),
                    enabled = !busy
                ) { onToggle() }

                SmallAction(
                    label = stringResourceCompat(if (trusted) R.string.modules_untrust else R.string.modules_trust),
                    enabled = !busy
                ) { onTrust(!trusted) }

                if (module.hasAction) {
                    SmallAction(
                        label = stringResourceCompat(R.string.modules_run_action),
                        enabled = !busy && canRun && serviceRunning
                    ) { onRunAction() }
                }

                if (module.hasService) {
                    SmallAction(
                        label = stringResourceCompat(R.string.modules_run_service),
                        enabled = !busy && canService && serviceRunning
                    ) { onRunService() }
                }

                if (module.hasWebUi) {
                    SmallAction(label = stringResourceCompat(R.string.modules_open_webui), enabled = !busy) { onOpenWebUi() }
                }
            }

            // Destructive action lives on its own right-aligned row: keeps it away from the
            // toggle/run cluster so it cannot be hit by accident when the row wraps.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SmallAction(
                    label = stringResourceCompat(R.string.modules_delete),
                    enabled = !busy,
                    danger = true
                ) { onDelete() }
            }
        }
    }
}

private enum class ChipTone { Ok, Muted, Bad }

@Composable
private fun StatusChip(text: String, tone: ChipTone) {
    val container = when (tone) {
        ChipTone.Ok -> MaterialTheme.colorScheme.primaryContainer
        ChipTone.Muted -> MaterialTheme.colorScheme.surfaceContainerHighest
        ChipTone.Bad -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        ChipTone.Ok -> MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
        ChipTone.Bad -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(shape = CircleShape, color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SmallAction(label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private enum class NoticeTone { Neutral, Warn }

@Composable
private fun NoticeStrip(text: String, tone: NoticeTone, busy: Boolean = false) {
    val container = when (tone) {
        NoticeTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHigh
        NoticeTone.Warn -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (tone) {
        NoticeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        NoticeTone.Warn -> MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OutputDialog(state: ModuleOutput, onDismiss: () -> Unit) {
    val scroll = rememberScrollState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResourceCompat(R.string.modules_exit_code, state.result.exitCode),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.result.exitCode == 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Text(
                    text = state.result.combinedOutput,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResourceCompat(R.string.action_ok)) }
        }
    )
}

@Composable
private fun stringResourceCompat(id: Int, vararg args: Any): String =
    androidx.compose.ui.res.stringResource(id, *args)
