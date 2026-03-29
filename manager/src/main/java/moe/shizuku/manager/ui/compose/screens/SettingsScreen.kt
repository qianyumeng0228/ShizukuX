package moe.shizuku.manager.ui.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import moe.shizuku.manager.ui.compose.components.SettingsListItemWithNavigation
import moe.shizuku.manager.ui.compose.components.SettingsListItemWithSwitch
import moe.shizuku.manager.ui.compose.components.SettingsSectionHeader
import moe.shizuku.manager.ui.compose.components.SettingsSpacer

/**
 * M3-compliant Settings Screen
 * 
 * Architectural decisions:
 * - LazyColumn for efficient rendering of long lists
 * - M3 Shape Tokens: shapes.medium (12dp) for cards
 * - M3 Iconography: Icons.Outlined from material-icons-extended
 * - M3 Grid: 16dp horizontal padding, 8dp vertical spacing between items
 * - M3 Colors: surfaceVariant for cards, onSurfaceVariant for icons
 * 
 * Sections follow M3 grouping guidelines with clear visual hierarchy
 */
@Composable
fun SettingsScreen(
    onNavigateToBehavior: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToAdvanced: () -> Unit,
    onNavigateToAbout: () -> Unit,
    // Feature toggles - connect to ViewModel/Settings
    isWatchdogEnabled: Boolean,
    onWatchdogToggle: (Boolean) -> Unit,
    isAutoStartEnabled: Boolean,
    onAutoStartToggle: (Boolean) -> Unit,
    isVerboseLoggingEnabled: Boolean,
    onVerboseLoggingToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    androidx.compose.material3.Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            color = MaterialTheme.colorScheme.background
        ) {
            SettingsContent(
                isWatchdogEnabled = isWatchdogEnabled,
                onWatchdogToggle = onWatchdogToggle,
                isAutoStartEnabled = isAutoStartEnabled,
                onAutoStartToggle = onAutoStartToggle,
                isVerboseLoggingEnabled = isVerboseLoggingEnabled,
                onVerboseLoggingToggle = onVerboseLoggingToggle,
                onNavigateToBehavior = onNavigateToBehavior,
                onNavigateToAppearance = onNavigateToAppearance,
                onNavigateToAdvanced = onNavigateToAdvanced,
                onNavigateToAbout = onNavigateToAbout
            )
        }
    }
}

@Composable
private fun SettingsContent(
    isWatchdogEnabled: Boolean,
    onWatchdogToggle: (Boolean) -> Unit,
    isAutoStartEnabled: Boolean,
    onAutoStartToggle: (Boolean) -> Unit,
    isVerboseLoggingEnabled: Boolean,
    onVerboseLoggingToggle: (Boolean) -> Unit,
    onNavigateToBehavior: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToAdvanced: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp) // M3 8dp grid system
    ) {
        // ==================== BEHAVIOR SECTION ====================
        item {
            SettingsSectionHeader(title = "Behavior")
        }
        
        item {
            SettingsListItemWithSwitch(
                title = "Watchdog Service",
                subtitle = "Automatically restart service if it crashes",
                leadingIcon = Icons.Outlined.Shield, // M3 standard icon
                checked = isWatchdogEnabled,
                onCheckedChange = onWatchdogToggle
            )
        }
        
        item {
            SettingsListItemWithSwitch(
                title = "Auto-start on Boot",
                subtitle = "Start service automatically when device boots",
                leadingIcon = Icons.Outlined.Verified, // M3 standard icon
                checked = isAutoStartEnabled,
                onCheckedChange = onAutoStartToggle
            )
        }
        
        item {
            SettingsListItemWithSwitch(
                title = "Verbose Logging",
                subtitle = "Enable detailed logging for debugging",
                leadingIcon = Icons.Outlined.BugReport, // M3 standard icon
                checked = isVerboseLoggingEnabled,
                onCheckedChange = onVerboseLoggingToggle
            )
        }

        item {
            SettingsSpacer()
        }

        // ==================== APPEARANCE SECTION ====================
        item {
            SettingsSectionHeader(title = "Appearance")
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "Theme",
                subtitle = "Light, Dark, or System default",
                leadingIcon = Icons.Outlined.Palette, // M3 standard icon (replaces broken SVG)
                onClick = onNavigateToAppearance
            )
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "Icon Style",
                subtitle = "Customize app icon appearance",
                leadingIcon = Icons.Outlined.Image, // M3 standard icon
                onClick = { /* Navigate to icon style */ }
            )
        }

        item {
            SettingsSpacer()
        }

        // ==================== ADVANCED SECTION ====================
        item {
            SettingsSectionHeader(title = "Advanced")
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "Developer Options",
                subtitle = "Advanced settings for power users",
                leadingIcon = Icons.Outlined.Build, // M3 standard icon (replaces broken SVG)
                onClick = onNavigateToAdvanced
            )
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "Storage",
                subtitle = "Manage app data and cache",
                leadingIcon = Icons.Outlined.Storage, // M3 standard icon
                onClick = { /* Navigate to storage */ }
            )
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "Network",
                subtitle = "ADB and network settings",
                leadingIcon = Icons.Outlined.DataUsage, // M3 standard icon
                onClick = { /* Navigate to network */ }
            )
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "Security",
                subtitle = "Permission and authentication settings",
                leadingIcon = Icons.Outlined.Security, // M3 standard icon
                onClick = { /* Navigate to security */ }
            )
        }

        item {
            SettingsSpacer()
        }

        // ==================== ABOUT SECTION ====================
        item {
            SettingsSectionHeader(title = "About")
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "About ShizukuX",
                subtitle = "Version 13.6.0.r1493-shizukuplus",
                leadingIcon = Icons.Outlined.Info, // M3 standard icon
                onClick = onNavigateToAbout
            )
        }
        
        item {
            SettingsListItemWithNavigation(
                title = "Help & Feedback",
                subtitle = "Get help or report issues",
                leadingIcon = Icons.Outlined.Help, // M3 standard icon
                onClick = { /* Navigate to help */ }
            )
        }
    }
}

/**
 * Preview Data Class for Settings Items
 * Used for grouping related settings in a more structured way
 */
data class SettingsGroup(
    val title: String,
    val items: List<SettingsItem>
)

sealed class SettingsItem {
    data class Switch(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val isChecked: Boolean,
        val onToggle: (Boolean) -> Unit
    ) : SettingsItem()
    
    data class Navigation(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val icon: androidx.compose.ui.graphics.vector.ImageVector,
        val onClick: () -> Unit
    ) : SettingsItem()
}
