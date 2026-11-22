package af.shizuku.manager.home.compose

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.compose.kyant.InteractiveHighlight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.flow.collect

/**
 * Official Kyant0/AndroidLiquidGlass 1.0.2 values, not the first-pass invented defaults.
 *
 * Catalog source at tag 1.0.2:
 *  LiquidButton   -> vibrancy + blur(2dp) + lens(12dp, 24dp), white surface supplied by caller
 *  LiquidBottomTabs -> vibrancy + blur(8dp) + lens(24dp, 24dp), container white at alpha .4
 *  LiquidToggle   -> blur(8dp) at rest, lens(5dp*progress, 10dp*progress, chromatic)
 */
object LiquidGlassDefaults {
    /** LiquidButton refraction, measured in dp. */
    const val BUTTON_REFRACTION_HEIGHT_DP = 12f
    const val BUTTON_REFRACTION_AMOUNT_DP = 24f
    const val BUTTON_BLUR_DP = 2f

    /** Card surfaces use the same official button lens so their edges visibly bend the photo. */
    const val CARD_REFRACTION_HEIGHT_DP = BUTTON_REFRACTION_HEIGHT_DP
    const val CARD_REFRACTION_AMOUNT_DP = BUTTON_REFRACTION_AMOUNT_DP
    const val BLUR_DP = BUTTON_BLUR_DP

    /** LiquidBottomTabs main tray: 24dp lens in both axes, 8dp blur. */
    const val TABS_REFRACTION_HEIGHT_DP = 24f
    const val TABS_REFRACTION_AMOUNT_DP = 24f
    const val TABS_BLUR_DP = 8f

    /** Generic lens fallback kept for compatibility with the tuner. */
    const val REFRACTION_HEIGHT_DP = TABS_REFRACTION_HEIGHT_DP
    const val REFRACTION_AMOUNT_DP = TABS_REFRACTION_AMOUNT_DP

    /** Official buttons grow instead of squash when held. */
    const val PRESSED_SCALE_X = 1.06f
    const val PRESSED_SCALE_Y = 1.06f
    const val PRESS_RELEASE_DAMPING = 0.5f
    const val PRESS_RELEASE_STIFFNESS = 300f

    /** Older aliases retained for other glass pages. */
    const val SPRING_DAMPING = PRESS_RELEASE_DAMPING
    const val SPRING_STIFFNESS = PRESS_RELEASE_STIFFNESS
}

/**
 * Background-only backdrop. Surfaces inside the pager sample this layer, never the layer that
 * contains themselves; the navigation glass uses a separate combined background + page backdrop.
 */
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/**
 * Bridge for XML MaterialCardView items hosted inside the glass pager. They cannot read a
 * composition local, but they can mount a tiny ComposeView overlay that samples the same live
 * background recorder as the Compose surfaces.
 */
object LiquidGlassBackdropHolder {
    @Volatile
    var background: Backdrop? = null

    // Home cards mount one ComposeView each. Re-sampling and blurring the same static
    // background for every visible card is the dominant cost while the native list scrolls:
    // on the 120Hz device a card with the live backdrop measured ~15ms per frame, while the
    // classic list stayed at ~6ms. Keep the exact live glass at rest, but skip its draw while
    // the list is being dragged/flung; the XML tint + hairline still provide the glass pane.
    private val scrollingState = androidx.compose.runtime.mutableStateOf(false)
    val isScrolling: Boolean get() = scrollingState.value

    fun setScrolling(scrolling: Boolean) {
        scrollingState.value = scrolling
    }
}

/**
 * Background-only glass overlay for a MaterialCardView. It is installed at child index 0 so the
 * real XML text/buttons stay above the glass, and it draws no shadow of its own because the card
 * already owns the silhouette.
 */
@Composable
fun GlassCardOverlay(radiusDp: Float) {
    val backdrop = LiquidGlassBackdropHolder.background ?: return
    // The static MaterialCardView tint and 1dp hairline remain visible; only the live
    // blur/refraction is suspended for the duration of a drag/fling. It returns on idle.
    if (LiquidGlassBackdropHolder.isScrolling) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(radiusDp.dp) },
                effects = {
                    vibrancy()
                    blur(LiquidGlassDefaults.BLUR_DP.dp.toPx())
                    lens(
                        refractionHeight = LiquidGlassDefaults.CARD_REFRACTION_HEIGHT_DP.dp.toPx(),
                        refractionAmount = LiquidGlassDefaults.CARD_REFRACTION_AMOUNT_DP.dp.toPx()
                    )
                },
                highlight = { Highlight.Default },
                shadow = { null },
                onDrawSurface = {
                    // The XML MaterialCardView already carries the shared 10%/6% pane tint.
                    // Drawing another pane here doubled the fill and made home/terminal cards read
                    // denser than Compose cards. Only the live backdrop/highlight belongs here.
                }
            )
    )
}

