package moe.shizuku.manager.app

import android.content.res.Resources
import android.content.res.Resources.Theme
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Window
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import moe.shizuku.manager.R
import rikka.core.res.isNight
import rikka.core.res.resolveColor
import rikka.material.app.MaterialActivity
import com.google.android.material.transition.platform.MaterialSharedAxis

abstract class AppActivity : MaterialActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // Enable window transitions
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)
        
        // Apply M3E 2026 MaterialSharedAxis transition to the window
        val enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        val exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        val returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        val reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
        
        // Increase duration for smoother motion
        enterTransition.duration = 450
        exitTransition.duration = 450
        returnTransition.duration = 450
        reenterTransition.duration = 450

        window.enterTransition = enterTransition
        window.exitTransition = exitTransition
        window.returnTransition = returnTransition
        window.reenterTransition = reenterTransition

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
