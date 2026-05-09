package af.shizuku.manager.app

import android.content.res.Resources.Theme
import android.os.Bundle
import android.view.Window
import androidx.activity.enableEdgeToEdge
import af.shizuku.manager.R
import rikka.core.res.isNight
import rikka.material.app.MaterialActivity

abstract class AppActivity : MaterialActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
            // Enable Activity enter/exit transitions
            window.enterTransition = android.transition.Explode()
            window.exitTransition = android.transition.Explode()
        } catch (_: Exception) {
            // Feature may already be enabled by the theme or base class
        }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    override fun computeUserThemeKey(): String {
        return ThemeHelper.getTheme(this) + ThemeHelper.isUsingSystemColor()
    }

    override fun onApplyUserThemeResource(theme: Theme, isDecorView: Boolean) {
        if (ThemeHelper.isUsingSystemColor()) {
            if (resources.configuration.isNight())
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Dark, true)
            else
                theme.applyStyle(R.style.ThemeOverlay_DynamicColors_Light, true)
        }

        theme.applyStyle(ThemeHelper.getThemeStyleRes(this), true)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) {
            finish()
        }
        return true
    }
} 
