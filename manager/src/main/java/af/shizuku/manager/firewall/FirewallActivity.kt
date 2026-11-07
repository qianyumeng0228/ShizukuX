package af.shizuku.manager.firewall

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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
import af.shizuku.manager.ui.compose.ShizukuIcon
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.ShizukuSystemApis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One installed app that can be blocked by the platform firewall.
 *
 * [info] is kept so the row can load its icon lazily; the list itself only needs the label,
 * the uid and whether the app is a system one.
 */
data class FirewallApp(
    val packageName: String,
    val label: String,
    val uid: Int,
    val info: ApplicationInfo,
    val system: Boolean
)

/**
 * Per-app network blocking, on the platform's own firewall.
 *
 * The screen is two things: a switch for the chain (the firewall as a whole), and one switch per
 * app for the deny bit in that chain. Both are read back from the platform instead of being
 * remembered - another app (ShizuWall and friends) writes the same chain, and a list that shows
 * what this app last asked for would be wrong the moment anything else touched it.
 */
class FirewallActivity : ComponentActivity() {

    private var available by mutableStateOf(false)
    private var chainOn by mutableStateOf<Boolean?>(null)
    private var apps by mutableStateOf<List<FirewallApp>>(emptyList())
    private var blocked by mutableStateOf<Set<Int>>(emptySet())
    private var loaded by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var busyUid by mutableStateOf<Int?>(null)
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
                FirewallScreen(
                    available = available,
                    chainOn = chainOn,
                    apps = apps,
                    blocked = blocked,
                    loaded = loaded,
                    refreshing = refreshing,
                    busyUid = busyUid,
                    message = message,
                    onBack = { finish() },
                    onRefresh = { load() },
                    onChainChange = { on -> setChain(on) },
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
                    // Package visibility hides every package but this app from the local
                    // PackageManager, and the IShizukuService list only carries apps that talk to
                    // Shizuku - so the list comes from the platform package manager itself.
                    val applications = PackageEnumerator.installedApplications(userId).orEmpty()

                    val list = applications.distinctBy { it.packageName }
                        .filter { info ->
                            // Only apps that hold INTERNET can be blocked in any meaningful way;
                            // listing the rest would be a list of switches that do nothing.
                            runCatching {
                                ShizukuSystemApis.checkPermission(
                                    android.Manifest.permission.INTERNET,
                                    info.packageName,
                                    userId
                                ) == PackageManager.PERMISSION_GRANTED
                            }.getOrDefault(false)
                        }
                        .map { info ->
                            FirewallApp(
                                packageName = info.packageName,
                                label = runCatching { info.loadLabel(pm).toString() }
                                    .getOrDefault(info.packageName),
                                uid = info.uid,
                                info = info,
                                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                    list to FirewallManager.rulesSnapshot(list.map { it.uid })
                }
            }

            snapshot
                .onSuccess { (list, rules) ->
                    apps = list
                    // A uid the platform did not answer for is left out of the blocked set, so the
                    // switch shows "not blocked" only when that was actually read, and the row's
                    // toggle still writes a definite state when the user flips it.
                    blocked = rules?.filterValues { it }?.keys ?: emptySet()
                    available = FirewallManager.isAvailable()
                    chainOn = FirewallManager.chainOn()
                    loaded = true
                }
                .onFailure { message = it.message ?: it.toString() }

            refreshing = false
        }
    }

    private fun setChain(on: Boolean) {
        if (busyUid != null) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { FirewallManager.setChain(on) }
            val now = withContext(Dispatchers.IO) { FirewallManager.chainOn() }
            chainOn = now
            if (!result) message = getString(R.string.firewall_failed)
        }
    }

    private fun setAppBlocked(app: FirewallApp, block: Boolean) {
        if (busyUid != null) return
        busyUid = app.uid
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                FirewallManager.setBlocked(app.uid, app.packageName, block)
            }
            if (ok) {
                blocked = if (block) blocked + app.uid else blocked - app.uid
                // The chain has to be on for the rule to mean anything; reading it back keeps the
                // big switch honest after a per-app write turned it on.
                chainOn = withContext(Dispatchers.IO) { FirewallManager.chainOn() }
            } else {
                message = getString(R.string.firewall_failed)
            }
            busyUid = null
        }
    }
}

private const val FILTER_APPS = 0
private const val FILTER_BLOCKED = 1
private const val FILTER_SYSTEM = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirewallScreen(
    available: Boolean,
    chainOn: Boolean?,
    apps: List<FirewallApp>,
    blocked: Set<Int>,
    loaded: Boolean,
    refreshing: Boolean,
    busyUid: Int?,
    message: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onChainChange: (Boolean) -> Unit,
    onToggleApp: (FirewallApp, Boolean) -> Unit,
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
            // them. A blocked system app still shows there - hiding a rule the user set would be
            // worse than the clutter - and a search reveals any of them.
            val shown = when (filter) {
                FILTER_BLOCKED -> app.uid in blocked
                FILTER_SYSTEM -> app.system
                else -> !app.system || app.uid in blocked || (query.isNotBlank() && matches)
            }
            shown && matches
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.firewall_action_failed)) },
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
                            text = stringResource(R.string.firewall_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (!available) {
                                stringResource(R.string.firewall_need_service)
                            } else {
                                stringResource(R.string.firewall_blocked_count, blocked.size)
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
                            contentDescription = stringResource(R.string.firewall_refresh)
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
                            text = stringResource(R.string.firewall_switch_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.firewall_switch_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (chainOn) {
                                true -> stringResource(R.string.firewall_state_on)
                                false -> stringResource(R.string.firewall_state_off)
                                null -> stringResource(R.string.firewall_state_unknown)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (chainOn == true) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Switch(
                        checked = chainOn == true,
                        onCheckedChange = onChainChange,
                        enabled = available && chainOn != null
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.firewall_search_hint)) },
                singleLine = true,
                // Capsule corner to match the other list pages (stock shape is a 4dp rectangle).
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
                    label = { Text(stringResource(R.string.firewall_filter_all)) }
                )
                FilterChip(
                    selected = filter == FILTER_BLOCKED,
                    onClick = { filter = FILTER_BLOCKED },
                    label = { Text(stringResource(R.string.firewall_filter_blocked)) }
                )
                FilterChip(
                    selected = filter == FILTER_SYSTEM,
                    onClick = { filter = FILTER_SYSTEM },
                    label = { Text(stringResource(R.string.firewall_filter_system)) }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.firewall_app_count, visible.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            // No full-bleed divider here: the rows below are individual cards, so a hard rule
            // between the filter bar and the list reads as a leftover from the flat design.
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
                        text = stringResource(R.string.firewall_empty),
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
                        FirewallRow(
                            app = app,
                            blocked = app.uid in blocked,
                            busy = busyUid == app.uid,
                            enabled = available && busyUid == null,
                            onToggle = { onToggleApp(app, it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FirewallRow(
    app: FirewallApp,
    blocked: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    // One card per app, matching the AppOps rows: on the glass background a flat row separated
    // only by a divider leaves the list without any edge to read against.
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
private fun AppIcon(app: FirewallApp) {
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
