package af.shizuku.manager.utils

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.widget.ImageView
import androidx.preference.PreferenceGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.color.utilities.Hct
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.themeColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object IconStyleHelper {

    enum class Style(val key: String) {
        STANDARD("standard"),
        OUTLINED("outlined"),
        TWO_TONE("twotone");

        companion object {
            fun fromKey(key: String?): Style = values().firstOrNull { it.key == key } ?: STANDARD
        }
    }

    enum class ColorMode(val key: String) {
        NONE("none"),
        UNIFORM("uniform"),
        PER_ICON("per_icon");

        companion object {
            fun fromKey(key: String?): ColorMode = values().firstOrNull { it.key == key } ?: UNIFORM
        }
    }

    // Evenly-spaced hue slots a per-icon color is picked from, keeping tone/chroma fixed to the
    // theme's own primary so varied hues still read as "this app's palette" rather than random.
    private const val HUE_SLOTS = 12

    fun current(): Style = Style.fromKey(ShizukuSettings.getIconStyle())
    fun currentColorMode(): ColorMode = ColorMode.fromKey(ShizukuSettings.getIconColorMode())

    fun applyToTree(
        context: Context,
        group: PreferenceGroup,
        style: Style = current(),
        colorMode: ColorMode = currentColorMode()
    ) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                applyToTree(context, pref, style, colorMode)
            }
            pref.icon?.let { original ->
                pref.icon = stylize(context, original, style, colorMode, seedKey = pref.key)
            }
        }
    }

    fun stylize(
        context: Context,
        original: Drawable,
        style: Style = current(),
        colorMode: ColorMode = currentColorMode(),
        seedKey: String? = null
    ): Drawable {
        val mutable = original.mutate()
        // Unify visual size across icons that ship with different internal viewBox padding
        // (24dp, 960dp, 108dp …). A small uniform inset makes them all read as the same weight.
        val inset = (1 * context.resources.displayMetrics.density).toInt()
        return when (style) {
            Style.STANDARD -> InsetDrawable(
                tinted(mutable, cardSafe(resolveColor(context, R.attr.colorPrimary), context, key = seedKey)), inset
            )
            Style.OUTLINED -> InsetDrawable(
                tinted(mutable, cardSafe(resolveColor(context, R.attr.colorOnSurfaceVariant), context, key = seedKey)), inset
            )
            Style.TWO_TONE -> {
                val (bgColor, fgColor) = twoToneColors(context, colorMode, seedKey)
                val bg = pillBackground(context, bgColor)
                val fg = tinted(mutable, cardSafe(fgColor, context, bgColor, seedKey))
                val padding = (4 * context.resources.displayMetrics.density).toInt()
                LayerDrawable(arrayOf(bg, InsetDrawable(fg, padding)))
            }
        }
    }

    // The 12dp CardIcon/CardIcon.Droplet style used, so a plain Standard/Outlined vector still
    // renders at its native ~24dp size centered in the 48dp Home card icon slot.
    private const val CARD_ICON_PADDING_DP = 12

    /**
     * Applies the current icon style/color-mode/shape to a Home-screen card's 48dp icon slot.
     * Those cards previously always used a fixed two-tone "droplet" pill via the CardIcon.Droplet
     * XML style, ignoring the Personalization icon settings entirely. This clears that hardcoded
     * background/tint so [stylize] fully controls the result, and swaps the ImageView's own
     * padding per style: Two-Tone's pill needs to fill the whole slot (stylize's own InsetDrawable
     * already provides the icon-to-pill breathing room), while Standard/Outlined need the
     * original 12dp padding so the bare vector isn't stretched to fill the whole 48dp box.
     *
     * [original] must be the icon's untouched, freshly-inflated drawable - callers should capture
     * it once (e.g. in a ViewHolder's init block) rather than reading it back from the ImageView,
     * since after the first call the ImageView holds the already-styled result.
     */
    fun applyToCardIcon(
        imageView: ImageView,
        original: Drawable,
        seedKey: String,
        style: Style = current(),
        colorMode: ColorMode = currentColorMode()
    ) {
        val context = imageView.context
        val inset = (1 * context.resources.displayMetrics.density).toInt()
        imageView.background = null
        imageView.imageTintList = null
        if (style == Style.TWO_TONE) {
            // Two-Tone paints its own pill behind the glyph, so the ImageView itself must stay
            // untinted - both layers carry their colour inside stylize().
            imageView.setPadding(0, 0, 0, 0)
            imageView.setImageDrawable(stylize(context, original, style, colorMode, seedKey))
            return
        }
        // Single-colour styles are tinted at the ImageView level instead of inside the drawable:
        // several of the bundled card vectors ignore Drawable#setTintList (their own fillColor wins
        // in some ROM vector pipelines), which rendered them pure white on the light glass cards.
        // ImageView tint always applies to whatever it draws.
        val padding = CARD_ICON_PADDING_DP * context.resources.displayMetrics.density
        imageView.setPadding(padding.toInt(), padding.toInt(), padding.toInt(), padding.toInt())
        imageView.setImageDrawable(InsetDrawable(original.mutate(), inset))
        val tint = if (style == Style.STANDARD) {
            resolveColor(context, R.attr.colorPrimary)
        } else {
            resolveColor(context, R.attr.colorOnSurfaceVariant)
        }
        imageView.imageTintList = ColorStateList.valueOf(cardSafe(tint, context, key = seedKey))
    }

    /**
     * Resolves the Two-Tone background/foreground colors for the given mode.
     * - NONE: neutral surface tones, no hue - a plain monochrome pill.
     * - UNIFORM: the theme's own primary container pair, same for every icon (previous behavior).
     * - PER_ICON: a distinct hue per icon, derived from the theme's primary HCT tone/chroma so
     *   the varied palette still reads as "this app's colors" rather than arbitrary hues, then
     *   harmonized toward primary and run through Material's color-role derivation for correct
     *   contrast in both light and dark themes. Mirrors Material Color Utilities' recommended
     *   pattern (Hct hue-rotation + MaterialColors.harmonize/getColorRoles) rather than a
     *   hand-picked fixed palette - see IconStyleHelper's audit notes.
     */
    private fun twoToneColors(context: Context, colorMode: ColorMode, seedKey: String?): Pair<Int, Int> {
        return when (colorMode) {
            ColorMode.NONE ->
                resolveColor(context, R.attr.colorSurfaceVariant) to resolveColor(context, R.attr.colorOnSurfaceVariant)
            ColorMode.UNIFORM ->
                resolveColor(context, R.attr.colorPrimaryContainer) to resolveColor(context, R.attr.colorOnPrimaryContainer)
            ColorMode.PER_ICON -> {
                val primary = resolveColor(context, R.attr.colorPrimary)
                val slot = if (seedKey.isNullOrEmpty()) 0 else abs(seedKey.hashCode()) % HUE_SLOTS
                val baseHct = Hct.fromInt(primary)
                val hue = (baseHct.hue + slot * (360.0 / HUE_SLOTS)) % 360.0
                val seedColor = Hct.from(hue, baseHct.chroma, baseHct.tone).toInt()
                val harmonized = MaterialColors.harmonize(seedColor, primary)
                val roles = MaterialColors.getColorRoles(context, harmonized)
                roles.accentContainer to roles.onAccentContainer
            }
        }
    }

    /**
     * The Two-Tone pill's background shape, following the same "app personality" dial as
     * container corners (see ThemeDelegateImpl's shape_style overlays) instead of always being
     * a plain circle - "zen" keeps an asymmetric leaf/droplet look, others use progressively
     * more/less rounded rectangles. Falls back to a plain circle when expressive shapes are off.
     *
     * Public so status-driven icons (e.g. ServerStatusViewHolder, StartStockShizukuViewHolder)
     * that must keep their own semantic ok/error color logic instead of the user's color-mode
     * setting can still share the same shape, rather than reaching for the static
     * shape_droplet_background drawable directly and drifting out of sync with shape_style.
     */
    fun pillBackground(context: Context, color: Int): Drawable {
        if (!ShizukuSettings.isExpressiveShapesEnabled()) {
            return GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        return when (ShizukuSettings.getShapeStyle()) {
            "zen" -> leafDrawable(context, color)
            "classic" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 2 * context.resources.displayMetrics.density
                setColor(color)
            }
            "squircle" -> GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * context.resources.displayMetrics.density
                setColor(color)
            }
            "cut" -> cutDrawable(context, color)
            else -> GradientDrawable().apply { // "modern"
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * context.resources.displayMetrics.density
                setColor(color)
            }
        }
    }

    /**
     * The Cut style's icon pill: an octagon (all four rectangle corners cut at 45°), matching the
     * shape_style overlay's CUT corner family (see themes_overlay.xml) instead of ROUNDED - keeps
     * the same "distinct silhouette per style" treatment leafDrawable() gives "zen" rather than
     * falling back to a rounded rectangle indistinguishable from the other styles.
     * GradientDrawable has no native cut-corner support (only rounded), so this draws the
     * octagon path directly via a small custom Drawable instead.
     */
    private fun cutDrawable(context: Context, color: Int): Drawable {
        val cutFraction = 0.28f // corner cut as a fraction of the shorter side
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            this.color = color
        }
        return object : Drawable() {
            private val path = android.graphics.Path()

            override fun onBoundsChange(bounds: android.graphics.Rect) {
                super.onBoundsChange(bounds)
                val w = bounds.width().toFloat()
                val h = bounds.height().toFloat()
                val cut = min(w, h) * cutFraction
                path.reset()
                path.moveTo(cut, 0f)
                path.lineTo(w - cut, 0f)
                path.lineTo(w, cut)
                path.lineTo(w, h - cut)
                path.lineTo(w - cut, h)
                path.lineTo(cut, h)
                path.lineTo(0f, h - cut)
                path.lineTo(0f, cut)
                path.close()
            }

            override fun draw(canvas: android.graphics.Canvas) {
                canvas.drawPath(path, paint)
            }

            override fun setAlpha(alpha: Int) {
                paint.alpha = alpha
            }

            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                paint.colorFilter = colorFilter
            }

            @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /**
     * An asymmetric "leaf/droplet" pill: three tight corners, one wide corner.
     * cornerRadii values are in pixels (unlike cornerRadius's dp-friendly single value used
     * elsewhere in this file's other branches), hence the explicit density multiplication here.
     */
    private fun leafDrawable(context: Context, color: Int): Drawable {
        val density = context.resources.displayMetrics.density
        val tight = 4f * density
        val wide = 20f * density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadii = floatArrayOf(
                tight, tight, // top-left
                wide, wide,   // top-right
                tight, tight, // bottom-right
                tight, tight  // bottom-left
            )
            setColor(color)
        }
    }

    /**
     * Applies a semantic status pill (server ok/error, ADB-permission-limited warning,
     * incompatible-server warning) — these always render as a Two-Tone-style pill regardless of
     * the user's icon Style setting, since muting an error/warning to Standard/Outlined would lose
     * the color signal. Still routes the shape through [pillBackground] so it follows shape_style,
     * and clears the ImageView's static 12dp `CardIcon.Droplet` XML padding to 0 - without this,
     * these pills render visibly smaller/off-center than every Style-driven card's Two-Tone pill,
     * which gets 0 padding via [applyToCardIcon].
     */
    fun applyToStatusCardIcon(imageView: ImageView, pillColor: Int, tintColor: Int) {
        val context = imageView.context
        // CardIcon.Droplet leaves a backgroundTint (?colorTertiaryContainer) that would re-tint our
        // newly-assigned pill with a mismatched color (e.g. a blue pill on a pink error card).
        // Clear it so the pill actually renders in pillColor and blends with the card.
        imageView.backgroundTintList = null
        imageView.background = pillBackground(context, pillColor)
        imageView.imageTintList = ColorStateList.valueOf(tintColor)
        imageView.setPadding(0, 0, 0, 0)
    }

    private fun tinted(drawable: Drawable, color: Int): Drawable {
        drawable.setTintList(ColorStateList.valueOf(color))
        return drawable
    }

    private fun isNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Tone a card icon is drawn on - only used for the contrast guarantee in [cardSafe]. */
    private fun cardBackdrop(context: Context): Int = if (ShizukuSettings.isGlassUi()) {
        if (isNight(context)) 0xFF141A2B.toInt() else 0xFFF7F6FC.toInt()
    } else {
        resolveColor(context, R.attr.colorSurface)
    }

    /** WCAG relative luminance, 0..1. */
    private fun luminance(color: Int): Double {
        fun channel(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.0396) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /**
     * Guarantees the glyph is actually visible on its card. Under the glass skin the card fill
     * became translucent white in light mode, and any colour resolution that landed on a near
     * white tone (an onSurfaceVariant / container inherited from the dark base theme) rendered
     * white-on-white icons. Returns [tint] untouched whenever it already clears 3:1 against the
     * backdrop; otherwise falls back to the skin's own secondary text tone.
     */
    private fun cardSafe(tint: Int, context: Context, bg: Int = 0, key: String? = null): Int {
        val backdrop = if (bg != 0) bg else cardBackdrop(context)
        if (contrast(tint, backdrop) >= 3.0) return tint
        return if (isNight(context)) 0xFFA9B4C6.toInt() else 0xFF54505F.toInt()
    }

    private fun resolveColor(context: Context, attr: Int): Int = context.themeColor(attr)
}
