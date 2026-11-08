package af.shizuku.manager.force_dark

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.firewall.PackageEnumerator
import af.shizuku.manager.hiding.HidingWatchService
import af.shizuku.manager.ui.compose.ShizukuIcon
import af.shizuku.manager.utils.AppIconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One app that can be opened, which is the whole population this screen is about.
 *
 * An app with nothing in the launcher is an app nobody sits in front of, and this rule only means
 * anything while something is in front - so those are left out rather than listed as dead switches.
 */
private data class ForceDarkApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val system: Boolean
)

/**
 * Per-app force dark: the whole-device switch held while a chosen app is on screen.
 *
 * Two switches on one page besides the per-app ones, because the two mechanisms behind force dark
 * are not the same thing and nothing here pretends otherwise: the system night mode is what
 * actually changes pixels (and darkens the whole phone with it), and the old property switches are
 * offered separately for phones and apps that still answer them.
 */
class ForceDarkActivity : ComponentActivity() {

    private var available by mutableStateOf(false)
    private var enabled by mutableStateOf(false)
    private var usesTheme by mutableStateOf(true)
    private var writesSwitches by mutableStateOf(false)
    private var apps by mutableStateOf<List<ForceDarkApp>>(emptyList())
    private var forced by mutableStateOf<Set<String>>(emptySet())
    private var loaded by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var busyPackage by mutableStateOf<String?>(null)
    private var message by mutableStateOf<Int?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                ForceDarkScreen(
                    available = available,
                    enabled = enabled,
                    usesTheme = usesTheme,
                    writesSwitches = writesSwitches,
                    apps = apps,
                    forced = forced,
                    loaded = loaded,
                    refreshing = refreshing,
                    busyPackage = busyPackage,
                    message = message,
                    onBack = { finish() },
                    onRefresh = { load() },
                    onEnabledChange = { on -> changeEnabled(on) },
                    onUsesThemeChange = { on -> changeUsesTheme(on) },
                    onWritesSwitchesChange = { on -> changeWritesSwitches(on) },
                    onToggleApp = { app, want -> setApp(app, want) },
                    onDismissMessage = { message = null }
                )
            }
        }
        load()
    }

    private fun load() {
        if (refreshing) return
        refreshing = true
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = packageManager
                    val userId = UserHandleCompat.myUserId()
                    // The list the firewall and autostart screens use, for the same reason: package
                    // visibility hides every app but this one from the local PackageManager.
                    val all = PackageEnumerator.installedApplications(userId).orEmpty()
                    val launchable = ForceDarkManager.launchable()
                    val list = all.distinctBy { it.packageName }
                        .filter { launchable == null || it.packageName in launchable }
                        .map { info ->
                            ForceDarkApp(
                                packageName = info.packageName,
                                label = runCatching { info.loadLabel(pm).toString() }
                                    .getOrDefault(info.packageName),
                                info = info,
                                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                    list to ForceDarkManager.apps()
                }
            }

            snapshot
                .onSuccess { (list, chosen) ->
                    apps = list
                    forced = chosen
                    available = ForceDarkManager.isAvailable()
                    enabled = ForceDarkManager.isEnabled()
                    usesTheme = ForceDarkManager.usesSystemTheme()
                    writesSwitches = ForceDarkManager.writesSwitches()
                    loaded = true
                }
                .onFailure { message = R.string.force_dark_action_failed }

            refreshing = false
        }
    }

    private fun changeEnabled(on: Boolean) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ForceDarkManager.setEnabled(on)
                // Turning it off has to put the device back whether or not this app is holding it:
                // the user has just asked for exactly that.
                if (!on) ForceDarkManager.restoreAlways()
                HidingWatchService.refresh(this@ForceDarkActivity)
                true
            }
            enabled = ForceDarkManager.isEnabled()
            if (!ok) message = R.string.force_dark_action_failed
        }
    }

    private fun changeUsesTheme(on: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ForceDarkManager.setUsesSystemTheme(on)
                // Off means the theme goes back now, not on the next app change that may never come.
                if (!on) ForceDarkManager.restoreAlways()
                HidingWatchService.refresh(this@ForceDarkActivity)
            }
            usesTheme = ForceDarkManager.usesSystemTheme()
        }
    }

    private fun changeWritesSwitches(on: Boolean) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ForceDarkManager.setWritesSwitches(on)
                if (!on) ForceDarkManager.restore()
                HidingWatchService.refresh(this@ForceDarkActivity)
            }
            writesSwitches = ForceDarkManager.writesSwitches()
        }
    }

    private fun setApp(app: ForceDarkApp, wanted: Boolean) {
        if (busyPackage != null) return
        busyPackage = app.packageName
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ForceDarkManager.setForced(app.packageName, wanted)
                // Only the property path is read at process start; the night mode repaints live, so
                // closing an app for it would be a cost with nothing bought.
                if (ForceDarkManager.writesSwitches() && ForceDarkManager.isAvailable()) {
                    ForceDarkManager.restart(app.packageName)
                }
                HidingWatchService.refresh(this@ForceDarkActivity)
            }
            forced = ForceDarkManager.apps()
            busyPackage = null
        }
    }
}

