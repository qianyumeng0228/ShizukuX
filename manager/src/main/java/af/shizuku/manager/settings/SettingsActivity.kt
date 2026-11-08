package af.shizuku.manager.settings

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import af.shizuku.manager.R
import af.shizuku.manager.settings.compose.SettingsScreen
import af.shizuku.core.ui.AppActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    // Title, search results and the inner-page flag live in the controller so the single-container
    // shell can host the same screen; this activity is now only the standalone host.
    private val ctl by lazy { SettingsController(this) }
    var themeVersion by mutableStateOf(0)

    fun onThemeChanged() {
        themeVersion++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Indexing ~9 preference XMLs synchronously on the main thread caused jank on first
        // settings open. Prewarm on IO; [SettingsSearchEngine.search] blocks briefly on the
        // shared lock if a query arrives before prewarm completes.
        lifecycleScope.launch(Dispatchers.IO) {
            SettingsSearchEngine.init(applicationContext)
        }

        ctl.observeBackStack()

        setContent {
            val tv = themeVersion
            af.shizuku.core.ui.compose.AppTheme(
                isBlackNightTheme = af.shizuku.manager.app.ThemeHelper.isBlackNightTheme(this),
                isOneUi = af.shizuku.manager.ShizukuSettings.isOneUiThemeEnabled(),
                themeVersion = tv
            ) {
                SettingsScreen(
                    title = ctl.currentTitle,
                    // Settings opened from the tab bar is a sibling screen: no up arrow until a
                    // preference sub-page actually pushes onto the back stack.
                    showBack = ctl.hasInnerPage || !af.shizuku.manager.utils.GlassTabs.launchedFromTab(this),
                    onSelectTab = { af.shizuku.manager.utils.GlassTabs.open(this, it) },
                    onNavigateUp = {
                        if (!onSupportNavigateUp()) {
                            finish()
                        }
                    },
                    onNavigateToSetting = { item -> ctl.navigateToSetting(item) },
                    searchResults = ctl.searchResults,
                    onSearchQueryChanged = { query -> ctl.search(query) },
                    onContainerCreated = { ctl.attachContainerIfNeeded() }
                )
            }
        }

        // Settings has no app bar of its own, so without this a tab switch snapped instead of
        // sliding sideways like the rest of the destinations.
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.glass_tab_in_right, R.anim.glass_tab_out_left)
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragmentName = pref.fragment ?: return false
        ctl.openFragment(fragmentName, pref.extras, pref.title?.toString())
        return true
    }

    fun updateTitle(title: String) {
        ctl.updateTitle(title)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (ctl.popInnerPage()) return true
        return super.onSupportNavigateUp()
    }
}
