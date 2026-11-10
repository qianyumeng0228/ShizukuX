package af.shizuku.manager.hiding

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.LaunchedEffect
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

/** One installed app, kept with its info so the row can load an icon lazily. */
data class HidingApp(
    val packageName: String,
    val label: String,
    val info: ApplicationInfo,
    val system: Boolean
)

/** What one signal says about itself: is the mode on, and how many apps it names. */
data class SignalRow(
    val signal: Signal,
    val enabled: Boolean,
    val listed: Int
)

/**
 * Per-app hiding, on the platform's own settings.
 *
 * The screen is two levels: the signals - what an app objects to - and, behind each of them, the
 * apps it is hidden for. A signal is a rule, not a switch for the device: the watch applies it
 * while a listed app is in front and puts it back when it leaves, which is what makes hiding a
 * device-wide setting survivable.
 *
 * Everything shown is read back from the device: whether a signal is really hidden is the
 * provider's answer, not this app's last request, and a switch that could not be written says so
 * instead of showing what did not happen.
 */
class HidingActivity : ComponentActivity() {

    private var available by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var rows by mutableStateOf<List<SignalRow>>(emptyList())
    private var selected by mutableStateOf<Signal?>(null)
    private var apps by mutableStateOf<List<HidingApp>>(emptyList())
    private var appsLoaded by mutableStateOf(false)
    private var listed by mutableStateOf<Set<String>>(emptySet())
    private var restartOnOpen by mutableStateOf(false)
    // Which apps the restart applies to, and whether the list for them is the one on screen.
    // The restart card is not a signal: it has its own set and its own screen, but that screen
    // is the same list of apps every signal uses.
    private var restartApps by mutableStateOf<Set<String>>(emptySet())
    private var restartList by mutableStateOf(false)
    private var paused by mutableStateOf(false)
    private var usageAccess by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var busyKey by mutableStateOf<String?>(null)
    private var message by mutableStateOf<String?>(null)

    // Kept here rather than inside the list, because the list is dropped and rebuilt on every
    // refresh: a chip choice or a search term held there would jump back to the default the
    // moment a switch is flipped.
    private var appQuery by mutableStateOf("")
    private var appFilter by mutableStateOf(FILTER_ALL)

