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

/**
 * ShizukuX M3 Theme
 * 
 * Implements Material Design 3 theming with:
 * - Dynamic color support (Material You) on Android 12+
 * - Proper M3 color schemes
 * - M3 shape tokens (corner radii)
 * - M3 typography scale
 * 
 * Shape Tokens (M3 Defaults):
 * - ExtraSmall: 4dp
 * - Small: 8dp
 * - Medium: 12dp (used for cards/list items)
 * - Large: 16dp
 * - ExtraLarge: 28dp
 */

// M3 Light Color Scheme - Customized for ShizukuX brand
private val ShizukuLightColorScheme = lightColorScheme(
    primary = Color(0xFF3F51B5), // Shizuku brand color (Indigo)
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EAF6),
    onPrimaryContainer = Color(0xFF1A237E),
    secondary = Color(0xFF5C6BC0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EAF6),
    onSecondaryContainer = Color(0xFF1A237E),
    tertiary = Color(0xFF00897B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F2F1),
    onTertiaryContainer = Color(0xFF004D40),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1C1B1F),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0)
)

// M3 Dark Color Scheme - Customized for ShizukuX brand
private val ShizukuDarkColorScheme = darkColorScheme(
    primary = Color(0xFFBAC3F8),
    onPrimary = Color(0xFF0A1A6B),
    primaryContainer = Color(0xFF273180),
    onPrimaryContainer = Color(0xFFE8EAF6),
    secondary = Color(0xFFC4C6FF),
    onSecondary = Color(0xFF2B3178),
    secondaryContainer = Color(0xFF434791),
    onSecondaryContainer = Color(0xFFE8EAF6),
    tertiary = Color(0xFF4FDDBC),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005047),
    onTertiaryContainer = Color(0xFFE0F2F1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F)
)

@Composable
fun ShizukuPlusTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true, // Enable Material You on Android 12+
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // M3 Color Scheme with dynamic color support
    val colorScheme = when {
        // Dynamic colors (Material You) - Android 12+
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context)
            } else {
                dynamicLightColorScheme(context)
            }
        }
        // Static brand colors
        darkTheme -> ShizukuDarkColorScheme
        else -> ShizukuLightColorScheme
    }
    
    // Update status bar color to match theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        // M3 Shape Tokens - using defaults
        // shapes = MaterialTheme.shapes (already configured by M3)
        content = content
    )
}
