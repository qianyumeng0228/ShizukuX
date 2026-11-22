package af.shizuku.manager.home.compose

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import af.shizuku.manager.R
import af.shizuku.manager.home.compose.kyant.LiquidBottomTab
import af.shizuku.manager.home.compose.kyant.LiquidBottomTabs
import af.shizuku.manager.utils.GlassTabs
import com.kyant.backdrop.Backdrop

/**
 * The shell's navigation is the upstream [LiquidBottomTabs], not a look-alike.
 *
 * The exact component owns the 64dp tray, the moving 56dp lens, the 78/56 pressed scale, the
 * damped drag, the chromatic-aberration press lens and the radial InteractiveHighlight. That is
 * also what fixes the old bar's gesture fight: the old version wrapped every icon in its own
 * clickable and then tried to claim the same horizontal drag at the parent.
 */
@Composable
fun ShizukuLiquidTabs(
    selectedIndex: () -> Int,
    onSelectTab: (Int) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val dark = isSystemInDarkTheme()
    val currentIndex = selectedIndex()
    val activeTint = if (dark) Color(0xFF0091FF) else Color(0xFF0088FF)
    val idleTint = if (dark) Color(0xB8FFFFFF) else Color(0xB8000000)

    LiquidBottomTabs(
        selectedTabIndex = selectedIndex,
        onTabSelected = onSelectTab,
        backdrop = backdrop,
        tabsCount = 6,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
    ) {
        NavTab(
            index = GlassTabs.INDEX_HOME,
            selectedIndex = currentIndex,
            onSelectTab = onSelectTab,
            iconRes = R.drawable.ic_home_24,
            label = stringResource(R.string.nav_home),
            activeTint = activeTint,
            idleTint = idleTint
        )
        NavTab(
            index = GlassTabs.INDEX_AUTHORIZE,
            selectedIndex = currentIndex,
            onSelectTab = onSelectTab,
            iconRes = R.drawable.ic_authorize_24,
            label = stringResource(R.string.nav_authorize),
            activeTint = activeTint,
            idleTint = idleTint
        )
        NavTab(
            index = GlassTabs.INDEX_MODULES,
            selectedIndex = currentIndex,
            onSelectTab = onSelectTab,
            iconRes = R.drawable.ic_extension_24,
            label = stringResource(R.string.nav_modules),
            activeTint = activeTint,
            idleTint = idleTint
        )
        NavTab(
            index = GlassTabs.INDEX_TERMINAL,
            selectedIndex = currentIndex,
            onSelectTab = onSelectTab,
            iconRes = R.drawable.ic_terminal_24,
            label = stringResource(R.string.nav_terminal),
            activeTint = activeTint,
            idleTint = idleTint
        )
        NavTab(
            index = GlassTabs.INDEX_APPS,
            selectedIndex = currentIndex,
            onSelectTab = onSelectTab,
            iconRes = R.drawable.ic_android_24,
            label = stringResource(R.string.nav_apps),
            activeTint = activeTint,
            idleTint = idleTint
        )
        NavTab(
            index = GlassTabs.INDEX_SETTINGS,
            selectedIndex = currentIndex,
            onSelectTab = onSelectTab,
            iconRes = R.drawable.ic_settings_outline_24,
            label = stringResource(R.string.nav_settings),
            activeTint = activeTint,
            idleTint = idleTint
        )
    }
}

@Composable
private fun RowScope.NavTab(
    index: Int,
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    iconRes: Int,
    label: String,
    activeTint: Color,
    idleTint: Color
) {
    val selected = selectedIndex == index
    LiquidBottomTab(onClick = { onSelectTab(index) }) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = if (selected) activeTint else idleTint,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = if (selected) activeTint else idleTint,
            fontSize = 10.sp,
            lineHeight = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}
