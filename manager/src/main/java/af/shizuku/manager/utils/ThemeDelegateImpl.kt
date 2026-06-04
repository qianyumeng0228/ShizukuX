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
        return ThemeHelper.getTheme(context) + ThemeHelper.isUsingSystemColor() + customAccent
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

        theme.applyStyle(ThemeHelper.getThemeStyleRes(context), true)
    }
}
