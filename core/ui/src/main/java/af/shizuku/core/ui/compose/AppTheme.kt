package af.shizuku.core.ui.compose

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR

/**
 * Reads the ColorScheme from the hosting Activity's own theme instead of having Compose decide
 * dynamic-color/custom-accent/default independently. By the time this runs, AppActivity's
 * onApplyUserThemeResource (see ThemeDelegateImpl) has already applied the correct ThemeOverlay -
 * system dynamic color, a custom accent, the default blue, or black night theme - to the hosting
 * Activity's theme. Reading those resolved attributes keeps Compose screens visually identical to
 * the rest of the (XML-themed) app instead of falling back to Compose's own generic baseline
 * scheme whenever dynamic color isn't available or a custom accent is set.
 */
private fun androidColorScheme(context: Context, darkTheme: Boolean): ColorScheme {
    val fallback = if (darkTheme) darkColorScheme() else lightColorScheme()
    fun color(attr: Int, fallbackColor: Color) =
        Color(MaterialColors.getColor(context, attr, fallbackColor.toArgb()))

    // colorPrimary and colorError are declared by appcompat, not material - with non-transitive
    // R classes they only exist in appcompat's R.
    val primary = color(androidx.appcompat.R.attr.colorPrimary, fallback.primary)
    val onPrimary = color(MaterialR.attr.colorOnPrimary, fallback.onPrimary)
    val primaryContainer = color(MaterialR.attr.colorPrimaryContainer, fallback.primaryContainer)
    val onPrimaryContainer = color(MaterialR.attr.colorOnPrimaryContainer, fallback.onPrimaryContainer)
    val secondary = color(MaterialR.attr.colorSecondary, fallback.secondary)
    val onSecondary = color(MaterialR.attr.colorOnSecondary, fallback.onSecondary)
    val secondaryContainer = color(MaterialR.attr.colorSecondaryContainer, fallback.secondaryContainer)
    val onSecondaryContainer = color(MaterialR.attr.colorOnSecondaryContainer, fallback.onSecondaryContainer)
    val tertiary = color(MaterialR.attr.colorTertiary, fallback.tertiary)
    val onTertiary = color(MaterialR.attr.colorOnTertiary, fallback.onTertiary)
    val tertiaryContainer = color(MaterialR.attr.colorTertiaryContainer, fallback.tertiaryContainer)
    val onTertiaryContainer = color(MaterialR.attr.colorOnTertiaryContainer, fallback.onTertiaryContainer)
    val error = color(androidx.appcompat.R.attr.colorError, fallback.error)
    val onError = color(MaterialR.attr.colorOnError, fallback.onError)
    val errorContainer = color(MaterialR.attr.colorErrorContainer, fallback.errorContainer)
    val onErrorContainer = color(MaterialR.attr.colorOnErrorContainer, fallback.onErrorContainer)
    val background = color(android.R.attr.colorBackground, fallback.background)
    val onBackground = color(MaterialR.attr.colorOnBackground, fallback.onBackground)
    val surface = color(MaterialR.attr.colorSurface, fallback.surface)
    val onSurface = color(MaterialR.attr.colorOnSurface, fallback.onSurface)
    val surfaceVariant = color(MaterialR.attr.colorSurfaceVariant, fallback.surfaceVariant)
    val onSurfaceVariant = color(MaterialR.attr.colorOnSurfaceVariant, fallback.onSurfaceVariant)
    val outline = color(MaterialR.attr.colorOutline, fallback.outline)
    val scrim = color(MaterialR.attr.scrimBackground, fallback.scrim)
    val surfaceBright = color(MaterialR.attr.colorSurfaceBright, fallback.surfaceBright)
    val surfaceDim = color(MaterialR.attr.colorSurfaceDim, fallback.surfaceDim)
    val surfaceContainer = color(MaterialR.attr.colorSurfaceContainer, fallback.surfaceContainer)
    val surfaceContainerHigh = color(MaterialR.attr.colorSurfaceContainerHigh, fallback.surfaceContainerHigh)
    val surfaceContainerHighest = color(MaterialR.attr.colorSurfaceContainerHighest, fallback.surfaceContainerHighest)
    val surfaceContainerLow = color(MaterialR.attr.colorSurfaceContainerLow, fallback.surfaceContainerLow)
    val surfaceContainerLowest = color(MaterialR.attr.colorSurfaceContainerLowest, fallback.surfaceContainerLowest)

    // darkColorScheme()/lightColorScheme() are plain functions (not a shared type with a common
    // named-argument call), so a stored function reference can't be invoked with named args here -
    // hence the duplicated call rather than picking one reference to invoke once.
    return if (darkTheme) {
        darkColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
        )
    } else {
        lightColorScheme(
            primary = primary, onPrimary = onPrimary,
            primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
            secondary = secondary, onSecondary = onSecondary,
            secondaryContainer = secondaryContainer, onSecondaryContainer = onSecondaryContainer,
            tertiary = tertiary, onTertiary = onTertiary,
            tertiaryContainer = tertiaryContainer, onTertiaryContainer = onTertiaryContainer,
            error = error, onError = onError,
            errorContainer = errorContainer, onErrorContainer = onErrorContainer,
            background = background, onBackground = onBackground,
            surface = surface, onSurface = onSurface,
            surfaceVariant = surfaceVariant, onSurfaceVariant = onSurfaceVariant,
            outline = outline,
            scrim = scrim,
            surfaceBright = surfaceBright,
            surfaceDim = surfaceDim,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainerLowest = surfaceContainerLowest,
        )
    }
}

