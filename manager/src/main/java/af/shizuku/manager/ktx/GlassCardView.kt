package af.shizuku.manager.ktx

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.compose.GlassCardOverlay
import af.shizuku.manager.home.compose.LiquidGlassBackdropHolder
import kotlin.math.max

/**
 * Applies the same live Backdrop lens used by home cards to an XML MaterialCardView.
 *
 * The terminal guide is an XML layout hosted inside the Compose pager. It previously kept its
 * Material surface color while every neighboring page was glass, so its cards read as white
 * plastic blocks on top of the wallpaper. This helper installs the shared GlassCardOverlay at
 * child index 0; the real XML title/body/buttons remain above it and keep receiving touches.
 */
fun MaterialCardView.applyGlassCardSurface(fillColor: Int? = null) {
    if (!ShizukuSettings.isGlassUi() || LiquidGlassBackdropHolder.background == null) return

    val density = resources.displayMetrics.density
    val radiusDp = if (radius > 0f) radius / density else 28f
    setCardBackgroundColor(fillColor ?: context.cardSurfaceColor())
    strokeWidth = max(1, density.toInt())
    strokeColor = context.glassStrokeColor()
    cardElevation = 0f

    if (getChildAt(0) is ComposeView) return
    val overlay = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setContent { GlassCardOverlay(radiusDp) }
    }
    addView(
        overlay,
        0,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            // MaterialCardView lays children inside its content padding. Pull the glass layer back
            // out to the card silhouette so its highlight does not draw a second inset rim.
            leftMargin = -contentPaddingLeft
            topMargin = -contentPaddingTop
            rightMargin = -contentPaddingRight
            bottomMargin = -contentPaddingBottom
        }
    )
}

/**
 * A filled Material tonal button is opaque by definition, so it reads as a plastic pill when it
 * sits on one of the glass cards. Keep the same touch target and ripple but swap the container
 * for the card's low-alpha white fill plus the shared hairline; classic mode is left untouched.
 */
fun MaterialButton.applyGlassButtonSurface() {
    if (!ShizukuSettings.isGlassUi() || LiquidGlassBackdropHolder.background == null) return

    val density = resources.displayMetrics.density
    val fill = context.cardSurfaceColor()
    val enabledState = intArrayOf(android.R.attr.state_enabled)
    val disabledState = intArrayOf(-android.R.attr.state_enabled)
    backgroundTintList = ColorStateList(
        arrayOf(enabledState, disabledState),
        intArrayOf(fill, ColorUtils.setAlphaComponent(fill, 0x0D))
    )
    strokeColor = ColorStateList.valueOf(context.glassStrokeColor())
    strokeWidth = max(1, density.toInt())
    cornerRadius = (24 * density).toInt()
    elevation = 0f

    val onSurface = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorOnSurface,
        0
    )
    setTextColor(
        ColorStateList(
            arrayOf(enabledState, disabledState),
            intArrayOf(onSurface, ColorUtils.setAlphaComponent(onSurface, 0x66))
        )
    )
    rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 0x1F))
}
