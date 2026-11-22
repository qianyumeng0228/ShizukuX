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
            ShizukuSettings.isOneUiThemeEnabled() + ShizukuSettings.isOneHandedModeEnabled() +
            ShizukuSettings.getWallpaperTheme() +
            ShizukuSettings.isBoldTypographyEnabled() + ShizukuSettings.isExpandedHeadersEnabled() +
            ShizukuSettings.getUiStyle()
    }

    override fun isUsingSystemColor(): Boolean {
        return ThemeHelper.isUsingSystemColor()
    }

    override fun onApplyUserThemeResource(context: Context, theme: Theme, isDecorView: Boolean) {
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

        // Bold typography overlay applied before One UI so One UI's values win when both are on.
        if (ShizukuSettings.isBoldTypographyEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_Typography_Bold, true)
        }

        // One UI theme overlay is structure-only (shapes/typography, see themes_overlay.xml) -
        // no color attrs, so it composes with whichever color source (dynamic/custom accent/
        // default) was applied above instead of competing with it, and needs no day/night variant.
        if (ShizukuSettings.isOneUiThemeEnabled()) {
            theme.applyStyle(R.style.ThemeOverlay_OneUI, true)
        }

        // Liquid-glass skin: applied after One UI/bold so its accent + corner scale wins.
        if (ShizukuSettings.isGlassUi()) {
            if (context.resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_Glass, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_Glass_Light, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(context), true)
    }
}