    /** Everything the first screen shows, read off the device in one go on IO. */
    private data class Snapshot(
        val available: Boolean,
        val usageAccess: Boolean,
        val restartOnOpen: Boolean,
        val restartApps: Set<String>,
        val paused: Boolean,
        val rows: List<SignalRow>,
        val listed: Set<String>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                HidingScreen(
                    available = available,
                    loaded = loaded,
                    rows = rows,
                    selected = selected,
                    apps = apps,
                    appsLoaded = appsLoaded,
                    listed = listed,
                    restartOnOpen = restartOnOpen,
                    restartApps = restartApps,
                    restartList = restartList,
                    paused = paused,
                    usageAccess = usageAccess,
                    busy = busy,
                    busyKey = busyKey,
                    message = message,
                    appQuery = appQuery,
                    appFilter = appFilter,
                    onAppQuery = { appQuery = it },
                    onAppFilter = { appFilter = it },
                    onBackFromSignal = {
                        selected = null
                        restartList = false
                    },
                    onBack = { finish() },
                    onRefresh = { refresh() },
                    onOpenSignal = { signal ->
                        selected = signal
                        restartList = false
                        listed = Hiding.appsFor(signal)
                        // A fresh list starts unfiltered: the term and the chip belonged to the
                        // list that was open, not to every list this screen can open.
                        appQuery = ""
                        appFilter = FILTER_ALL
                        if (!appsLoaded) loadApps()
                    },
                    onOpenRestart = {
                        // The restart list is its own target, but the list itself is the same
                        // one: same chips, same order, same row.
                        selected = null
                        restartList = true
                        restartApps = Hiding.restartApps()
                        appQuery = ""
                        appFilter = FILTER_ALL
                        if (!appsLoaded) loadApps()
                    },
                    onToggleSignal = { signal, enabled -> setSignalEnabled(signal, enabled) },
                    onToggleApp = { app, on -> setOnList(app, on) },
                    onToggleRestartApp = { app, on -> setRestartApp(app, on) },
                    onRestartOnOpen = { on -> writeRestartOnOpen(on) },
                    onPaused = { on -> writePaused(on) },
                    onDismissMessage = { message = null }
                )
            }
        }
        refresh()
        lifecycleScope.launch(Dispatchers.IO) {
            // Opening the page is also how the watch comes back after the process that held it was
            // killed: a list is a standing rule, and one left without a watch would be a rule
            // nobody applies and settings nobody puts back. Started before the grants are asked
            // for, because each of those is a shell round trip and the watch must not wait on them
            // to be able to see.
            HidingWatchService.refresh(this@HidingActivity)
            // Both grants are shell commands with no dialog to show, and without them a list cannot
            // work at all. Asked for quietly, on IO, the same way the watch asks.
            HidingGrants.ensureQuietly()
        }
    }

    /**
     * Reads the device again, which is what the page shows until told otherwise.
     *
     * The grant states are read here too: a device that has just been granted usage access should
     * stop warning about it without a restart of the screen.
     */
    private fun refresh() {
        lifecycleScope.launch {
            loaded = false
            val snapshot = withContext(Dispatchers.IO) {
                Snapshot(
                    available = Hiding.shellAvailable(),
                    usageAccess = Hiding.hasUsageAccess(),
                    restartOnOpen = Hiding.isRestartOnOpenEnabled(),
                    restartApps = Hiding.restartApps(),
                    paused = Hiding.isPaused(),
                    rows = Signal.entries.map { signal ->
                        SignalRow(signal, Hiding.isSignalEnabled(signal), Hiding.appsFor(signal).size)
                    },
                    listed = selected?.let { Hiding.appsFor(it) } ?: emptySet()
                )
            }
            available = snapshot.available
            usageAccess = snapshot.usageAccess
            restartOnOpen = snapshot.restartOnOpen
            restartApps = snapshot.restartApps
            paused = snapshot.paused
            rows = snapshot.rows
            listed = snapshot.listed
            loaded = true
        }
    }

    private fun loadApps() {
        lifecycleScope.launch {
            appsLoaded = false
            val list = withContext(Dispatchers.IO) {
                val context = this@HidingActivity
                val infos = PackageEnumerator.installedApplications(UserHandleCompat.myUserId())
                    // The fallback is the local package manager: on a ROM that intercepts it this
                    // is a short list, but a short list beats a screen that cannot be used.
                    ?: context.packageManager
                        .getInstalledApplications(PackageManager.GET_META_DATA)
                        .filter { it.packageName != context.packageName }
                infos.map { info ->
                    HidingApp(
                        packageName = info.packageName,
                        label = AppIconCache.getLabel(context, info),
                        info = info,
                        system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
                    )
                }.sortedBy { it.label.lowercase() }
            }
            apps = list
            appsLoaded = true
        }
    }

    private fun setSignalEnabled(signal: Signal, enabled: Boolean) {
        lifecycleScope.launch {
            busyKey = "signal/${signal.name}"
            val ok = withContext(Dispatchers.IO) {
                Hiding.setSignalEnabled(signal, enabled)
                HidingWatchService.refresh(this@HidingActivity)
                Hiding.isSignalEnabled(signal) == enabled
            }
            if (!ok) message = getString(R.string.hiding_failed)
            busyKey = null
            refresh()
        }
    }

    private fun setOnList(app: HidingApp, on: Boolean) {
        val signal = selected ?: return
        lifecycleScope.launch {
            busyKey = app.packageName
            val ok = withContext(Dispatchers.IO) {
                Hiding.setOnList(signal, app.packageName, on)
                HidingWatchService.refresh(this@HidingActivity)
                Hiding.appsFor(signal).contains(app.packageName) == on
            }
            if (ok) {
                listed = if (on) listed + app.packageName else listed - app.packageName
                refresh()
            } else {
                message = getString(R.string.hiding_failed)
            }
            busyKey = null
        }
    }

    /**
     * Puts one app on the restart list, or takes it off.
     *
     * The watch reads this list every pass, so a write is enough: the app that is in front right
     * now is not restarted for having been just added, only the next time it comes to the front.
     */
    private fun setRestartApp(app: HidingApp, on: Boolean) {
        lifecycleScope.launch {
            busyKey = app.packageName
            val ok = withContext(Dispatchers.IO) {
                Hiding.setRestartApp(app.packageName, on)
                HidingWatchService.refresh(this@HidingActivity)
                Hiding.isRestartFor(app.packageName) == on
            }
            if (ok) {
                restartApps = if (on) restartApps + app.packageName else restartApps - app.packageName
                refresh()
            } else {
                message = getString(R.string.hiding_failed)
            }
            busyKey = null
        }
    }

    private fun writeRestartOnOpen(on: Boolean) {
        lifecycleScope.launch {
            busyKey = "restart_on_open"
            withContext(Dispatchers.IO) {
                Hiding.setRestartOnOpen(on)
                HidingWatchService.refresh(this@HidingActivity)
            }
            restartOnOpen = Hiding.isRestartOnOpenEnabled()
            busyKey = null
        }
    }

    /**
     * Stops or restarts the rule as a whole, the same fact the notification's action carries.
     *
     * Turning it on puts every setting back at once, because a pause that left the device lying
     * about itself would be the one thing a pause must not do.
     */
    private fun writePaused(on: Boolean) {
        lifecycleScope.launch {
            busyKey = "paused"
            withContext(Dispatchers.IO) {
                Hiding.setPaused(on)
                HidingWatchService.refresh(this@HidingActivity)
            }
            busyKey = null
            refresh()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HidingScreen(
    available: Boolean,
    loaded: Boolean,
    rows: List<SignalRow>,
    selected: Signal?,
    apps: List<HidingApp>,
    appsLoaded: Boolean,
    listed: Set<String>,
    restartOnOpen: Boolean,
    restartApps: Set<String>,
    restartList: Boolean,
    paused: Boolean,
    usageAccess: Boolean,
    busy: Boolean,
    busyKey: String?,
    message: String?,
    appQuery: String,
    appFilter: Int,
    onAppQuery: (String) -> Unit,
    onAppFilter: (Int) -> Unit,
    onBackFromSignal: () -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSignal: (Signal) -> Unit,
    onOpenRestart: () -> Unit,
    onToggleSignal: (Signal, Boolean) -> Unit,
    onToggleApp: (HidingApp, Boolean) -> Unit,
    onToggleRestartApp: (HidingApp, Boolean) -> Unit,
    onRestartOnOpen: (Boolean) -> Unit,
    onPaused: (Boolean) -> Unit,
    onDismissMessage: () -> Unit
) {
    // One value read once, so the title and the back arrow cannot disagree about which page
    // this is.
    val listSignal = selected

    // The system back gesture is the same step as the arrow: either list goes back to the
    // overview, and only the overview leaves the screen. Without this the gesture closed the
    // whole page from a list the arrow treats as one level deep.
    BackHandler(enabled = restartList || listSignal != null) {
        onBackFromSignal()
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.hiding_action_failed)) },
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
                    // The restart list is a third kind of page: not the overview, and not one
                    // signal, but the same app list the signals use.
                    Column {
                        Text(
                            text = when {
                                restartList ->
                                    stringResource(R.string.hiding_restart_list_title)
                                listSignal != null ->
                                    stringResource(R.string.hiding_list_title, stringResource(listSignal.labelRes))
                                else ->
                                    stringResource(R.string.hiding_title)
                            },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = when {
                                !available -> stringResource(R.string.firewall_need_service)
                                paused -> stringResource(R.string.hiding_subtitle_paused)
                                restartList ->
                                    stringResource(R.string.hiding_list_subtitle, restartApps.size)
                                listSignal != null ->
                                    stringResource(R.string.hiding_list_subtitle, listed.size)
                                else ->
                                    stringResource(R.string.hiding_subtitle_count, rows.sumOf { it.listed })
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = if (listSignal == null && !restartList) onBack else onBackFromSignal
                    ) {
                        ShizukuIcon(
                            icon = R.drawable.ic_arrow_back_24,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !busy) {
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
        if (!loaded) {
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        when {
            restartList -> AppList(
                padding = padding,
                signal = null,
                restart = true,
                apps = apps,
                appsLoaded = appsLoaded,
                listed = restartApps,
                available = available,
                busy = busy,
                busyKey = busyKey,
                query = appQuery,
                filter = appFilter,
                onQuery = onAppQuery,
                onFilter = onAppFilter,
                onToggleApp = onToggleRestartApp
            )
            selected == null -> SignalList(
                padding = padding,
                available = available,
                rows = rows,
                restartOnOpen = restartOnOpen,
                restartApps = restartApps.size,
                paused = paused,
                usageAccess = usageAccess,
                busy = busy,
                busyKey = busyKey,
                onOpenSignal = onOpenSignal,
                onToggleSignal = onToggleSignal,
                onOpenRestart = onOpenRestart,
                onRestartOnOpen = onRestartOnOpen,
                onPaused = onPaused
            )
            else -> AppList(
                padding = padding,
                signal = selected,
                restart = false,
                apps = apps,
                appsLoaded = appsLoaded,
                listed = listed,
                available = available,
                busy = busy,
                busyKey = busyKey,
                query = appQuery,
                filter = appFilter,
                onQuery = onAppQuery,
                onFilter = onAppFilter,
                onToggleApp = onToggleApp
            )
        }
    }
}

@Composable
private fun SignalList(
    padding: PaddingValues,
    available: Boolean,
    rows: List<SignalRow>,
    restartOnOpen: Boolean,
    restartApps: Int,
    paused: Boolean,
    usageAccess: Boolean,
    busy: Boolean,
    busyKey: String?,
    onOpenSignal: (Signal) -> Unit,
    onToggleSignal: (Signal, Boolean) -> Unit,
    onOpenRestart: () -> Unit,
    onRestartOnOpen: (Boolean) -> Unit,
    onPaused: (Boolean) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    // 12dp side margin to line up with the 12dp switch cards below; the intro card
                    // used to sit 4dp further in than everything under it.
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        text = stringResource(R.string.hiding_intro_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.hiding_intro_text),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!usageAccess) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.hiding_warning_no_usage),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // The whole rule, one switch: the same fact the notification's own action carries, so
        // the page can do what the shade can do without the user having to find the shade.
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.hiding_pause),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.hiding_pause_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = paused,
                        onCheckedChange = onPaused,
                        enabled = available && !busy
                    )
                }
            }
        }

        items(rows, key = { it.signal.name }) { row ->
            SignalCard(
                row = row,
                enabled = available && !busy,
                busy = busyKey == "signal/${row.signal.name}",
                onClick = { onOpenSignal(row.signal) },
                onToggle = { onToggleSignal(row.signal, it) }
            )
        }

        // Not a signal, but it now reads like one: the switch arms the feature, and the list
        // behind "选择应用" says which apps it applies to. Some apps cannot be restarted at all
        // (a chat loses its draft), and some only read the settings a restart later, so one
        // switch over every app could only ever be wrong for half of them.
        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.hiding_restart_on_open),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = when {
                                !restartOnOpen -> stringResource(R.string.hiding_signal_summary_off)
                                restartApps == 0 -> stringResource(R.string.hiding_signal_summary_empty)
                                else -> stringResource(R.string.hiding_restart_summary_on, restartApps)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (restartOnOpen && restartApps > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    TextButton(
                        onClick = onOpenRestart,
                        enabled = available && !busy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.hiding_choose_apps),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (busyKey == "restart_on_open") {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Switch(
                            checked = restartOnOpen,
                            onCheckedChange = onRestartOnOpen,
                            enabled = available && !busy
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignalCard(
    row: SignalRow,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    // One row per signal: name and count, then the way into the list, then the switch. Surface
    // lays its content out as a Box, so the contents need a Column of their own to stack.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        // The dark glass fill (0x66161D2F) sits within a few levels of the page backdrop, so a
        // hairline is what actually draws the row edge (same border every Compose list row uses).
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp)
                ) {
                    Text(
                        text = stringResource(row.signal.labelRes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // An armed mode with an empty list used to read "对 0 个应用隐藏", which
                        // looks like a broken switch next to an on switch. Say the list is empty.
                        text = when {
                            !row.enabled -> stringResource(R.string.hiding_signal_summary_off)
                            row.listed == 0 -> stringResource(R.string.hiding_signal_summary_empty)
                            else -> stringResource(R.string.hiding_signal_summary_on, row.listed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.enabled && row.listed > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                TextButton(
                    onClick = onClick,
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.hiding_choose_apps),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Switch(
                        checked = row.enabled,
                        onCheckedChange = onToggle,
                        enabled = enabled
                    )
                }
            }
        }
    }
}

@Composable
private fun AppList(
    padding: PaddingValues,
    signal: Signal?,
    restart: Boolean,
    apps: List<HidingApp>,
    appsLoaded: Boolean,
    listed: Set<String>,
    available: Boolean,
    busy: Boolean,
    busyKey: String?,
    query: String,
    filter: Int,
    onQuery: (String) -> Unit,
    onFilter: (Int) -> Unit,
    onToggleApp: (HidingApp, Boolean) -> Unit
) {
    val visible = remember(apps, listed, query, filter) {
        apps.filter { app ->
            val matches = query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
            // What the chip says is what the list holds: no hidden rule about system apps any
            // more, because the chips are the way to ask for them.
            val shown = when (filter) {
                FILTER_USER -> !app.system
                FILTER_SYSTEM -> app.system
                else -> true
            }
            shown && matches
        }
            // The apps already on the list come first and stay there: the list exists to show the
            // rules that are set, and hunting for one of them among hundreds of packages is the
            // part of this screen that was hardest to use.
            .sortedWith(compareBy({ it.packageName !in listed }, { it.label.lowercase() }))
    }

    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
    ) {
        Text(
            text = stringResource(
                if (restart) R.string.hiding_restart_list_hint else R.string.hiding_list_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        if (signal == Signal.VPN) {
            Text(
                text = stringResource(R.string.hiding_vpn_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            placeholder = { Text(stringResource(R.string.hiding_search_hint)) },
            singleLine = true,
            // Same capsule as the AppOps / app-management search fields; the stock 4dp corner
            // read as a hard-edged rectangle against the rounded cards around it.
            shape = RoundedCornerShape(24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The chips scroll rather than squeeze: three labels are near the width of a phone
            // already, and the count beside them must not be pushed out by an overflow.
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = filter == FILTER_ALL,
                    onClick = { onFilter(FILTER_ALL) },
                    label = { Text(stringResource(R.string.hiding_filter_all)) }
                )
                FilterChip(
                    selected = filter == FILTER_USER,
                    onClick = { onFilter(FILTER_USER) },
                    label = { Text(stringResource(R.string.hiding_filter_user)) }
                )
                FilterChip(
                    selected = filter == FILTER_SYSTEM,
                    onClick = { onFilter(FILTER_SYSTEM) },
                    label = { Text(stringResource(R.string.hiding_filter_system)) }
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.firewall_app_count, visible.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        if (!appsLoaded) {
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
            val listState = rememberLazyListState()
            // A chip or a search term changes what the first row is. Keeping the old offset would
            // open the new list part-way down, with its best match above the visible area.
            LaunchedEffect(filter, query) { listState.scrollToItem(0) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        listed = app.packageName in listed,
                        busy = busyKey == app.packageName,
                        enabled = available && !busy,
                        onToggle = { onToggleApp(app, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: HidingApp,
    listed: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    // Card per app, matching the firewall / autostart / AppOps lists.
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
            Switch(checked = listed, onCheckedChange = onToggle, enabled = enabled)
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
private fun AppIcon(app: HidingApp) {
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

/** Which slice of the installed apps the list is showing. */
private const val FILTER_ALL = 0
private const val FILTER_USER = 1
private const val FILTER_SYSTEM = 2
