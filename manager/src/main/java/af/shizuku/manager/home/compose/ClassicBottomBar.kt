package af.shizuku.manager.home.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import af.shizuku.manager.R
import af.shizuku.manager.utils.GlassTabs

/**
 * The classic skin keeps the stock Material navigation bar. The single-container pager still hosts
 * every page, but nothing from the glass shell may leak into it: no wallpaper layer, no floating
 * capsule, no overlay icon tints.
 */
@Composable
fun ClassicBottomBar(
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationBar(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        ClassicNavItem(GlassTabs.INDEX_HOME, selectedIndex, onSelectTab, R.drawable.ic_home_24, stringResource(R.string.nav_home))
        ClassicNavItem(GlassTabs.INDEX_AUTHORIZE, selectedIndex, onSelectTab, R.drawable.ic_authorize_24, stringResource(R.string.nav_authorize))
        ClassicNavItem(GlassTabs.INDEX_MODULES, selectedIndex, onSelectTab, R.drawable.ic_extension_24, stringResource(R.string.nav_modules))
        ClassicNavItem(GlassTabs.INDEX_TERMINAL, selectedIndex, onSelectTab, R.drawable.ic_terminal_24, stringResource(R.string.nav_terminal))
        ClassicNavItem(GlassTabs.INDEX_APPS, selectedIndex, onSelectTab, R.drawable.ic_android_24, stringResource(R.string.nav_apps))
        ClassicNavItem(GlassTabs.INDEX_SETTINGS, selectedIndex, onSelectTab, R.drawable.ic_settings_outline_24, stringResource(R.string.nav_settings))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ClassicNavItem(
    index: Int,
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    iconRes: Int,
    label: String
) {
    val selected = selectedIndex == index
    NavigationBarItem(
        selected = selected,
        onClick = { onSelectTab(index) },
        icon = {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label
            )
        },
        label = {
            Text(label, fontSize = 10.sp, maxLines = 1)
        },
        alwaysShowLabel = true,
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}
