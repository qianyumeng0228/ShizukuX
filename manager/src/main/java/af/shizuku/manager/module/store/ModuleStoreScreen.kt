package af.shizuku.manager.module.store

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import af.shizuku.manager.R
import af.shizuku.manager.ui.compose.ShizukuIcon

/**
 * Online module store for the ADB module page.
 *
 * Reads the official `shizukux-modules` catalog, shows what is already installed, and hands
 * the downloaded ZIP to `AdbModuleManager.install` — the same install path as a locally
 * picked ZIP, so the ZIP guards (path traversal, size budget, module.prop validation) all
 * still apply.
 */
@Composable
internal fun ModuleStoreScreen(
    state: ModuleStoreUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: (ModuleStoreEntry) -> Unit,
    onDismissError: () -> Unit,
    showTabBar: Boolean = false,
    onSelectTab: (Int) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(state.entries, query) {
        val needle = query.trim()
        if (needle.isEmpty()) state.entries
        else state.entries.filter { entry ->
            entry.id.contains(needle, ignoreCase = true) ||
                entry.displayName.contains(needle, ignoreCase = true) ||
                entry.description?.contains(needle, ignoreCase = true) == true ||
                entry.author?.contains(needle, ignoreCase = true) == true ||
                entry.tags.any { it.contains(needle, ignoreCase = true) }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showTabBar) {
                af.shizuku.manager.home.compose.GlassTabStrip(
                    selectedIndex = af.shizuku.manager.utils.GlassTabs.INDEX_MODULES,
                    onSelectTab = onSelectTab
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.modules_store_title),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        Text(
                            text = when {
                                state.loading -> stringResource(R.string.modules_store_loading)
                                state.entries.isNotEmpty() -> {
                                    val community = state.entries.count { it.isCommunity }
                                    if (community > 0) {
                                        stringResource(
                                            R.string.modules_store_count_mixed,
                                            state.entries.size,
                                            community,
                                            state.sourceHost
                                        )
                                    } else {
                                        stringResource(
                                            R.string.modules_store_count,
                                            state.entries.size,
                                            state.sourceHost
                                        )
                                    }
                                }
                                state.loaded -> stringResource(R.string.modules_store_source_empty, state.sourceHost)
                                else -> stringResource(R.string.modules_store_subtitle)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                    IconButton(onClick = onRefresh, enabled = !state.loading) {
                        ShizukuIcon(
                            icon = R.drawable.ic_refresh_24,
                            contentDescription = stringResource(R.string.modules_store_refresh)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading && state.entries.isEmpty() -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(Modifier.size(32.dp)) }

                state.error != null && state.entries.isEmpty() -> StoreEmpty(
                    title = stringResource(R.string.modules_store_error_title),
                    description = state.error,
                    actionLabel = stringResource(R.string.modules_store_retry),
                    onAction = onRefresh
                )

                state.entries.isEmpty() && state.loaded -> StoreEmpty(
                    title = stringResource(R.string.modules_store_empty_title),
                    description = stringResource(R.string.modules_store_empty_description),
                    actionLabel = stringResource(R.string.modules_store_retry),
                    onAction = onRefresh
                )

                else -> Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.modules_store_search_hint)) },
                        shape = MaterialTheme.shapes.large
                    )
                    if (state.loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (filtered.isEmpty()) {
                        StoreEmpty(
                            title = stringResource(R.string.modules_store_no_result_title),
                            description = stringResource(R.string.modules_store_no_result_description),
                            actionLabel = null,
                            onAction = {}
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                Text(
                                    text = stringResource(R.string.modules_store_policy_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            items(filtered, key = { it.id }) { entry ->
                                StoreCard(
                                    entry = entry,
                                    installedVersionCode = state.installedVersionCode(entry.id),
                                    hasUpdate = state.hasUpdate(entry),
                                    installing = state.installingId == entry.id,
                                    progress = if (state.installingId == entry.id) state.installProgress else null,
                                    anyInstalling = state.installingId != null,
                                    onInstall = { onInstall(entry) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(stringResource(R.string.modules_store_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoreCard(
    entry: ModuleStoreEntry,
    installedVersionCode: Long?,
    hasUpdate: Boolean,
    installing: Boolean,
    progress: Float?,
    anyInstalling: Boolean,
    onInstall: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOfNotNull(
                            entry.version?.let { "v$it" },
                            entry.author,
                            entry.formattedSize
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                when {
                    hasUpdate -> StoreChip(
                        stringResource(R.string.modules_store_chip_update),
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    installedVersionCode != null -> StoreChip(
                        stringResource(R.string.modules_store_chip_installed),
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            entry.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (entry.isCommunity) {
                    StoreChip(
                        stringResource(R.string.modules_store_chip_community),
                        MaterialTheme.colorScheme.tertiaryContainer,
                        MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                if (entry.hasAction) {
                    StoreChip(
                        stringResource(R.string.modules_store_chip_action),
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.hasService) {
                    StoreChip(
                        stringResource(R.string.modules_store_chip_service),
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (entry.usesWebUI) {
                    StoreChip(
                        stringResource(R.string.modules_store_chip_webui),
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                entry.tags.take(3).forEach { tag ->
                    StoreChip(
                        "#$tag",
                        MaterialTheme.colorScheme.secondaryContainer,
                        MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (installing) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.modules_store_installing),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onInstall,
                        enabled = !anyInstalling,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        shape = MaterialTheme.shapes.large
                    ) {
                        ShizukuIcon(
                            icon = R.drawable.ic_install_24,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                hasUpdate -> stringResource(R.string.modules_store_update)
                                installedVersionCode != null -> stringResource(R.string.modules_store_reinstall)
                                else -> stringResource(R.string.modules_store_install)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreChip(text: String, container: Color, content: Color) {
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
private fun StoreEmpty(
    title: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        ShizukuIcon(
            icon = R.drawable.ic_outline_store_24,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null) {
            Spacer(Modifier.height(18.dp))
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}
