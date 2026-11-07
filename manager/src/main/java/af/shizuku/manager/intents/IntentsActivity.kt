package af.shizuku.manager.intents

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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

/** One app whose launcher activity can be started. */
private data class LaunchApp(
    val packageName: String,
    val label: String,
    val system: Boolean,
    val info: ApplicationInfo
)

/**
 * Starts activities and sends intents through the Shizuku user.
 *
 * The list half is the practical side of "Activities": pick an app and start it even when the
 * platform would refuse to, because the shell user is allowed to. The manual half exists for the
 * case that has no app at all - a deep link, a settings screen, a broadcast-only action.
 */
class IntentsActivity : ComponentActivity() {

    private var apps by mutableStateOf<List<LaunchApp>>(emptyList())
    private var loading by mutableStateOf(false)
    private var busy by mutableStateOf<String?>(null)
    private var available by mutableStateOf(false)
    private var checked by mutableStateOf(false)
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
                IntentsScreen(
                    apps = apps,
                    loading = loading,
                    busy = busy,
                    available = available,
                    checked = checked,
                    message = message,
                    onLaunch = { launch(it) },
                    onSend = { action, data, pkg, component, extras ->
                        send(action, data, pkg, component, extras)
                    },
                    onDismissMessage = { message = null }
                )
            }
        }
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    val pm = packageManager
                    val userId = UserHandleCompat.myUserId()
                    PackageEnumerator.installedApplications(userId).orEmpty()
                        .distinctBy { it.packageName }
                        .map { info ->
                            LaunchApp(
                                packageName = info.packageName,
                                label = runCatching { info.loadLabel(pm).toString() }
                                    .getOrDefault(info.packageName),
                                system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                                info = info
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                }.getOrDefault(emptyList())
            }
            available = IntentManager.isAvailable()
            checked = true
            apps = list
            loading = false
        }
    }

    private fun launch(packageName: String) {
        if (busy != null) return
        busy = packageName
        lifecycleScope.launch {
            // busy must always be released: a throwing shell call used to leave it set, and every
            // row (and the manual dialog) gates on it, so one failure froze the whole screen.
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { IntentManager.launch(packageName) }.getOrNull()
                }
                message = result ?: getString(R.string.intents_launch_failed)
            } finally {
                busy = null
            }
        }
    }

    private fun send(action: String, data: String, pkg: String, component: String, extras: String) {
        if (busy != null) return
        busy = "*"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching { IntentManager.send(action, data, pkg, component, extras) }.getOrNull()
                }
                // am echoes "Starting: Intent {...}" on success and "Error: ..." on failure; put
                // the verdict first so the dialog is readable without parsing the dump below it.
                message = when {
                    result == null -> getString(R.string.intents_launch_failed)
                    result.contains("Error") || result.contains("Exception") ->
                        getString(R.string.intents_send_failed) + "\n\n" + result
                    else -> getString(R.string.intents_sent) + "\n\n" + result
                }
            } finally {
                busy = null
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntentsScreen(
    apps: List<LaunchApp>,
    loading: Boolean,
    busy: String?,
    available: Boolean,
    checked: Boolean,
    message: String?,
    onLaunch: (String) -> Unit,
    onSend: (String, String, String, String, String) -> Unit,
    onDismissMessage: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }

    val visible = remember(apps, query) {
        if (query.isBlank()) apps
        else apps.filter {
            it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = onDismissMessage,
            title = { Text(stringResource(R.string.intents_result)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissMessage) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    if (manual) {
        ManualIntentDialog(
            onDismiss = { manual = false },
            onSend = { action, data, pkg, component, extras ->
                manual = false
                onSend(action, data, pkg, component, extras)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Two-line header, matching the other feature pages.
                    Column {
                        Text(
                            text = stringResource(R.string.intents_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.intents_count, visible.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        ShizukuIcon(
                            icon = R.drawable.ic_arrow_back_24,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (loading || busy != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    TextButton(onClick = { manual = true }) {
                        Text(stringResource(R.string.intents_manual))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (checked && !available) {
                Text(
                    stringResource(R.string.intents_need_service),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Scaffold
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.appops_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp)
            )

            if (apps.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.intents_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.packageName }) { app ->
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
                            .clickable(enabled = busy == null) { onLaunch(app.packageName) }
                            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app)
                        Spacer(Modifier.size(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
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
                        TextButton(
                            onClick = { onLaunch(app.packageName) },
                            enabled = busy == null,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                stringResource(R.string.intents_start),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualIntentDialog(
    onDismiss: () -> Unit,
    onSend: (String, String, String, String, String) -> Unit
) {
    var action by remember { mutableStateOf("android.intent.action.VIEW") }
    var data by remember { mutableStateOf("") }
    var pkg by remember { mutableStateOf("") }
    var component by remember { mutableStateOf("") }
    var extras by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.intents_manual)) },
        text = {
            // Five stacked fields are taller than the dialog on a short screen once the keyboard
            // is up, so the body scrolls instead of pushing the buttons off-screen.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.intents_action)) }
                )
                OutlinedTextField(
                    value = data,
                    onValueChange = { data = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.intents_data)) }
                )
                OutlinedTextField(
                    value = component,
                    onValueChange = { component = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.intents_component)) }
                )
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.intents_package)) }
                )
                OutlinedTextField(
                    value = extras,
                    onValueChange = { extras = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.intents_extras)) }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSend(action, data, pkg, component, extras) },
                // am refuses to start without an action; catch that here instead of showing a
                // failure dialog after the fact.
                enabled = action.isNotBlank()
            ) {
                Text(stringResource(R.string.intents_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}


/** The row icon, loaded off the main thread and cached by package + size. */
@Composable
private fun AppIcon(app: LaunchApp) {
    val context = LocalContext.current
    val size = 40.dp
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        app.packageName
    ) {
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