private const val FILTER_APPS = 0
private const val FILTER_FORCED = 1
private const val FILTER_SYSTEM = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForceDarkScreen(
    available: Boolean,
    enabled: Boolean,
    usesTheme: Boolean,
    writesSwitches: Boolean,
    apps: List<ForceDarkApp>,
    forced: Set<String>,
    loaded: Boolean,
    refreshing: Boolean,
    busyPackage: String?,
    message: Int?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onUsesThemeChange: (Boolean) -> Unit,
    onWritesSwitchesChange: (Boolean) -> Unit,
    onToggleApp: (ForceDarkApp, Boolean) -> Unit,
    onDismissMessage: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FILTER_APPS) }

    val visible = remember(apps, forced, query, filter) {
        apps.filter { app ->
            val matches = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            val shown = when (filter) {
                FILTER_FORCED -> app.packageName in forced
                FILTER_SYSTEM -> app.system
                else -> !app.system || app.packageName in forced || (query.isNotBlank() && matches)
            }
            shown && matches
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.force_dark_title)) },
            text = { Text(stringResource(message)) },
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.force_dark_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (!available) {
                                stringResource(R.string.firewall_need_service)
                            } else {
                                stringResource(R.string.force_dark_count, forced.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        ShizukuIcon(
                            icon = R.drawable.ic_arrow_back_24,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !refreshing) {
                        ShizukuIcon(
                            icon = R.drawable.ic_refresh_24,
                            contentDescription = stringResource(R.string.force_dark_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.force_dark_gate_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.force_dark_gate_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = when {
                                !enabled -> stringResource(R.string.force_dark_gate_off)
                                forced.isEmpty() -> stringResource(R.string.force_dark_gate_empty)
                                else -> stringResource(R.string.force_dark_gate_on)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (enabled && forced.isNotEmpty()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Switch(
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        enabled = available && busyPackage == null
                    )
                }
            }

            // The two mechanisms, side by side and labelled by what they cost: the theme is the one
            // that works and darkens the whole phone; the switches are the one that does not on a
            // modern device and is offered for the ones that still answer.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                    MechanismRow(
                        title = stringResource(R.string.force_dark_theme_title),
                        summary = stringResource(R.string.force_dark_theme_summary),
                        checked = usesTheme,
                        enabled = available && busyPackage == null,
                        onCheckedChange = onUsesThemeChange
                    )
                    Spacer(Modifier.height(12.dp))
                    MechanismRow(
                        title = stringResource(R.string.force_dark_switches_title),
                        summary = stringResource(R.string.force_dark_switches_summary),
                        checked = writesSwitches,
                        enabled = available && busyPackage == null,
                        onCheckedChange = onWritesSwitchesChange
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.force_dark_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == FILTER_APPS,
                    onClick = { filter = FILTER_APPS },
                    label = { Text(stringResource(R.string.force_dark_filter_all)) }
                )
                FilterChip(
                    selected = filter == FILTER_FORCED,
                    onClick = { filter = FILTER_FORCED },
                    label = { Text(stringResource(R.string.force_dark_filter_forced)) }
                )
                FilterChip(
                    selected = filter == FILTER_SYSTEM,
                    onClick = { filter = FILTER_SYSTEM },
                    label = { Text(stringResource(R.string.force_dark_filter_system)) }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.force_dark_app_count, visible.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }




            if (!loaded || refreshing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (visible.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.force_dark_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(visible, key = { it.packageName }) { app ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            )
                        ) {
                            ForceDarkRow(
                                app = app,
                                forced = app.packageName in forced,
                                busy = busyPackage == app.packageName,
                                // Dimmed and inert while the mode is off: the list is being kept,
                                // not applied, and a switch that changes nothing is one to stop
                                // offering.
                                enabled = available && enabled && busyPackage == null,
                                onToggle = { onToggleApp(app, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MechanismRow(
    title: String,
    summary: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ForceDarkRow(
    app: ForceDarkApp,
    forced: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(app)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (app.system) {
                    stringResource(R.string.firewall_system_app, app.packageName)
                } else {
                    app.packageName
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Switch(checked = forced, onCheckedChange = onToggle, enabled = enabled)
        }
    }
}

/**
 * The row icon, loaded off the main thread and cached by package + size.
 *
 * A null bitmap keeps the slot's width so the text does not jump when the icon arrives late.
 */
@Composable
private fun AppIcon(app: ForceDarkApp) {
    val context = LocalContext.current
    val size = 40.dp
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            AppIconCache.getOrLoadBitmap(context, app.info, UserHandleCompat.myUserId(), px)
                ?.asImageBitmap()
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
    ) {
        val image = bitmap
        if (image != null) {
            Image(bitmap = image, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}