/** Spring-driven press that grows the glass; the reference does not squash it vertically. */
@Composable
fun Modifier.liquidPress(
    interactionSource: MutableInteractionSource,
    pressedScaleX: Float = LiquidGlassDefaults.PRESSED_SCALE_X,
    pressedScaleY: Float = LiquidGlassDefaults.PRESSED_SCALE_Y
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val animationsEnabled = ShizukuSettings.isExpressiveAnimationsEnabled()
    val releaseSpec: AnimationSpec<Float> = spring(
        dampingRatio = LiquidGlassDefaults.PRESS_RELEASE_DAMPING,
        stiffness = LiquidGlassDefaults.PRESS_RELEASE_STIFFNESS
    )
    val scaleX by animateFloatAsState(
        targetValue = if (pressed) pressedScaleX else 1f,
        animationSpec = when {
            !animationsEnabled -> snap()
            pressed -> spring(dampingRatio = 1f, stiffness = 1000f)
            else -> releaseSpec
        },
        label = "liquidPressX"
    )
    val scaleY by animateFloatAsState(
        targetValue = if (pressed) pressedScaleY else 1f,
        animationSpec = when {
            !animationsEnabled -> snap()
            pressed -> spring(dampingRatio = 1f, stiffness = 1000f)
            else -> releaseSpec
        },
        label = "liquidPressY"
    )
    return graphicsLayer {
        this.scaleX = scaleX
        this.scaleY = scaleY
        transformOrigin = TransformOrigin.Center
    }
}

/**
 * Real liquid glass on API 33+: live sampled backdrop, official button blur/lens values, the
 * official fine Highlight.Default rim and a pressed radial highlight. Card surfaces deliberately
 * have no drop shadow so they match the XML card overlays used by home and terminal. There is
 * deliberately no 20% white pane: the photo must remain legible through the glass.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalLiquidGlassBackdrop.current
    val dark = isSystemInDarkTheme()
    val interactionSource = remember { MutableInteractionSource() }
    val pressModifier = if (onClick != null) Modifier.liquidPress(interactionSource) else Modifier
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { InteractiveHighlight(animationScope) }
    val clickModifier = if (onClick != null) {
        LaunchedEffect(interactionSource, highlight) {
            interactionSource.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> highlight.press()
                    is PressInteraction.Release, is PressInteraction.Cancel -> highlight.release()
                }
            }
        }
        Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(highlight.modifier)
    } else {
        Modifier
    }
    val surfaceModifier = if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        modifier
            .then(pressModifier)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(LiquidGlassDefaults.BLUR_DP.dp.toPx())
                    lens(
                        refractionHeight = LiquidGlassDefaults.CARD_REFRACTION_HEIGHT_DP.dp.toPx(),
                        refractionAmount = LiquidGlassDefaults.CARD_REFRACTION_AMOUNT_DP.dp.toPx()
                    )
                },
                highlight = { Highlight.Default },
                shadow = { null },
                onDrawSurface = {
                    // Low-alpha surface only. 0.24+ was the reason the first pass read as a white
                    // plastic card and hid the lens. Every card family (home, terminal, modules,
                    // apps, native settings rows) now targets the same 10% light / 6% dark pane.
                    drawRect(Color.White.copy(alpha = if (dark) 0.06f else 0.10f))
                }
            )
            .then(clickModifier)
    } else {
        val fallback = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = if (dark) 0.08f else 0.14f),
                Color.White.copy(alpha = if (dark) 0.04f else 0.08f)
            )
        )
        modifier
            .then(pressModifier)
            .clip(shape)
            .background(fallback)
            .border(1.dp, Color.White.copy(alpha = if (dark) 0.18f else 0.34f), shape)
            .then(clickModifier)
    }
    Box(modifier = surfaceModifier, contentAlignment = contentAlignment, content = content)
}

/**
 * The moving selection lens. Exact 1.0.2 behavior: no blur or vibrancy at rest, lens grows with
 * press progress, chromatic aberration only while pressed, black 10% selection pane, and all
 * highlight/shadow/inner-shadow alphas tied to the same press progress.
 */
@Composable
fun LiquidGlassSelection(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    scaleX: () -> Float = { 1f },
    scaleY: () -> Float = { 1f },
    progress: () -> Float = { 0f }
) {
    val dark = isSystemInDarkTheme()
    Box(
        modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { ContinuousCapsule },
            effects = {
                val pressed = progress()
                lens(
                    refractionHeight = 10.dp.toPx() * pressed,
                    refractionAmount = 14.dp.toPx() * pressed,
                    chromaticAberration = true
                )
            },
            highlight = { Highlight.Default.copy(alpha = progress()) },
            shadow = { Shadow(alpha = progress()) },
            innerShadow = { InnerShadow(radius = 8.dp * progress(), alpha = progress()) },
            layerBlock = {
                this.scaleX = scaleX()
                this.scaleY = scaleY()
                transformOrigin = TransformOrigin.Center
            },
            onDrawSurface = {
                val pressed = progress()
                drawRect(
                    if (dark) Color.White.copy(alpha = 0.10f)
                    else Color.Black.copy(alpha = 0.10f),
                    alpha = 1f - pressed
                )
                drawRect(Color.Black.copy(alpha = 0.03f * pressed))
            }
        )
    )
}
