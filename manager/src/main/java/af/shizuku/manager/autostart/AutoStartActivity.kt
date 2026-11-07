package af.shizuku.manager.autostart

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
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
import af.shizuku.manager.ui.compose.ShizukuIcon
import af.shizuku.manager.utils.AppIconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One installed app whose background starts can be denied.
 *
 * [info] is kept so the row can load its icon lazily; the list itself needs the label, the package
 * name and whether the app is a system one.
 */
private data class AutoStartApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val system: Boolean
)

/**
 * Per-app autostart blocking, on the platform's `RUN_ANY_IN_BACKGROUND` op.
 *
 * Two things on one screen: a switch for the whole list (the gate), and one switch per app for the
 * op itself. Both are read back from the platform instead of being remembered, so a list that
 * another tool also writes stays true.
 */
class AutoStartActivity : ComponentActivity() {

    private var available by mutableStateOf(false)
    private var gateOn by mutableStateOf(true)
    private var apps by mutableStateOf<List<AutoStartApp>>(emptyList())
    private var blocked by mutableStateOf<Set<String>>(emptySet())
    private var loaded by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var busyPackage by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                AutoStartScreen(
                    available = available,
                    gateOn = gateOn,
                    apps = apps,
                    blocked = blocked,
                    loaded = loaded,
                    refreshing = refreshing,
                    busyPackage = busyPackage,
                    message = message,
                    onBack = { finish() },
                    onRefresh = { load() },
                    onGateChange = { on -> setGate(on) },
                    onToggleApp = { app, block -> setAppBlocked(app, block) },
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
                    // The same list the firewall uses, for the same reason: package visibility
                    // hides every app but this one from the local PackageManager.
                    val applications = PackageEnumerator.installedApplications(userId).orEmpty()
                    val list = applications.distinctBy { it.packageName }
                        .map { info ->
                            AutoStartApp(
                                packageName = info.packageName,
                                label = runCatching { info.loadLabel(pm).toString() }
                                    .getOrDefault(info.packageName),
                                info = info,
                                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                    list to AutoStartManager.blocked()
                }
            }

            snapshot
                .onSuccess { (list, denied) ->
                    apps = list
                    blocked = denied
                    available = AutoStartManager.isAvailable()
                    gateOn = AutoStartManager.isGateOn()
                    loaded = true
                }
                .onFailure { message = it.message ?: it.toString() }

            refreshing = false
        }
    }

    private fun setGate(on: Boolean) {
        if (busyPackage != null) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AutoStartManager.setGate(this@AutoStartActivity, on) }
            gateOn = AutoStartManager.isGateOn()
            blocked = withContext(Dispatchers.IO) { AutoStartManager.blocked() }
            if (!result) message = getString(R.string.autostart_gate_failed)
        }
    }

    private fun setAppBlocked(app: AutoStartApp, block: Boolean) {
        if (busyPackage != null) return
        busyPackage = app.packageName
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                AutoStartManager.setBlocked(this@AutoStartActivity, app.packageName, block)
            }
            if (ok) {
                blocked = if (block) blocked + app.packageName else blocked - app.packageName
                gateOn = AutoStartManager.isGateOn()
            } else {
                message = getString(R.string.autostart_failed)
            }
            busyPackage = null
        }
    }
}

private const val FILTER_APPS = 0
private const val FILTER_BLOCKED = 1
private const val FILTER_SYSTEM = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoStartScreen(
    available: Boolean,
    gateOn: Boolean,
    apps: List<AutoStartApp>,
    blocked: Set<String>,
    loaded: Boolean,
    refreshing: Boolean,
    busyPackage: String?,
    message: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onGateChange: (Boolean) -> Unit,
    onToggleApp: (AutoStartApp, Boolean) -> Unit,
    onDismissMessage: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FILTER_APPS) }

    val visible = remember(apps, blocked, query, filter) {
        apps.filter { app ->
            val matches = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            // The default view is the user's own apps: hundreds of system packages would bury
            // them. A blocked system app still shows there, and a search reveals any of them.
            val shown = when (filter) {
                FILTER_BLOCKED -> app.packageName in blocked
                FILTER_SYSTEM -> app.system
                else -> !app.system || app.packageName in blocked || (query.isNotBlank() && matches)
            }
            shown && matches
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.autostart_action_failed)) },
            text = { Text(message) },
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
                            text = stringResource(R.string.autostart_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (!available) {
                                stringResource(R.string.firewall_need_service)
                            } else {
                                stringResource(R.string.autostart_blocked_count, blocked.size)
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
                            contentDescription = stringResource(R.string.autostart_refresh)
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
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.autostart_gate_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.autostart_gate_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (gateOn) {
                                stringResource(R.string.autostart_gate_on)
                            } else {
                                stringResource(R.string.autostart_gate_off)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (gateOn) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Switch(
                        checked = gateOn,
                        onCheckedChange = onGateChange,
                        enabled = available && busyPackage == null
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.autostart_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == FILTER_APPS,
                    onClick = { filter = FILTER_APPS },
                    label = { Text(stringResource(R.string.autostart_filter_all)) }
                )
                FilterChip(
                    selected = filter == FILTER_BLOCKED,
                    onClick = { filter = FILTER_BLOCKED },
                    label = { Text(stringResource(R.string.autostart_filter_blocked)) }
                )
                FilterChip(
                    selected = filter == FILTER_SYSTEM,
                    onClick = { filter = FILTER_SYSTEM },
                    label = { Text(stringResource(R.string.autostart_filter_system)) }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.autostart_app_count, visible.size),
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
                        text = stringResource(R.string.autostart_empty),
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
                        AutoStartRow(
                            app = app,
                            blocked = app.packageName in blocked,
                            busy = busyPackage == app.packageName,
                            enabled = available && busyPackage == null,
                            onToggle = { onToggleApp(app, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoStartRow(
    app: AutoStartApp,
    blocked: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    // Card per app, same as the firewall and AppOps lists.
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
            Switch(checked = blocked, onCheckedChange = onToggle, enabled = enabled)
        }
        }
    }
}

/**
 * The row icon, loaded off the main thread and cached by package + size.
 *
 * A null bitmap keeps the slot's width so the text does not jump when the icon arrives late.
 */
@Composable
private fun AppIcon(app: AutoStartApp) {
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
