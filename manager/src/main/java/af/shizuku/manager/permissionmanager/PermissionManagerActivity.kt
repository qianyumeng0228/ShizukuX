package af.shizuku.manager.permissionmanager

import android.os.Bundle
import af.shizuku.core.ui.AppActivity
import af.shizuku.core.ui.compose.AppTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme

class PermissionManagerActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled()
            ) {
                PermissionManagerScreen(onBackClick = { finish() })
            }
        }
    }
}
