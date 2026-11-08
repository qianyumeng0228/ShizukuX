package af.shizuku.manager.utils

import android.app.Activity
import android.content.Intent
import af.shizuku.core.ui.AppBarActivity.Companion.EXTRA_FROM_TAB
import af.shizuku.manager.home.HomeActivity
import af.shizuku.manager.management.ApplicationManagementActivity
import af.shizuku.manager.module.ModulesActivity
import af.shizuku.manager.settings.SettingsActivity
import af.shizuku.manager.shell.ShellTutorialActivity

/**
 * The five glass-skin destinations share one floating tab bar. They are separate activities
 * (each screen owns its own RecyclerView / Compose tree), but they behave as sibling tabs: no
 * back arrow, and moving between them replaces the current screen instead of stacking.
 */
object GlassTabs {

    const val INDEX_HOME = 0
    const val INDEX_APPS = 1
    const val INDEX_MODULES = 2
    const val INDEX_TERMINAL = 3
    const val INDEX_SETTINGS = 4

    fun open(activity: Activity, index: Int) {
        val fromHome = activity is HomeActivity
        if (index == GlassTabs.INDEX_HOME) {
            // Home is the root of the task: pop everything above it instead of stacking a
            // second copy, so the tab bar always lands on the same instance.
            if (fromHome) return
            activity.startActivity(
                Intent(activity, HomeActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            activity.finish()
            return
        }

        val target = when (index) {
            INDEX_APPS -> ApplicationManagementActivity::class.java
            INDEX_MODULES -> ModulesActivity::class.java
            INDEX_TERMINAL -> ShellTutorialActivity::class.java
            INDEX_SETTINGS -> SettingsActivity::class.java
            else -> return
        }
        activity.startActivity(Intent(activity, target).putExtra(EXTRA_FROM_TAB, true))
        // Leaving the home page keeps the task root intact (system back still returns to it);
        // moving between sibling tabs replaces the current screen so they never pile up.
        if (!fromHome) activity.finish()
    }

    /**
     * True when this screen was opened from the tab bar, i.e. it is a sibling destination and
     * must not show the toolbar's up arrow (the system back gesture/button still works).
     */
    fun launchedFromTab(activity: Activity): Boolean =
        activity.intent?.getBooleanExtra(EXTRA_FROM_TAB, false) == true
}
