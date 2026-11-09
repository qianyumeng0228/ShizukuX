package af.shizuku.manager.home.compose

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import af.shizuku.manager.R
import java.io.ByteArrayOutputStream

/** Geometry and look of the bottom-bar lens, in px. */
data class LensGeometry(
    val radiusPx: Float,
    val halfSpinePx: Float,
    val edgePx: Float
)

data class LensLook(
    val refraction: Float = 0.65f,
    val dispersion: Float = 1.00f,
    val frost: Float = 0.55f,
    val bezel: Float = 0.45f,
    val saturation: Float = 1.15f,
    val specAmt: Float = 0.55f,
    val tint: Float = 0.06f,
    val opacity: Float = 1.00f
)

/**
 * Wraps the AGSL lens shader and caches the [RenderEffect].
 *
 * Kept in its own class on purpose: [RuntimeShader] is API 33 and resolving it during class load
 * on an older device crashes with NoClassDefFoundError before any guard can run. Nothing outside
 * this file references these types, and the caller only instantiates it above API 33.
 *
 * The cache is not an optimisation detail but a correctness one: the effect snapshots the uniform
 * values at creation time, so a stale effect silently renders the previous geometry (all-transparent
 * when it was built with a zero radius).
 */
@RequiresApi(33)
class LensShader(context: Context) {

    private val shader = RuntimeShader(readRaw(context))
    private var cached: RenderEffect? = null
    private var cachedKey: String = ""

    fun effect(
        geometry: LensGeometry,
        look: LensLook,
        dark: Boolean,
        centerX: Float,
        centerY: Float,
        specX: Float,
        specY: Float
    ): RenderEffect {
        val key = buildString {
            append(geometry.radiusPx).append('|').append(geometry.halfSpinePx).append('|')
            append(geometry.edgePx).append('|').append(look.refraction).append('|')
            append(look.dispersion).append('|').append(look.frost).append('|')
            append(look.bezel).append('|').append(look.saturation).append('|')
            append(look.specAmt).append('|').append(look.tint).append('|')
            append(look.opacity).append('|').append(dark).append('|')
            append(centerX).append('|').append(centerY).append('|')
            append(specX).append('|').append(specY)
        }
        cached?.let { if (key == cachedKey) return it }

        // Uniforms first, effect second: createRuntimeShaderEffect captures the current values.
        shader.setFloatUniform("uCenter", centerX, centerY)
        shader.setFloatUniform("uRadius", geometry.radiusPx)
        shader.setFloatUniform("uHalfSpine", geometry.halfSpinePx)
        shader.setFloatUniform("uRefraction", look.refraction)
        shader.setFloatUniform("uDispersion", look.dispersion)
        shader.setFloatUniform("uFrost", look.frost)
        shader.setFloatUniform("uEdge", geometry.edgePx)
        shader.setFloatUniform("uBezel", look.bezel)
        shader.setFloatUniform("uSat", look.saturation)
        shader.setFloatUniform("uSpec", specX, specY)
        shader.setFloatUniform("uSpecAmt", look.specAmt)
        shader.setFloatUniform("uTint", look.tint)
        shader.setFloatUniform("uOpacity", look.opacity)
        shader.setFloatUniform("uDark", if (dark) 1f else 0f)

        val effect = RenderEffect.createRuntimeShaderEffect(shader, "content")
        cached = effect
        cachedKey = key
        return effect
    }

    private fun readRaw(context: Context): String {
        context.resources.openRawResource(R.raw.liquid_lens).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8")
        }
    }
}
