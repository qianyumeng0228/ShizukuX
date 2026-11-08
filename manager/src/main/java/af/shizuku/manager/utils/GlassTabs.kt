package af.shizuku.manager.utils

import android.app.Activity
import android.content.Intent
import af.shizuku.core.ui.AppBarActivity.Companion.EXTRA_FROM_TAB
import af.shizuku.manager.MainActivity
import af.shizuku.manager.home.HomeActivity

/**
 * The five glass-skin destinations share one floating tab bar.
 *
 * They used to be five sibling activities, so every tab tap started a new activity: the content
 * was swapped instead of sliding, and the pill re-seated itself instead of following the finger.
 * They now all live in one container ([af.shizuku.manager.home.GlassShellActivity], reached through
 * HomeActivity), so opening a tab means opening that one container and asking its pager to land on
 * the requested page - and once inside, every further tap is a horizontal scroll, never an
 * activity launch.
 */
object GlassTabs {

    const val INDEX_HOME = 0
    const val INDEX_APPS = 1
    const val INDEX_MODULES = 2
    const val INDEX_TERMINAL = 3
    const val INDEX_SETTINGS = 4

    /** Which page the container should show first when a tab bar opens it. */
    const val EXTRA_START_PAGE = "start_page"

    fun open(activity: Activity, index: Int) {
        // The container draws one bar for all five pages; it never sends itself to another screen.
        if (activity is HomeActivity) return

        activity.startActivity(
            // MainActivity, not HomeActivity: it is the component the launcher (and therefore the
            // task root) uses, so CLEAR_TOP re-uses the container that is already running instead of
            // stacking a second instance of its parent class.
            Intent(activity, MainActivity::class.java)
                .putExtra(EXTRA_START_PAGE, index)
                // CLEAR_TOP + SINGLE_TOP: if the container is already in the task, re-use it and
                // let onNewIntent animate to the page instead of stacking a second copy.
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        // Leaving a standalone destination for the container is a tab switch, not a drill-down:
        // the old screen must not linger below the container in the back stack.
        activity.finish()
    }

    /**
     * True when this screen was opened from the tab bar, i.e. it is a sibling destination and
     * must not show the toolbar's up arrow (the system back gesture/button still works).
     */
    fun launchedFromTab(activity: Activity): Boolean =
        activity.intent?.getBooleanExtra(EXTRA_FROM_TAB, false) == true
}
