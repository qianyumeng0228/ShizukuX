package af.shizuku.manager.appops

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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/** One installed app whose ops can be inspected. */
private data class OpsApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val system: Boolean
)

/**
 * Per-app App Ops: every op the platform holds a record for, read and written one at a time.
 *
 * This is deliberately not the same screen as the runtime-permission manager next door. A runtime
 * permission covers exactly one op; the ops this screen exposes include the ones no runtime
 * permission maps to at all (clipboard reads, background starts, wake locks), which is where a
 * user's real complaints live. Each write is read back before the row is redrawn.
 */
class AppOpsActivity : ComponentActivity() {

    private var available by mutableStateOf(false)

    /** False until the first probe answers, so the "start the service" line never flashes. */
    private var checked by mutableStateOf(false)
    private var apps by mutableStateOf<List<OpsApp>>(emptyList())
    private var restricted by mutableStateOf<Set<String>>(emptySet())
    private var loaded by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    /** Non-null while the detail pane is open; holds the package it shows. */
    private var detail by mutableStateOf<String?>(null)
    private var detailLabel by mutableStateOf("")
    private var ops by mutableStateOf<List<AppOp>>(emptyList())
    private var opsLoading by mutableStateOf(false)
    private var busyOp by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                AppOpsScreen(
                    available = available,
                    checked = checked,
                    apps = apps,
                    restricted = restricted,
                    loaded = loaded,
                    refreshing = refreshing,
                    message = message,
                    detail = detail,
                    detailLabel = detailLabel,
                    ops = ops,
                    opsLoading = opsLoading,
                    busyOp = busyOp,
                    onBack = { if (detail != null) closeDetail() else finish() },
                    onRefresh = { if (detail != null) openDetail(detail!!) else load() },
                    onOpen = { app -> openDetail(app.packageName, app.label) },
                    onSetOp = { op, mode -> setOp(op, mode) },
                    onResetAll = { resetAll() },
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
                            OpsApp(
                                packageName = info.packageName,
                                label = runCatching { info.loadLabel(pm).toString() }
                                    .getOrDefault(info.packageName),
                                info = info,
                                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                    Triple(
                        list,
                        AppOpsManager.restricted(list.map { it.packageName }),
                        AppOpsManager.isAvailable()
                    )
                }
            }
            snapshot
                .onSuccess { (list, limited, avail) ->
                    apps = list
                    restricted = limited
                    available = avail
                    checked = true
                    loaded = true
                }
                .onFailure { message = it.message ?: it.toString() }
            refreshing = false
        }
    }

    private fun openDetail(packageName: String, label: String = "") {
        detail = packageName
        if (label.isNotEmpty()) detailLabel = label
        opsLoading = true
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { AppOpsManager.ops(packageName) }
            ops = list.orEmpty()
            if (list == null) message = getString(R.string.appops_failed)
            opsLoading = false
        }
    }

    private fun closeDetail() {
        detail = null
        ops = emptyList()
        busyOp = null
        // The list's "restricted" marks come from the same records this pane just edited, so they
        // are re-read on the way back instead of being left stale behind the user.
        load()
    }

    private fun setOp(op: AppOp, mode: String) {
        val pkg = detail ?: return
        if (busyOp != null || op.mode == mode) return
        busyOp = op.name
        lifecycleScope.launch {
            // Release the busy flag even if the shell call throws: otherwise every op row stays
            // disabled and the detail pane looks dead until the screen is recreated.
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { AppOpsManager.setOp(pkg, op.name, mode) }.getOrDefault(false)
                }
                if (ok) {
                    // AppOpsManager.setOp answers only after reading the mode back, so this update
                    // mirrors the platform's own record rather than the requested value.
                    ops = ops.map { if (it.name == op.name) it.copy(mode = mode) else it }
                } else {
                    // UID-level ops answer `cmd appops set` with success on this ROM but never
                    // change (verified against Camera/RECORD_AUDIO), so the honest message names
                    // the real limitation instead of the generic "platform refused".
                    message = getString(
                        if (AppOpsManager.isUidLevel(op.name)) R.string.appops_failed_uid
                        else R.string.appops_failed
                    )
                    // A failed write is the one case where the platform's value is worth re-reading:
                    // it either did not take, or it took and answered with a different mode name.
                    ops = withContext(Dispatchers.IO) {
                        runCatching { AppOpsManager.ops(pkg) }.getOrNull()
                    }.orEmpty().ifEmpty { ops }
                }
            } finally {
                busyOp = null
            }
        }
    }

    private fun resetAll() {
        val pkg = detail ?: return
        if (busyOp != null) return
        busyOp = "*"
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { AppOpsManager.resetAll(pkg) }.getOrNull()
                }
                ops = withContext(Dispatchers.IO) {
                    runCatching { AppOpsManager.ops(pkg) }.getOrNull()
                }.orEmpty().ifEmpty { ops }
                if (ok != true) message = getString(R.string.appops_failed)
            } finally {
                busyOp = null
            }
        }
    }
}

/** The platform name under the label, marked when the record covers the whole UID. */
private fun descriptor(op: AppOp): String =
    if (AppOpsManager.isUidLevel(op.name)) AppOpsManager.bareName(op.name) + " · UID"
    else op.name

