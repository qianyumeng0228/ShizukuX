package af.shizuku.manager.home.compose

import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Records the page behind the bar into a [GraphicsLayer] so the lens can re-sample it.
 *
 * The layer is recorded from the pager's own draw pass, so the page is still drawn exactly once:
 * this replaces the page content rather than adding a second copy of it.
 */
fun Modifier.glassBackdrop(layer: GraphicsLayer): Modifier = drawWithContent {
    val content = this
    layer.record { content.drawContent() }
    drawLayer(layer)
}

@Composable
fun rememberGlassBackdropLayer(): GraphicsLayer = rememberGraphicsLayer()

/**
 * A capsule of live refraction sitting on top of the bottom bar.
 *
 * The backdrop is translated so the page lands in the same place it was drawn, then clipped to the
 * lens box: painting it unclipped repaints the whole page over everything drawn afterwards, which
 * buries the bar underneath. Clipping does not shrink the refraction source - the shader samples
 * the recorded layer, not the canvas.
 *
 * No pointer input of any kind: the lens sits over the bar and must stay transparent to touches.
 *
 * Callers must only reach this above API 33 ([LensShader] resolves RuntimeShader at class load).
 */
@RequiresApi(33)
@Composable
fun GlassLens(
    backdrop: GraphicsLayer,
    centreInBackdropPx: Offset,
    sizeDp: DpSize,
    geometry: LensGeometry,
    look: LensLook,
    dark: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember { LensShader(context) }

    Box(
        modifier = modifier
            .size(sizeDp)
            // A cast shadow is what separates the glass from a flat white patch on a light page
            // (reference S2): the refraction alone is invisible when the backdrop is near-white.
            // Kept light and narrow - a full-strength drop shadow on a lavender background reads
            // as a grey ring rather than the glass being lifted off the page.
            .shadow(
                elevation = 6.dp,
                shape = CircleShape,
                clip = false,
                ambientColor = Color(0x0D000000),
                spotColor = Color(0x14000000)
            )
            .clipToBounds()
            // Order matters: the effect has to sit outside the drawing it refracts. Placed after
            // drawWithContent it wrapped an empty layer - the block never called drawContent(), so
            // the shader was applied to nothing and the backdrop reached the screen unfiltered.
            .graphicsLayer {
                renderEffect = renderer.effect(
                    geometry = geometry,
                    look = look,
                    dark = dark,
                    centerX = size.width / 2f,
                    centerY = size.height / 2f,
                    specX = 0f,
                    specY = -geometry.radiusPx * 0.42f
                ).asComposeRenderEffect()
            }
            .drawWithContent {
                val origin = centreInBackdropPx - Offset(size.width / 2f, size.height / 2f)
                translate(-origin.x, -origin.y) { drawLayer(backdrop) }
            }
    )
}
