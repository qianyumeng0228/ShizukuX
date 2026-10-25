package af.shizuku.manager.home

import android.os.Bundle
import af.shizuku.core.ui.AppActivity
import af.shizuku.core.ui.compose.AppTheme

import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.home.compose.SystemHubScreen

class SystemHubActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // Optional deep-link to a specific hub page (e.g. the home "activity log" chip opens
        // straight on the log tab). Default 0 = metrics, which keeps the diagnostics-chip
        // entry behavior unchanged.
        val initialPage = intent?.getIntExtra(EXTRA_INITIAL_PAGE, PAGE_METRICS) ?: PAGE_METRICS
        setContent {
            AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled()
            ) {
                SystemHubScreen(
                    onBackClick = { finish() },
                    initialPage = initialPage
                )
            }
        }
    }

    companion object {
        const val EXTRA_INITIAL_PAGE = "af.shizuku.manager.home.EXTRA_INITIAL_PAGE"
        const val PAGE_METRICS = 0
        const val PAGE_ACTIVITY_LOG = 1
    }
}
