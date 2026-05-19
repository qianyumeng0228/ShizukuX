package af.shizuku.manager.utils

import android.content.Context
import android.content.res.Resources.Theme
import af.shizuku.core.ui.ThemeDelegate
import af.shizuku.manager.app.ThemeHelper
import rikka.core.res.isNight
import af.shizuku.manager.R

class ThemeDelegateImpl : ThemeDelegate {
    override fun getThemeKey(context: Context): String {
        return ThemeHelper.getTheme(context) + ThemeHelper.isUsingSystemColor()
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
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(context), true)
    }
}
