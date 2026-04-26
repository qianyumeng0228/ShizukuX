package af.shizuku.manager.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import androidx.preference.PreferenceGroup
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

object IconStyleHelper {

    enum class Style(val key: String) {
        STANDARD("standard"),
        OUTLINED("outlined"),
        TWO_TONE("twotone");

        companion object {
            fun fromKey(key: String?): Style = values().firstOrNull { it.key == key } ?: STANDARD
        }
    }

    fun current(): Style = Style.fromKey(ShizukuSettings.getIconStyle())

    fun applyToTree(context: Context, group: PreferenceGroup, style: Style = current()) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                applyToTree(context, pref, style)
            }
            pref.icon?.let { original ->
                pref.icon = stylize(context, original, style)
            }
        }
    }

    fun stylize(context: Context, original: Drawable, style: Style = current()): Drawable {
        val mutable = original.mutate()
        return when (style) {
            Style.STANDARD -> tinted(mutable, resolveColor(context, R.attr.colorPrimary))
            Style.OUTLINED -> tinted(mutable, resolveColor(context, R.attr.colorOnSurfaceVariant))
            Style.TWO_TONE -> {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(resolveColor(context, R.attr.colorPrimaryContainer))
                }
                val fg = tinted(mutable, resolveColor(context, R.attr.colorOnPrimaryContainer))
                val padding = (4 * context.resources.displayMetrics.density).toInt()
                LayerDrawable(arrayOf(bg, InsetDrawable(fg, padding)))
            }
        }
    }

    private fun tinted(drawable: Drawable, color: Int): Drawable {
        drawable.setTintList(ColorStateList.valueOf(color))
        return drawable
    }

    private fun resolveColor(context: Context, attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }
}