// Mirrors ShapeAppearance.OneUI.Corner.{ExtraSmall,Small,Medium,Large,ExtraLarge}
// (manager/src/main/res/values/themes_overlay.xml) so Compose screens match One UI's rounder
// corner language exactly like the XML side does when ThemeOverlay.OneUI is applied. Only used
// when the caller passes isOneUi = true (see ShizukuSettings.isOneUiThemeEnabled()) - Compose's
// stock Material3 Shapes() is left untouched for every other Shape Style (Modern/Classic/Squircle).
private val OneUiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

// Mirrors ShapeAppearance.Glass.Corner.* (themes_overlay.xml): 28dp cards / 20dp rows.
private val GlassShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * True when the hosting Activity's theme already has ThemeOverlay.Glass applied (see
 * ThemeDelegateImpl). Resolved by attribute name because this module cannot see the manager
 * module's R class; `isGlassUi` is declared in manager/res/values/attrs.xml and set by both
 * glass overlays, so no flag has to be threaded through every Activity call site.
 */
fun isGlassTheme(context: Context): Boolean {
    val id = context.resources.getIdentifier("isGlassUi", "attr", context.packageName)
    if (id == 0) return false
    val tv = android.util.TypedValue()
    return context.theme.resolveAttribute(id, tv, true) && tv.data != 0
}

/** Icon tone used by the glass skin for single-colour glyphs (matches the manager overlays). */
private fun glassScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFFB9A7FF), onPrimary = Color(0xFF201639),
        primaryContainer = Color(0xFF3A2C70), onPrimaryContainer = Color(0xFFE7DEFF),
        secondary = Color(0xFFB9A7FF), onSecondary = Color(0xFF201639),
        secondaryContainer = Color(0xFF33265F), onSecondaryContainer = Color(0xFFE7DEFF),
        tertiary = Color(0xFFD9CFFF), onTertiary = Color(0xFF201639),
        background = Color.Transparent, onBackground = Color(0xFFF2F5FA),
        surface = Color(0x66141A2B), onSurface = Color(0xFFF2F5FA),
        surfaceVariant = Color(0x661A2136), onSurfaceVariant = Color(0xFFA9B4C6),
        surfaceBright = Color(0x801E2740), surfaceDim = Color(0x800C111C),
        surfaceContainerLowest = Color(0x660A0E17), surfaceContainerLow = Color(0x66121828),
        surfaceContainer = Color(0x66161D2F), surfaceContainerHigh = Color(0xFF1B2336),
        surfaceContainerHighest = Color(0xFF202A40),
        outline = Color(0xFF8E86A8), outlineVariant = Color(0xFF4A4560),
        error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF6C55C9), onPrimary = Color.White,
        primaryContainer = Color(0xFFE8E1FF), onPrimaryContainer = Color(0xFF241A45),
        secondary = Color(0xFF6C55C9), onSecondary = Color.White,
        secondaryContainer = Color(0xFFE8E1FF), onSecondaryContainer = Color(0xFF241A45),
        tertiary = Color(0xFF8A76D8), onTertiary = Color.White,
        background = Color.Transparent, onBackground = Color(0xFF1B1B22),
        surface = Color(0xCCF7F6FC), onSurface = Color(0xFF1B1B22),
        surfaceVariant = Color(0xCCE7E4F2), onSurfaceVariant = Color(0xFF54505F),
        surfaceBright = Color(0xE6FFFFFF), surfaceDim = Color(0xCEE4E1EE),
        surfaceContainerLowest = Color(0xF7FFFFFF), surfaceContainerLow = Color(0xF2F8F7FE),
        surfaceContainer = Color(0xF2FBFAFF), surfaceContainerHigh = Color(0xFFF3F1FB),
        surfaceContainerHighest = Color(0xFFEDEAF9),
        outline = Color(0xFF7C7690), outlineVariant = Color(0xFFCCC6DC),
        error = Color(0xFFBA1A1A), onError = Color.White,
        errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    )
}

