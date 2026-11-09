package af.shizuku.manager.module

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.module.store.ModuleStoreController
import af.shizuku.manager.module.store.ModuleStoreScreen

/**
 * Online module store (catalog) for the ADB module page.
 *
 * Opened from the store icon in the modules app bar. Installation reuses
 * [AdbModuleManager.install], so a downloaded ZIP goes through exactly the same validation
 * as one picked from the system file picker.
 */
class ModuleStoreActivity : ComponentActivity() {

    private val ctl by lazy {
        ModuleStoreController(this, lifecycleScope) { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                ModuleStoreScreen(
                    state = ctl.state,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { ctl.refresh() },
                    onInstall = { entry -> ctl.install(entry) },
                    onDismissError = { ctl.dismissError() }
                )
            }
        }
        ctl.refresh()

        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.glass_tab_in_right, R.anim.glass_tab_out_left)
    }
}
