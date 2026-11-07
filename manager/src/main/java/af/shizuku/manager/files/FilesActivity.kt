package af.shizuku.manager.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.ui.compose.ShizukuIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** A file opened for reading; [text] is null when the platform refused or it is binary. */
private data class Preview(
    val name: String,
    val path: String,
    val text: String?,
    val binary: Boolean,
    val size: Long
)

/**
 * A read-only browser for the parts of the device an app cannot see.
 *
 * Everything below `/data` needs the Shizuku user to read, which is why this belongs here rather
 * than in a normal file picker: it is the same privilege the rest of the app already holds.
 */
class FilesActivity : ComponentActivity() {

    private var path by mutableStateOf(FileManager.SHORTCUTS[1])
    private var entries by mutableStateOf<List<FileEntry>>(emptyList())
    private var loading by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var available by mutableStateOf(false)
    private var checked by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var preview by mutableStateOf<Preview?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                FilesScreen(
                    path = path,
                    entries = entries,
                    loading = loading,
                    loaded = loaded,
                    available = available,
                    checked = checked,
                    error = error,
                    preview = preview,
                    onNavigate = { open(it) },
                    onRefresh = { open(path) },
                    onDismissError = { error = null },
                    onPreview = { openPreview(it) },
                    onDismissPreview = { preview = null }
                )
            }
        }
        open(path)
    }

    private fun open(target: String) {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FileManager.list(target) to FileManager.isAvailable()
                }
                available = result.second
                checked = true
                val list = result.first
                if (list == null) {
                    // Keep the old listing on screen and explain instead: a directory that cannot
                    // be read (permissions, SELinux) must not leave an empty page behind.
                    error = getString(R.string.files_failed)
                } else {
                    path = target
                    entries = list
                    loaded = true
                }
            } catch (t: Throwable) {
                error = getString(R.string.files_failed)
            } finally {
                // Any exit path has to release the header spinner.
                loading = false
            }
        }
    }

    private fun openPreview(entry: FileEntry) {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val text = FileManager.readText(entry.path, entry.size)
                    val binary = text != null && FileManager.looksBinary(text)
                    Preview(
                        name = entry.name,
                        path = entry.path,
                        text = if (binary) null else text,
                        binary = binary,
                        size = entry.size
                    )
                }
                preview = result
            } finally {
                // Even a failed read has to release the header spinner, or the page stays busy
                // forever with nothing to show for it.
                loading = false
            }
        }
    }
}

/** The parent of [path]; `/` is its own parent. */
private fun parentOf(path: String): String {
    if (path == "/") return "/"
    val cut = path.trimEnd('/').lastIndexOf('/')
    return if (cut <= 0) "/" else path.trimEnd('/').substring(0, cut)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilesScreen(
    path: String,
    entries: List<FileEntry>,
    loading: Boolean,
    loaded: Boolean,
    available: Boolean,
    checked: Boolean,
    error: String?,
    preview: Preview?,
    onNavigate: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onPreview: (FileEntry) -> Unit,
    onDismissPreview: () -> Unit
) {
    val context = LocalContext.current
    BackHandler(enabled = path != "/") { onNavigate(parentOf(path)) }

    if (error != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.files_title)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_ok)) }
            }
        )
    }

    preview?.let { item ->
        AlertDialog(
            onDismissRequest = onDismissPreview,
            title = { Text(stringResource(R.string.files_preview_title)) },
            text = {
                Column(Modifier.heightIn(max = 520.dp)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        item.path,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        item.binary -> Text(
                            stringResource(R.string.files_binary_hint),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        item.text == null -> Text(
                            stringResource(R.string.files_failed),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> Box(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 380.dp)) {
                            Text(
                                item.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (item.text != null) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(item.name, item.text))
                    }
                    onDismissPreview()
                }) {
                    Text(
                        stringResource(
                            if (item.text == null) R.string.action_ok else R.string.files_copy
                        )
                    )
                }
            },
            dismissButton = {
                if (item.text != null) {
                    TextButton(onClick = onDismissPreview) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.files_title))
                        Text(
                            path,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (path == "/") (context as? ComponentActivity)?.finish()
                        else onNavigate(parentOf(path))
                    }) {
                        ShizukuIcon(
                            icon = R.drawable.ic_arrow_back_24,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        ShizukuIcon(
                            icon = R.drawable.ic_refresh_24,
                            contentDescription = stringResource(R.string.appops_refresh)
                        )
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
                    stringResource(R.string.files_need_service),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Scaffold
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = false,
                    onClick = { onNavigate(parentOf(path)) },
                    label = { Text(stringResource(R.string.files_up)) }
                )
                listOf(
                    "/" to R.string.files_shortcut_root,
                    "/sdcard" to R.string.files_shortcut_storage,
                    "/data/adb" to R.string.files_shortcut_adb,
                    "/data/local/tmp" to R.string.files_shortcut_tmp,
                    "/data/system" to R.string.files_shortcut_system,
                    "/data/misc" to R.string.files_shortcut_misc
                ).forEach { (target, label) ->
                    FilterChip(
                        selected = path == target,
                        onClick = { onNavigate(target) },
                        label = { Text(stringResource(label)) }
                    )
                }
            }

            Text(
                text = stringResource(R.string.files_items, entries.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (loaded && entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.files_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { entry ->
                    FileRow(entry = entry, onClick = {
                        if (entry.directory) onNavigate(entry.path) else onPreview(entry)
                    })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FileRow(entry: FileEntry, onClick: () -> Unit) {
    val linkLabel = stringResource(R.string.files_link)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(
                if (entry.directory) R.drawable.ic_folder_24 else R.drawable.ic_file_24
            ),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (entry.directory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (!entry.directory) append(formatSize(entry.size)).append(" · ")
                    append(entry.permissions).append(" · ").append(entry.owner)
                    if (entry.link) append(" · ").append(linkLabel)
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            DateFormat.getDateInstance(DateFormat.SHORT).format(Date(entry.mtime * 1000)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
