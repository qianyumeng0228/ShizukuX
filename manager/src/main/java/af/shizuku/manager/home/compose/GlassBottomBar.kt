package af.shizuku.manager.home.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import af.shizuku.manager.R

/**
 * Liquid-glass floating pill navigation (home / apps / terminal / settings).
 *
 * Part of the second UI skin: a 66dp translucent capsule with a 1dp hairline stroke, floating
 * 20dp above the bottom edge. It only exists in the glass skin - the classic look keeps the
 * original card-only home, so nothing new is forced onto existing users.
 */
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    onAppsClick: () -> Unit,
    onTerminalClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(28.dp)
    val fill = if (dark) Color(0x1FFFFFFF) else Color(0x8CFFFFFF)
    val stroke = if (dark) Color(0x24FFFFFF) else Color(0x14000000)
    val activeTint = MaterialTheme.colorScheme.primary
    val idleTint = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(66.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // "Home" is the current destination, so it renders as the active item and does nothing.
        GlassNavItem(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_home_24,
            label = stringResource(R.string.nav_home),
            tint = activeTint,
            selected = true,
            onClick = {}
        )
        GlassNavItem(
            modifier = Modifier.weight(1f),
            // ic_default_app_icon is an adaptive-icon wrapper, which painterResource rejects at
            // measure time ("Only VectorDrawables and rasterized asset types are supported"),
            // so the Apps tab uses a plain vector.
            iconRes = R.drawable.ic_extension_24,
            label = stringResource(R.string.nav_apps),
            tint = idleTint,
            onClick = onAppsClick
        )
        GlassNavItem(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_terminal_24,
            label = stringResource(R.string.nav_terminal),
            tint = idleTint,
            onClick = onTerminalClick
        )
        GlassNavItem(
            modifier = Modifier.weight(1f),
            iconRes = R.drawable.ic_settings_outline_24,
            label = stringResource(R.string.nav_settings),
            tint = idleTint,
            onClick = onSettingsClick
        )
    }
}

@Composable
private fun GlassNavItem(
    iconRes: Int,
    label: String,
    tint: Color,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            color = tint,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}
