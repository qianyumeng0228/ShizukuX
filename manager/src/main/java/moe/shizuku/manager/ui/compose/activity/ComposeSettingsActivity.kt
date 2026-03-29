package moe.shizuku.manager.ui.compose.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ui.compose.screens.SettingsScreen
import moe.shizuku.manager.ui.compose.theme.ShizukuPlusTheme

/**
 * Compose-based Settings Activity with M3E Theme
 * 
 * Demonstrates proper M3E implementation with:
 * - Edge-to-edge display
 * - Dynamic color support (Material You)
 * - Dark/Light theme switching
 * - M3E shape tokens (12dp medium for cards)
 * - M3E color palette
 */
class ComposeSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display (M3E best practice)
        enableEdgeToEdge()
        
        setContent {
            // Get theme settings from app preferences
            val isDarkTheme = when (ShizukuSettings.getNightMode()) {
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> true
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> isSystemInDarkTheme()
            }
            
            // Use M3E theme instead of standard M3
            ShizukuPlusTheme(
                darkTheme = isDarkTheme,
                dynamicColor = true // Enable Material You dynamic colors on Android 12+
            ) {
                SettingsScreen(
                    onNavigateToBehavior = {
                        // Navigate to behavior settings
                    },
                    onNavigateToAppearance = {
                        // Navigate to appearance settings
                    },
                    onNavigateToAdvanced = {
                        // Navigate to advanced settings
                    },
                    onNavigateToAbout = {
                        // Navigate to about screen
                    },
                    // Feature toggles - connected to ShizukuSettings
                    isWatchdogEnabled = ShizukuSettings.getWatchdog(),
                    onWatchdogToggle = { enabled ->
                        ShizukuSettings.setWatchdog(this, enabled)
                    },
                    isAutoStartEnabled = ShizukuSettings.getPreferences()
                        .getBoolean("start_on_boot", true),
                    onAutoStartToggle = { enabled ->
                        ShizukuSettings.getPreferences().edit()
                            .putBoolean("start_on_boot", enabled)
                            .apply()
                    },
                    isVerboseLoggingEnabled = ShizukuSettings.getPreferences()
                        .getBoolean("verbose_logging", false),
                    onVerboseLoggingToggle = { enabled ->
                        ShizukuSettings.getPreferences().edit()
                            .putBoolean("verbose_logging", enabled)
                            .apply()
                    }
                )
            }
        }
    }
}
