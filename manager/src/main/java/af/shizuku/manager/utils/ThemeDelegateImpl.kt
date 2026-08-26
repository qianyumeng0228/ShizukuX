package af.shizuku.manager.utils

import android.content.Context
import android.content.res.Resources.Theme
import af.shizuku.core.ui.ThemeDelegate
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.ShizukuSettings
import rikka.core.res.isNight
import af.shizuku.manager.R

class ThemeDelegateImpl : ThemeDelegate {
    override fun getThemeKey(context: Context): String {
        val customAccent = ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
        return ThemeHelper.getTheme(context) + ThemeHelper.isUsingSystemColor() + customAccent +
            ShizukuSettings.isExpressiveShapesEnabled() + ShizukuSettings.getShapeStyle() +
            ShizukuSettings.getIconStyle() + ShizukuSettings.getIconColorMode() +
            ShizukuSettings.isOneUiThemeEnabled() + ShizukuSettings.isOneHandedModeEnabled()
    }

    override fun isUsingSystemColor(): Boolean {
        return ThemeHelper.isUsingSystemColor()
    }

    override fun onApplyUserThemeResource(context: Context, theme: Theme, isDecorView: Boolean) {
        // One UI theme overlay is applied first (before dynamic color/custom accent below) so
        // Monet dynamic color still wins when both are enabled at once - applyStyle overrides
        // are last-wins, so this must run before, not after, the dynamic-color block. The dark
        // variant uses a lighter blue to maintain WCAG contrast on dark surfaces.
        if (ShizukuSettings.isOneUiThemeEnabled()) {
            val isNight = context.resources.configuration.isNight()
            val oneUiStyle = if (isNight) R.style.ThemeOverlay_OneUI_Dark else R.style.ThemeOverlay_OneUI
            theme.applyStyle(oneUiStyle, true)
        }

        if (ThemeHelper.isUsingSystemColor()) {
            if (context.resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        } else {
            val customAccent = ShizukuSettings.getPreferences().getString("custom_accent", "DEFAULT")
            if (customAccent != "DEFAULT") {
                val isNight = context.resources.configuration.isNight()
                val styleRes = when (customAccent) {
                    "VIOLET" -> if (isNight) R.style.ThemeOverlay_Accent_Violet_Dark else R.style.ThemeOverlay_Accent_Violet
                    "GREEN" -> if (isNight) R.style.ThemeOverlay_Accent_Green_Dark else R.style.ThemeOverlay_Accent_Green
                    "CRIMSON" -> if (isNight) R.style.ThemeOverlay_Accent_Crimson_Dark else R.style.ThemeOverlay_Accent_Crimson
                    "OCEAN" -> if (isNight) R.style.ThemeOverlay_Accent_Ocean_Dark else R.style.ThemeOverlay_Accent_Ocean
                    else -> 0
                }
                if (styleRes != 0) {
                    theme.applyStyle(styleRes, true)
                }
            }
        }

        if (!ShizukuSettings.isExpressiveShapesEnabled()) {
            // shape_style's preference entry is UI-disabled while expressive_shapes is off, so
            // it doesn't apply here regardless of its stored value - flatten to plain Material3.
            theme.applyStyle(R.style.ThemeOverlay_Shapes_Standard, true)
        } else {
            val shapeStyleRes = when (ShizukuSettings.getShapeStyle()) {
                "modern" -> R.style.ThemeOverlay_Shape_Modern
                "classic" -> R.style.ThemeOverlay_Shape_Classic
                "squircle" -> R.style.ThemeOverlay_Shape_Squircle
                else -> 0 // "zen" (default): keep the base Material3Expressive corner scale
            }
            if (shapeStyleRes != 0) {
                theme.applyStyle(shapeStyleRes, true)
            }
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(context), true)
    }
}