/**
 * True while the glass skin is active for the current screen. Screens read this to drop their
 * opaque container colours (Scaffold backgrounds, list rows) so the backdrop drawn by [AppTheme]
 * shows through - without it every sub-page looked like a solid sheet with only the app bar
 * tinted.
 */
val LocalGlassUi = staticCompositionLocalOf { false }

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    isBlackNightTheme: Boolean = false,
    isOneUi: Boolean = false,
    themeVersion: Int = 0,
    // Screens hosted by an Activity that has not had ThemeOverlay.Glass applied (plain
    // ComponentActivity screens) pass this in so they still get the glass palette + backdrop
    // instead of falling back to the classic scheme.
    forceGlass: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeGlass = isGlassTheme(context)
    var colorScheme = remember(context, darkTheme, themeVersion, forceGlass) {
        if (forceGlass && !themeGlass) glassScheme(darkTheme) else androidColorScheme(context, darkTheme)
    }

    // Belt-and-suspenders: ThemeOverlay.Black (applied via onApplyUserThemeResource) already
    // forces colorSurface/android:colorBackground to black, so this should be redundant with the
    // read above - kept in case a future overlay change misses one of the two attributes.
    // All surface-container tones are pure black (#496: near-black values like #0D0D0D are visibly
    // grey on high-contrast OLEDs; AMOLED users expect zero pixels lit in backgrounds).
    if (darkTheme && isBlackNightTheme) {
        colorScheme = colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color.Black,
            surfaceContainerHighest = Color.Black,
        )
    }

    val shapes = when {
        isGlassTheme(context) -> GlassShapes
        isOneUi -> OneUiShapes
        else -> Shapes()
    }

    // Glass skin: every Compose screen sits on the same deep gradient backdrop instead of a flat
    // surface colour, and `background` is made transparent so a screen's Scaffold (whose default
    // container colour is exactly background) lets that backdrop through. `surface` stays opaque
    // so dialogs, cards and sheets keep their own solid tone.
    val glass = forceGlass || themeGlass
    if (glass) {
        colorScheme = colorScheme.copy(background = Color.Transparent)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = shapes
    ) {
        CompositionLocalProvider(LocalGlassUi provides glass) {
            if (glass) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind { drawGlassBackdrop(darkTheme) }
                ) {
                    content()
                }
            } else {
                content()
            }
        }
    }
}

/**
 * Static backdrop for the glass skin, matching manager/.../WallpaperBackground.kt's GlassBackground
 * so sub-pages and the home screen share one look. Drawn once (no per-frame work): a vertical
 * three-stop gradient plus two soft radial highlights.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlassBackdrop(dark: Boolean) {
    val h = size.height
    val w = size.width
    val base = listOf(
        Color(0xFF25335A), Color(0xFF121829), Color(0xFF080B12)
    )
    val light = listOf(
        Color(0xFFEEF2FE), Color(0xFFE6EBFA), Color(0xFFDDE4F6)
    )
    drawRect(
        brush = Brush.verticalGradient(if (dark) base else light, startY = 0f, endY = h)
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x398B6CFF),
                Color(0x008B6CFF)
            ),
            center = Offset(w * 0.12f, h * 0.10f),
            radius = w * 0.9f
        )
    )
    drawRect(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x2E4C8DFF),
                Color(0x004C8DFF)
            ),
            center = Offset(w * 0.92f, h * 0.88f),
            radius = w * 0.85f
        )
    )
}
