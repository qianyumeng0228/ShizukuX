package af.shizuku.manager.wifi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/**
 * The saved Wi-Fi passwords, read from the platform's own store.
 *
 * This screen shows secrets by design - that is the whole feature - so it does not cache them:
 * every visit reads the store again and nothing is written to the app's own preferences.
 *
 * Without a root shell the store is unreadable, but the platform still lists the saved network
 * names, so the page degrades to SSID-only instead of failing outright.
 */
class WifiPasswordActivity : ComponentActivity() {

    private var networks by mutableStateOf<List<WifiNetwork>>(emptyList())
    private var withKeys by mutableStateOf(false)
    private var loading by mutableStateOf(false)
    private var loaded by mutableStateOf(false)
    private var available by mutableStateOf(false)
    private var checked by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                WifiScreen(
                    networks = networks,
                    withKeys = withKeys,
                    loading = loading,
                    loaded = loaded,
                    available = available,
                    checked = checked,
                    error = error,
                    onRefresh = { load() },
                    onDismissError = { error = null }
                )
            }
        }
        load()
    }

    private fun load() {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WifiPasswordManager.load() to WifiPasswordManager.isAvailable()
            }
            available = result.second
            checked = true
            val snapshot = result.first
            if (snapshot == null) {
                error = getString(R.string.wifi_failed)
            } else {
                networks = snapshot.networks
                withKeys = snapshot.withKeys
                loaded = true
            }
            loading = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WifiScreen(
    networks: List<WifiNetwork>,
    withKeys: Boolean,
    loading: Boolean,
    loaded: Boolean,
    available: Boolean,
    checked: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf<String?>(null) }

    val visible = remember(networks, query) {
        if (query.isBlank()) networks
        else networks.filter { it.ssid.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Two-line header like the other feature pages: a lone title line sat high in
                    // the bar and left this page's header visibly taller than its content.
                    Column {
                        Text(
                            text = stringResource(R.string.wifi_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(
                                if (withKeys) R.string.wifi_count else R.string.wifi_count_ssid_only,
                                visible.size
                            ),
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (checked && !available) {
                Text(
                    stringResource(R.string.wifi_need_service),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Scaffold
            }

            if (error != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        error,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_ok)) }
                }
            }

            // The passwords live in a store only a root shell can open. Say that once, up here,
            // instead of repeating it on every row.
            if (loaded && !withKeys) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                ) {
                    Text(
                        stringResource(R.string.wifi_ssid_only_notice),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.wifi_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp)
            )

            // The network count moved up into the header subtitle; a second copy here only added
            // a floating label between the search field and the list.

            if (loaded && visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.wifi_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.ssid }) { network ->
                    WifiRow(
                        network = network,
                        withKeys = withKeys,
                        copied = copied == network.ssid,
                        onCopy = {
                            if (network.key != null) {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(network.ssid, network.key))
                                copied = network.ssid
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun WifiRow(network: WifiNetwork, withKeys: Boolean, copied: Boolean, onCopy: () -> Unit) {
    // Card per network, matching the firewall / autostart / AppOps lists.
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
                .clickable(enabled = network.key != null, onClick = onCopy)
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        network.ssid,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (network.connected) {
                        Spacer(Modifier.width(6.dp))
                        ConnectedChip()
                    }
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    subtitle(network, withKeys),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (withKeys && network.key != null) FontFamily.Monospace else FontFamily.Default,
                    color = if (withKeys && network.key != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (network.key != null) {
                TextButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        stringResource(if (copied) R.string.files_copied else R.string.files_copy),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

/** The badge for the network currently joined, modelled on the other Wi-Fi managers' lists. */
@Composable
private fun ConnectedChip() {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Text(
            stringResource(R.string.wifi_connected),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** The line under the SSID: the password when readable, else what the stored entry is. */
@Composable
private fun subtitle(network: WifiNetwork, withKeys: Boolean): String = when {
    // Without keys every entry has a null key, so a null key here would otherwise read as "open
    // network" and claim something the app cannot actually see.
    !withKeys -> stringResource(R.string.wifi_key_need_root)
    network.key != null -> network.key
    network.enterprise -> stringResource(R.string.wifi_enterprise)
    else -> stringResource(R.string.wifi_open)
}
