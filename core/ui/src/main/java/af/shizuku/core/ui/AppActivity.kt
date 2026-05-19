package af.shizuku.core.ui

import android.content.res.Resources.Theme
import android.os.Bundle
import android.view.Window
import androidx.activity.enableEdgeToEdge
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
            window.enterTransition = android.transition.Explode()
            window.exitTransition = android.transition.Explode()
        } catch (_: Exception) {
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun computeUserThemeKey(): String {
        return ThemeDelegateManager.getDelegate().getThemeKey(this)
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        ThemeDelegateManager.getDelegate().onApplyUserThemeResource(this, theme, isDecorView)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
}