private const val FILTER_APPS = 0
private const val FILTER_RESTRICTED = 1
private const val FILTER_SYSTEM = 2

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppOpsScreen(
    available: Boolean,
    checked: Boolean,
    apps: List<OpsApp>,
    restricted: Set<String>,
    loaded: Boolean,
    refreshing: Boolean,
    message: String?,
    detail: String?,
    detailLabel: String,
    ops: List<AppOp>,
    opsLoading: Boolean,
    busyOp: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpen: (OpsApp) -> Unit,
    onSetOp: (AppOp, String) -> Unit,
    onResetAll: () -> Unit,
    onDismissMessage: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FILTER_APPS) }
    var pending by remember { mutableStateOf<AppOp?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val visible = remember(apps, restricted, query, filter) {
        apps.filter { app ->
            val matches = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            val shown = when (filter) {
                FILTER_RESTRICTED -> app.packageName in restricted
                FILTER_SYSTEM -> app.system
                else -> !app.system || app.packageName in restricted || (query.isNotBlank() && matches)
            }
            shown && matches
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.appops_action_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    pending?.let { op ->
        Dialog(
            onDismissRequest = { pending = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                )
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 18.dp)) {
                    Text(
                        stringResource(R.string.appops_mode_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        AppOpsManager.label(op.name),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (AppOpsManager.isKnown(op.name) || AppOpsManager.isUidLevel(op.name)) {
                        Text(
                            descriptor(op),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // UID-level rows always opened a dialog that then failed on this ROM. Say so
                    // before the user spends a tap on it, instead of only after the write is
                    // rejected by readMode().
                    if (AppOpsManager.isUidLevel(op.name)) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.appops_uid_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    AppOpsManager.MODES.forEach { mode ->
                        val selected = op.mode == mode
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                            } else {
                                Color.Transparent
                            },
                            onClick = {
                                pending = null
                                onSetOp(op, mode)
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    modeLabel(mode),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (selected) {
                                    Text(
                                        stringResource(R.string.appops_current),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { pending = null }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.appops_reset_all)) },
            text = { Text(stringResource(R.string.appops_reset_all_hint, detailLabel)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetAll()
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (detail == null) {
                                stringResource(R.string.appops_title)
                            } else {
                                detailLabel.ifEmpty { detail ?: "" }
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = when {
                                checked && !available -> stringResource(R.string.appops_need_service)
                                !checked -> ""
                                detail != null -> stringResource(R.string.appops_detail_subtitle, ops.size)
                                else -> stringResource(R.string.appops_restricted_count, restricted.size)
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
                    if (detail != null) {
                        IconButton(onClick = { confirmReset = true }, enabled = busyOp == null) {
                            ShizukuIcon(
                                icon = R.drawable.ic_restore_24,
                                contentDescription = stringResource(R.string.appops_reset_all)
                            )
                        }
                    }
                    IconButton(onClick = onRefresh, enabled = !refreshing && !opsLoading) {
                        ShizukuIcon(
                            icon = R.drawable.ic_refresh_24,
                            contentDescription = stringResource(R.string.appops_refresh)
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
            if (detail == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.appops_search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp)
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == FILTER_APPS,
                        onClick = { filter = FILTER_APPS },
                        label = { Text(stringResource(R.string.firewall_filter_all)) }
                    )
                    FilterChip(
                        selected = filter == FILTER_RESTRICTED,
                        onClick = { filter = FILTER_RESTRICTED },
                        label = { Text(stringResource(R.string.appops_filter_restricted)) }
                    )
                    FilterChip(
                        selected = filter == FILTER_SYSTEM,
                        onClick = { filter = FILTER_SYSTEM },
                        label = { Text(stringResource(R.string.firewall_filter_system)) }
                    )
                }
                if (!loaded) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(visible, key = { it.packageName }) { app ->
                            AppRow(
                                app = app,
                                restricted = app.packageName in restricted,
                                onClick = { onOpen(app) }
                            )
                        }
                    }
                }
            } else {
                if (opsLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (ops.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.appops_empty),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(ops, key = { it.name }) { op ->
                            OpRow(
                                op = op,
                                busy = busyOp == op.name,
                                onClick = { pending = op }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One op: its label, its platform name, and its current mode as a chip.
 *
 * The mode is the tappable target as well as the readout - a row that shows a state but needs a
 * different control to change it is the shape users miss.
 */
@Composable
private fun OpRow(op: AppOp, busy: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        // On the light glass scheme surfaceContainer and the page gradient are both nearly white,
        // so the rows only read as cards once they carry the same hairline as the other glass
        // cards (device control, dialog).
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    AppOpsManager.label(op.name),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (AppOpsManager.isKnown(op.name) || AppOpsManager.isUidLevel(op.name)) {
                    Text(
                        descriptor(op),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                ModeChip(mode = op.mode, onClick = onClick)
            }
        }
    }
}

@Composable
private fun ModeChip(mode: String, onClick: () -> Unit) {
    val color = when (mode) {
        "deny" -> MaterialTheme.colorScheme.error
        "ignore" -> MaterialTheme.colorScheme.tertiary
        "allow" -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        onClick = onClick
    ) {
        Text(
            modeLabel(mode),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = color
        )
    }
}

@Composable
private fun AppRow(app: OpsApp, restricted: Boolean, onClick: () -> Unit) {
    // Same card language as the other feature lists; the old full-bleed row plus a divider read
    // as a leftover from the first pass.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(app)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (restricted) {
                Text(
                    stringResource(R.string.appops_restricted_mark),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** The row icon, loaded off the main thread and cached by package + size. */
@Composable
private fun AppIcon(app: OpsApp) {
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
