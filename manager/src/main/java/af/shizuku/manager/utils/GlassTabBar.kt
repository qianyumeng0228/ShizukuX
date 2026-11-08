package af.shizuku.manager.utils

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.coordinatorlayout.widget.CoordinatorLayout
import af.shizuku.manager.home.compose.GlassBottomBar

/**
 * Mounts the floating tab bar on the XML pages that are reachable from it. Compose pages put the
 * same composable in their Scaffold's bottomBar slot; both end up with one identical bar, so the
 * tab strip never disappears when you move between destinations.
 *
 * The bar is deliberately laid into a bare [ComposeView] with no AppTheme wrapper: the XML pages
 * already paint the shared gradient, and wrapping them would paint a second backdrop inside the
 * 66dp strip, cutting a visible seam.
 */
object GlassTabBar {

    /** Height reserved under page content: 66dp bar + 20dp lift + 30dp breathing room. */
    val CONTENT_BOTTOM_INSET = 116.dp

    fun attach(activity: Activity, root: ViewGroup, selectedIndex: Int) {
        val bar = ComposeView(activity).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                ) {
                    GlassBottomBar(
                        selectedIndex = selectedIndex,
                        onHomeClick = { GlassTabs.open(activity, GlassTabs.INDEX_HOME) },
                        onAppsClick = { GlassTabs.open(activity, GlassTabs.INDEX_APPS) },
                        onModulesClick = { GlassTabs.open(activity, GlassTabs.INDEX_MODULES) },
                        onTerminalClick = { GlassTabs.open(activity, GlassTabs.INDEX_TERMINAL) },
                        onSettingsClick = { GlassTabs.open(activity, GlassTabs.INDEX_SETTINGS) }
                    )
                }
            }
        }
        val lp = if (root is CoordinatorLayout) {
            CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM }
        } else {
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(bar, lp)
    }
}
