package af.shizuku.manager.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

/**
 * Shared page background used by both the home screen and the settings screen.
 *
 * Follows the user's wallpaper-theme setting:
 *  - white_miku: the bright Miku wallpaper (scrim adapts to system light/dark)
 *  - black_miku: the dark cyberpunk Miku wallpaper
 *  - original: no wallpaper — the stock animated gradient background
 *
 * A faint "breathing" gradient tint is layered on top of the wallpaper so the app keeps its
 * expressive feel even with a photographic background.
 */
@Composable
fun WallpaperBackground(content: @Composable () -> Unit) {
    val theme = ShizukuSettings.getWallpaperTheme()

    if (theme == ShizukuSettings.WALLPAPER_THEME_ORIGINAL) {
        OriginalGradientBackground(content)
        return
    }

    val wallpaperRes = if (theme == ShizukuSettings.WALLPAPER_THEME_BLACK_MIKU)
        R.drawable.wallpaper_bg_dark
    else
        R.drawable.wallpaper_bg_light

    // PERF: the breathing animation must NOT drive Compose recomposition of the whole
    // page (home RecyclerView / settings list) - an infiniteTransition read in the
    // composition body re-runs `content()` every frame, which made every screen scroll
    // stutter on low/mid-end devices. The colors below are therefore FIXED (mid-point
    // alpha) and the breath effect is applied purely as a graphicsLayer alpha update,
    // which only touches the layer properties and never recomposes content().
    val animationsEnabled = ShizukuSettings.isExpressiveAnimationsEnabled()
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (animationsEnabled) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(15000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val color1 = MaterialTheme.colorScheme.primary.copy(alpha = 0.045f)
    val color2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.045f)
    val color3 = MaterialTheme.colorScheme.secondary.copy(alpha = 0.025f)
    val color4 = MaterialTheme.colorScheme.primary.copy(alpha = 0.015f)

    val dark = isSystemInDarkTheme()
    val scrimColor = MaterialTheme.colorScheme.surface
    val scrim = Brush.verticalGradient(
        0f to scrimColor.copy(alpha = if (dark) 0.82f else 0.76f),
        0.4f to scrimColor.copy(alpha = if (dark) 0.66f else 0.52f),
        1f to scrimColor.copy(alpha = if (dark) 0.76f else 0.66f)
    )
    val breathe = Brush.sweepGradient(
        colors = listOf(color1, color2, color3, color4, color1),
        center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(wallpaperRes),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(modifier = Modifier.fillMaxSize().background(scrim))
        // graphicsLayer: reads `alpha` without recomposing the tree; only the layer alpha
        // is updated per frame. The tint itself stays static (mid-alpha colors above).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(breathe)
                .graphicsLayer { this.alpha = 0.82f + 0.18f * alpha }
        )
        content()
    }
}

/**
 * The stock (pre-wallpaper) animated gradient background, kept for the "original" wallpaper
 * option. Restores exactly the look the app had before the beautification.
 */
@Composable
fun OriginalGradientBackground(content: @Composable () -> Unit) {
    // PERF: keep the gradient look but drop the 15s infinite breathing animation.
    // The infiniteTransition re-rendered the full-screen sweepGradient every single frame
    // on RenderThread, which layered under a scrolling RecyclerView (with the collapsing
    // LargeTopAppBar's per-frame scroll-delta work) and read as stutter. Static gradient
    // at a mid alpha looks identical and costs nothing after the first frame.
    val color1 = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    val color2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
    val color3 = MaterialTheme.colorScheme.secondary.copy(alpha = 0.04f)
    val color4 = MaterialTheme.colorScheme.primary.copy(alpha = 0.02f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.sweepGradient(
                    colors = listOf(color1, color2, color3, color4, color1),
                    center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
                )
            )
    ) {
        content()
    }
}
