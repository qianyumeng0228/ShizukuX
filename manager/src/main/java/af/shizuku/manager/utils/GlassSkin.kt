package af.shizuku.manager.utils

import android.app.Activity
import android.content.res.Configuration
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

/**
 * Liquid-glass skin for activities that do not extend [af.shizuku.core.ui.AppActivity] /
 * AppBarActivity, i.e. the plain AppCompat ones (`DeviceControlActivity`, `StealthModeActivity`,
 * `ExternalStartActivity`, `TileOptionsActivity`). Those never reach ThemeDelegateImpl, so they
 * kept the stock opaque window and read as black sheets next to the gradient pages.
 *
 * Call it in onCreate after super.onCreate() and before any setContentView(): the colour attrs
 * have to be in place before the hierarchy inflates, and the window background has to exist for
 * the gradient to show through.
 */
object GlassSkin {

    fun applyIfEnabled(activity: Activity) {
        if (!ShizukuSettings.isGlassUi()) return
        val night = (activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        try {
            activity.theme.applyStyle(
                if (night) R.style.ThemeOverlay_Glass else R.style.ThemeOverlay_Glass_Light, true
            )
        } catch (e: Exception) {
            // A page whose theme refuses the overlay is no reason to crash; it just keeps its
            // stock colours, which is exactly the pre-glass behaviour.
            return
        }

        // The window background is resolved when the DecorView is created, i.e. before this call,
        // so the overlay's android:windowBackground never reaches it - set the stage by hand.
        val bgRes = if (night) R.drawable.glass_window_background else R.drawable.glass_window_background_light
        runCatching {
            activity.window.setBackgroundDrawable(
                activity.resources.getDrawable(bgRes, activity.theme)
            )
        }
    }
}
