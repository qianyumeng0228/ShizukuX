package af.shizuku.manager.battery

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
import androidx.compose.ui.text.style.TextAlign
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
 * One installed app and where its whitelist entry comes from.
 *
 * [kind] is null for an app that is not exempt; a system entry is shown exempt but its switch is
 * disabled, because `dumpsys deviceidle whitelist -<pkg>` only removes user entries.
 */
private data class BatteryApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val system: Boolean
)

/**
 * The platform's battery-optimisation whitelist: the same list the system's "Don't optimize"
 * screen edits, managed for every installed app in one place.
 *
 * Three views on one list: all apps, the exempt ones, and the ones the system may still reclaim
 * in the background. Nothing is remembered locally - the switches are read back from the platform
 * after every write, so a list edited from the system settings or another tool stays true.
 */
class BatteryWhitelistActivity : ComponentActivity() {

    private var available by mutableStateOf(false)
    private var apps by mutableStateOf<List<BatteryApp>>(emptyList())
    private var kinds by mutableStateOf<Map<String, BatteryWhitelistManager.Kind>>(emptyMap())
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
                BatteryWhitelistScreen(
                    available = available,
                    apps = apps,
                    kinds = kinds,
                    loaded = loaded,
                    refreshing = refreshing,
                    busyPackage = busyPackage,
                    message = message,
                    onBack = { finish() },
                    onRefresh = { load() },
                    onToggleApp = { app, exempt -> setExempt(app, exempt) },
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
                    val applications = PackageEnumerator.installedApplications(userId).orEmpty()
                    val list = applications.distinctBy { it.packageName }
                        .map { info ->
                            BatteryApp(
                                packageName = info.packageName,
                                label = runCatching { info.loadLabel(pm).toString() }
                                    .getOrDefault(info.packageName),
                                info = info,
                                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            )
                        }
                        .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }))
                    val whitelist = BatteryWhitelistManager.read()
                    Triple(list, whitelist, BatteryWhitelistManager.isAvailable())
                }
            }

            snapshot
                .onSuccess { (list, whitelist, serviceAvailable) ->
                    apps = list
                    kinds = whitelist.entries
                    available = serviceAvailable && whitelist.error == null
                    loaded = true
                    message = whitelist.error?.let { describeError(it) }
                }
                .onFailure { message = it.message ?: it.toString() }

            refreshing = false
        }
    }

    private fun describeError(code: String): String = when (code) {
        BatteryWhitelistManager.NO_SERVICE -> getString(R.string.battery_whitelist_need_service)
        BatteryWhitelistManager.NO_DUMP_PERMISSION -> getString(R.string.battery_whitelist_no_dump_permission)
        else -> getString(R.string.battery_whitelist_unrecognised)
    }

    private fun setExempt(app: BatteryApp, exempt: Boolean) {
        if (busyPackage != null) return
        busyPackage = app.packageName
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BatteryWhitelistManager.setExempt(app.packageName, exempt)
            }
            when (result) {
                BatteryWhitelistManager.WriteResult.OK -> {
                    // Re-read the whole list rather than assuming the platform took the write: the
                    // verb is accepted by ROMs that leave the record alone, and the row would then
                    // show a state the device does not have.
                    val snapshot = withContext(Dispatchers.IO) { BatteryWhitelistManager.read() }
                    if (snapshot.error == null) kinds = snapshot.entries
                }
                BatteryWhitelistManager.WriteResult.SYSTEM_ENTRY ->
                    message = getString(R.string.battery_whitelist_system_locked)
                else -> message = getString(R.string.battery_whitelist_failed)
            }
            busyPackage = null
        }
    }
}

private const val FILTER_ALL = 0
private const val FILTER_WHITELIST = 1
private const val FILTER_BLACKLIST = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatteryWhitelistScreen(
    available: Boolean,
    apps: List<BatteryApp>,
    kinds: Map<String, BatteryWhitelistManager.Kind>,
    loaded: Boolean,
    refreshing: Boolean,
    busyPackage: String?,
    message: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleApp: (BatteryApp, Boolean) -> Unit,
    onDismissMessage: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FILTER_ALL) }

    val exemptInApps = remember(apps, kinds) { apps.count { it.packageName in kinds } }

    val visible = remember(apps, kinds, query, filter) {
        apps.filter { app ->
            val exempt = app.packageName in kinds
            val shown = when (filter) {
                FILTER_WHITELIST -> exempt
                FILTER_BLACKLIST -> !exempt
                else -> true
            }
            shown && (query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true))
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.battery_whitelist_action_failed)) },
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
                            text = stringResource(R.string.battery_whitelist_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (!available) {
                                stringResource(R.string.battery_whitelist_need_service)
                            } else {
                                stringResource(
                                    R.string.battery_whitelist_count,
                                    exemptInApps,
                                    apps.size
                                )
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
                            contentDescription = stringResource(R.string.battery_whitelist_refresh)
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                placeholder = { Text(stringResource(R.string.battery_whitelist_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == FILTER_ALL,
                    onClick = { filter = FILTER_ALL },
                    label = { Text(stringResource(R.string.battery_whitelist_filter_all)) }
                )
                FilterChip(
                    selected = filter == FILTER_WHITELIST,
                    onClick = { filter = FILTER_WHITELIST },
                    label = { Text(stringResource(R.string.battery_whitelist_filter_whitelist)) }
                )
                FilterChip(
                    selected = filter == FILTER_BLACKLIST,
                    onClick = { filter = FILTER_BLACKLIST },
                    label = { Text(stringResource(R.string.battery_whitelist_filter_blacklist)) }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.battery_whitelist_app_count, visible.size),
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
                        text = stringResource(
                            // A search that matches nothing is not the same statement as "no app
                            // is exempt at all": with a query in the field the list is already
                            // narrowed, and the per-filter wording would describe the whole device.
                            when {
                                query.isNotBlank() -> R.string.battery_whitelist_empty_search
                                filter == FILTER_WHITELIST -> R.string.battery_whitelist_empty_whitelist
                                filter == FILTER_BLACKLIST -> R.string.battery_whitelist_empty_blacklist
                                else -> R.string.battery_whitelist_empty_all
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(visible, key = { it.packageName }) { app ->
                        BatteryRow(
                            app = app,
                            kind = kinds[app.packageName],
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
private fun BatteryRow(
    app: BatteryApp,
    kind: BatteryWhitelistManager.Kind?,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val exempt = kind != null
    // System entries are exempt but not removable here: `whitelist -` only acts on user entries,
    // so the switch stays on and locked instead of pretending it can be turned off.
    val locked = when (kind) {
        BatteryWhitelistManager.Kind.SYSTEM, BatteryWhitelistManager.Kind.SYSTEM_EXCIDLE, BatteryWhitelistManager.Kind.OTHER -> true
        else -> false
    }

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
                    text = when {
                        locked -> stringResource(R.string.battery_whitelist_system_entry, app.packageName)
                        app.system -> stringResource(R.string.firewall_system_app, app.packageName)
                        else -> app.packageName
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
                Switch(
                    checked = exempt,
                    onCheckedChange = onToggle,
                    enabled = enabled && !locked
                )
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
private fun AppIcon(app: BatteryApp) {
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
