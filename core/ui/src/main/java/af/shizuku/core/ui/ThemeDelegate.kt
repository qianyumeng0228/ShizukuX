package af.shizuku.core.ui

import android.content.Context
import android.content.res.Resources.Theme

interface ThemeDelegate {
    fun getThemeKey(context: Context): String
    fun isUsingSystemColor(): Boolean
    fun onApplyUserThemeResource(context: Context, theme: Theme, isDecorView: Boolean)
}

object ThemeDelegateManager {
    private var delegate: ThemeDelegate? = null

    fun setDelegate(delegate: ThemeDelegate) {
        this.delegate = delegate
    }

    fun getDelegate(): ThemeDelegate {
        return delegate ?: throw IllegalStateException("ThemeDelegate not initialized")
    }
}
