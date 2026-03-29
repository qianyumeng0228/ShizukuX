package moe.shizuku.manager.ui.compose.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import rikka.material.widget.ThemeSwitchableView

/**
 * ShizukuX M3E (Material 3 Expressive) Theme for Compose
 * 
 * Integrates with the existing rikka.material M3E theme system.
 * 
 * M3E Shape Tokens (from rikka.material):
 * - ExtraSmall: 4dp
 * - Small: 8dp  
 * - Medium: 12dp (used for cards/list items)
 * - Large: 16dp
 * - ExtraLarge: 28dp
 * 
 * M3E Color Tokens:
 * - Uses rikka.material's Theme.Material3Expressive
 * - Dynamic color support via Material You (Android 12+)
 * - Custom brand color: #3F51B5 (Indigo)
 */

// M3E Light Color Scheme - Matches rikka.material M3E Light theme
private val M3ELightColorScheme = lightColorScheme(
    // Primary - Shizuku brand indigo
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    
    // Secondary - M3E secondary palette
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    
    // Tertiary - M3E tertiary palette
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    
    // Error
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    
    // Surface variants - M3E specific
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    
    // Outline
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    
    // Inverse
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFD0BCFF),
    
    // Surface tones (M3E specific)
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF3F3F3),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6)
)

// M3E Dark Color Scheme - Matches rikka.material M3E Dark theme
private val M3EDarkColorScheme = darkColorScheme(
    // Primary
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    
    // Secondary
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    
    // Tertiary
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    
    // Error
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    
    // Surface variants
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    
    // Outline
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    
    // Inverse
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    inversePrimary = Color(0xFF6750A4),
    
    // Surface tones (M3E specific)
    surfaceContainerLowest = Color(0xFF0A0A0A),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B)
)

@Composable
fun ShizukuPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Enable Material You on Android 12+
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // M3E Color Scheme with dynamic color support
    val colorScheme = when {
        // Dynamic colors (Material You) - Android 12+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        // Static M3E brand colors
        darkTheme -> M3EDarkColorScheme
        else -> M3ELightColorScheme
    }
    
    // M3E Shape Tokens - matching rikka.material defaults
    val shapes = androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), // Cards use this
        large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    )
    
    // Update status bar color to match M3E theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes,
        typography = androidx.compose.material3.Typography(), // M3E uses default M3 typography
        content = content
    )
}

/**
 * Get the current M3E color scheme
 */
@Composable
fun m3eColorScheme(): androidx.compose.material3.ColorScheme {
    return MaterialTheme.colorScheme
}

/**
 * Get the current M3E shape for medium containers (cards, list items)
 */
@Composable
fun m3eMediumShape(): androidx.compose.foundation.shape.RoundedCornerShape {
    return MaterialTheme.shapes.medium
}
