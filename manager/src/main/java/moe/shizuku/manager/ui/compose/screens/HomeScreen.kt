package moe.shizuku.manager.ui.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.EnvironmentUtils

// IDs matching HomeAdapter.java
private const val ID_STATUS = 0L
private const val ID_APPS = 1L
private const val ID_TERMINAL = 2L
private const val ID_START_ROOT = 3L
private const val ID_START_WADB = 4L
private const val ID_START_ADB = 5L
private const val ID_LEARN_MORE = 6L
private const val ID_ADB_PERMISSION_LIMITED = 7L
private const val ID_AUTOMATION = 8L

/**
 * Exact replica of shape_expressive_leaf_background.xml
 */
val LeafShape = GenericShape { size, _ ->
    addRoundRect(
        RoundRect(
            rect = Rect(0f, 0f, size.width, size.height),
            topLeft = CornerRadius(24.dp.toPx()),
            topRight = CornerRadius(8.dp.toPx()),
            bottomRight = CornerRadius(24.dp.toPx()),
            bottomLeft = CornerRadius(8.dp.toPx())
        )
    )
}

/**
 * Exact replica of shape_droplet_background.xml
 */
val DropletShape = GenericShape { size, _ ->
    addRoundRect(
        RoundRect(
            rect = Rect(0f, 0f, size.width, size.height),
            topLeft = CornerRadius(4.dp.toPx()),
            topRight = CornerRadius(48.dp.toPx()),
            bottomRight = CornerRadius(48.dp.toPx()),
            bottomLeft = CornerRadius(48.dp.toPx())
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    serviceVersion: String?,
    serviceMode: String?,
    grantedCount: Int,
    adbPermission: Boolean,
    isPrimaryUser: Boolean,
    isRooted: Boolean,
    isWadbAvailable: Boolean,
    cardOrder: List<Long>,
    hiddenCards: Set<String>,
    onStartServiceClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAppsClick: () -> Unit,
    onAdbClick: () -> Unit,
    onPairClick: () -> Unit,
    onGuideClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onAutomationClick: () -> Unit,
    onLearnMoreClick: () -> Unit,
    onActivityLogClick: () -> Unit,
    onRestoreHiddenCards: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val haptic = LocalHapticFeedback.current

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 22.sp
                        )
                    )
                },
                actions = {
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onSettingsClick()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings"
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        val screenWidth = LocalConfiguration.current.screenWidthDp
        val columns = if (screenWidth >= 600) 2 else 1

        val draggableCards = cardOrder.filter { id ->
            if (id.toString() in hiddenCards) return@filter false
            when (id) {
                ID_TERMINAL -> adbPermission && ShizukuSettings.showTerminalHome()
                ID_START_ROOT -> isPrimaryUser && isRooted
                ID_START_WADB -> isPrimaryUser && isWadbAvailable
                ID_START_ADB -> isPrimaryUser && ShizukuSettings.showStartAdbHome()
                ID_AUTOMATION -> ShizukuSettings.showAutomationHome()
                ID_LEARN_MORE -> ShizukuSettings.showLearnMoreHome()
                else -> false
            }
        }
        
        val showEmptyState = draggableCards.isEmpty() && !adbPermission && (!isServiceRunning || adbPermission)

        if (showEmptyState) {
            EmptyStateView(
                modifier = Modifier.padding(paddingValues),
                onRestoreClick = onRestoreHiddenCards
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = 16.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Status Card (ID_STATUS)
                item(span = { GridItemSpan(columns) }) {
                    StatusCard(
                        isRunning = isServiceRunning,
                        version = serviceVersion,
                        mode = serviceMode,
                        onActivityLogClick = onActivityLogClick
                    )
                }

                // Fixed: Manage Apps (ID_APPS)
                if (adbPermission) {
                    item {
                        HomeCard(
                            title = stringResource(R.string.home_app_management_title),
                            summary = stringResource(R.string.home_app_management_summary, grantedCount),
                            icon = painterResource(R.drawable.ic_server_ok_24dp),
                            onClick = onAppsClick
                        )
                    }
                }

                // Fixed: ADB Permission Limited (ID_ADB_PERMISSION_LIMITED)
                if (isServiceRunning && !adbPermission) {
                    item {
                        HomeCard(
                            title = "Limited Permission",
                            summary = "ADB permission is required for full functionality.",
                            icon = painterResource(R.drawable.ic_server_error_24dp),
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            onClick = onAppsClick
                        )
                    }
                }

                // Draggable cards
                draggableCards.forEach { id ->
                    when (id) {
                        ID_TERMINAL -> {
                            item {
                                HomeCard(
                                    title = stringResource(R.string.home_terminal_title),
                                    summary = if (isServiceRunning) stringResource(R.string.home_terminal_description) else "Service not running",
                                    icon = painterResource(R.drawable.ic_terminal_24),
                                    enabled = isServiceRunning,
                                    onClick = onTerminalClick
                                )
                            }
                        }
                        ID_START_ROOT -> {
                            item {
                                HomeCard(
                                    title = "Start via Root",
                                    summary = if (isServiceRunning && serviceMode == "Root") "Service is running via Root" else "Tap to start or restart with root access",
                                    icon = painterResource(R.drawable.ic_server_ok_24dp),
                                    onClick = onStartServiceClick
                                )
                            }
                        }
                        ID_START_WADB -> {
                            item(span = { GridItemSpan(columns) }) {
                                WirelessAdbCard(
                                    onStartClick = onAdbClick,
                                    onPairClick = onPairClick,
                                    onGuideClick = onGuideClick
                                )
                            }
                        }
                        ID_START_ADB -> {
                            item {
                                HomeCard(
                                    title = "Start via ADB",
                                    summary = "Connect to a computer to start",
                                    icon = painterResource(R.drawable.ic_terminal_24),
                                    onClick = onAdbClick
                                )
                            }
                        }
                        ID_AUTOMATION -> {
                            item {
                                HomeCard(
                                    title = "Automation",
                                    summary = "Automate tasks and workflows",
                                    icon = painterResource(R.drawable.ic_server_ok_24dp),
                                    onClick = onAutomationClick
                                )
                            }
                        }
                        ID_LEARN_MORE -> {
                            item {
                                HomeCard(
                                    title = stringResource(R.string.home_adb_button_view_help),
                                    summary = "Documentation and guides",
                                    icon = painterResource(R.drawable.ic_server_ok_24dp),
                                    onClick = onLearnMoreClick
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
private fun StatusCard(
    isRunning: Boolean,
    version: String?,
    mode: String?,
    onActivityLogClick: () -> Unit
) {
    val containerColor = if (isRunning) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    
    val contentColor = if (isRunning) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(36.dp),
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(color = contentColor, shape = DropletShape)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(
                        if (isRunning) R.drawable.ic_server_ok_24dp 
                        else R.drawable.ic_server_error_24dp
                    ),
                    contentDescription = null,
                    tint = containerColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRunning) {
                        stringResource(R.string.home_status_service_is_running, stringResource(R.string.app_name))
                    } else {
                        stringResource(R.string.home_status_service_not_running, stringResource(R.string.app_name))
                    },
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                
                if (version != null || mode != null) {
                    Text(
                        text = if (isRunning) "Version $version, $mode" else stringResource(R.string.home_status_service_stopped_tile_sublabel),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                        alpha = 0.8f
                    )
                }

                if (isRunning && ShizukuSettings.showActivityLogHome()) {
                    TextButton(
                        onClick = onActivityLogClick,
                        modifier = Modifier.padding(top = 8.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_activity_log),
                            color = contentColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WirelessAdbCard(
    onStartClick: () -> Unit,
    onPairClick: () -> Unit,
    onGuideClick: () -> Unit
) {
    LargeHomeCard(
        title = stringResource(R.string.home_wireless_adb_title),
        summary = if (EnvironmentUtils.isTlsSupported()) {
            stringResource(R.string.home_wireless_adb_description)
        } else {
            stringResource(R.string.home_wireless_adb_description_pre_11)
        },
        icon = painterResource(R.drawable.ic_wadb_24),
        actions = {
            if (EnvironmentUtils.isTlsSupported()) {
                TextButton(
                    onClick = onGuideClick,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_outline_open_in_new_24), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_wireless_adb_view_guide_button))
                }
                TextButton(
                    onClick = onPairClick,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(painterResource(R.drawable.ic_baseline_link_24), null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.adb_pairing))
                }
            }
            TextButton(
                onClick = onStartClick
            ) {
                Icon(painterResource(R.drawable.ic_server_start_24dp), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_root_button_start))
            }
        }
    )
}

@Composable
private fun HomeCard(
    title: String,
    summary: String,
    icon: Painter,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        shape = RoundedCornerShape(36.dp),
        color = containerColor,
        contentColor = contentColor,
        alpha = if (enabled) 1f else 0.5f
    ) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = LeafShape
                    )
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(24.dp))

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelLarge,
                    color = contentColor.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun LargeHomeCard(
    title: String,
    summary: String,
    icon: Painter,
    actions: @Composable RowScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(36.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = LeafShape
                        )
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(24.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = summary,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                content = actions
            ) {
            }
        }
    }
}

@Composable
private fun EmptyStateView(
    modifier: Modifier = Modifier,
    onRestoreClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_empty_home_24),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_state_title_no_home_cards),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_state_description_no_home_cards),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRestoreClick) {
            Text(text = stringResource(R.string.empty_state_action_restore_home_cards))
        }
    }
}
