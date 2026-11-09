package af.shizuku.manager.home.compose

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import af.shizuku.manager.R
import kotlinx.coroutines.flow.collect

/**
 * Liquid-glass floating pill navigation (home / apps / modules / terminal / settings).
 *
 * Part of the second UI skin: a 66dp translucent capsule with a 1dp hairline stroke, floating
 * 20dp above the bottom edge. It only exists in the glass skin - the classic look keeps the
 * original card-only home, so nothing new is forced onto existing users.
 *
 * The selected tab is marked by a glass lozenge that slides between positions instead of a
 * static tint: the whole point of the skin is that a tap reads as the highlight travelling to
 * the new tab, not as a new screen appearing.
 */
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    selectedIndex: Int = 0,
    /**
     * Continuous position supplied by a pager (0..4, fractional while a swipe is in flight). When
     * present the highlight tracks the finger instead of springing to the nearest tab, so a single
     * container can slide its pages and its pill together.
     */
    positionFraction: Float? = null,
    /**
     * When the container draws a real glass lens over this bar, the flat gradient lozenge is
     * dropped - two highlights on the same slot read as one dirty piece of glass. Bars mounted on
     * the XML pages keep it, because they have no backdrop to sample.
     */
    drawHighlight: Boolean = true,
    /**
     * Drawn between the bar's fill and its icons, so a real glass lens refracts the page while the
     * icons stay legible on top of it - the order the reference implementation uses. The bar itself
     * must not clip: a lens is taller than the 66dp capsule and its rim is the part that sells the
     * refraction, so clipping here would slice off exactly the interesting edge.
     */
    lensSlot: (@Composable BoxScope.() -> Unit)? = null,
    /**
     * Reports the slot under a finger while it is held, and null when it lifts. The bar's own
     * clickable used to draw a ripple, but that ripple lands one layer *below* the glass lens, so
     * a press showed two highlights stacked on each other (a grey lozenge peeking out from under
     * the glass). The lens answers the touch itself instead, hence this callback.
     */
    onPressedChange: ((Int?) -> Unit)? = null,
    /**
     * Draw scale for the whole bar. A press lifts the glass and, with it, the capsule it sits in:
     * the reference interaction grows the bar under the finger instead of tinting the tapped cell,
     * so the two layers have to move together or the glass looks detached from its tray.
     */
    scale: Float = 1f,
    onHomeClick: () -> Unit = {},
    onAppsClick: () -> Unit = {},
    onModulesClick: () -> Unit = {},
    onTerminalClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    val dark = isSystemInDarkTheme()
    val barShape = RoundedCornerShape(28.dp)
    val pillShape = RoundedCornerShape(22.dp)
    // The bar sits over scrolling cards and there is no live backdrop blur on this skin, so the
    // fill has to carry the legibility on its own. 0xCC white (rather than near-opaque 0xE8) is
    // what the reference implementation settled on: enough page colour leaks through that the
    // glass lens reads as part of the same material instead of a white disc stuck on top.
    val fill = if (dark) Color(0x1FFFFFFF) else Color(0xCCFFFFFF)
    val stroke = if (dark) Color(0x24FFFFFF) else Color(0x14000000)
    // Colours are hardcoded rather than read from MaterialTheme: this bar is also mounted on the
    // XML pages, which host it inside a bare ComposeView without an AppTheme wrapper (so their
    // gradient background shows straight through instead of a second painted backdrop).
    val activeTint = if (dark) Color(0xFFB9A7FF) else Color(0xFF6C55C9)
    val idleTint = if (dark) Color(0xB8FFFFFF) else Color(0x99000000)

    // Springy but critically damped: overshoot reads as "liquid", a bounce reads as a toy.
    val springPos by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tab-highlight"
    )
    val animated = positionFraction ?: springPos

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .height(66.dp)
            // Scales the tray and everything on it (fill, icons, lens) about the bar's centre, so
            // a press grows the whole pill rather than only the glass riding on it.
            .scale(scale)
    ) {
        // The capsule fill lives on its own layer so the bar container no longer clips its
        // children - the lens has to overhang the capsule.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(barShape)
                .background(fill)
                .border(1.dp, stroke, barShape)
        )
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val unit = maxWidth / 5
            // Glass lozenge: soft top highlight + faint diagonal sheen + 1dp rim, so the moving
            // highlight looks like a lit piece of glass rather than a flat selection colour.
            if (drawHighlight) {
                Box(
                modifier = Modifier
                    .offset(x = unit * animated + 5.dp, y = 7.dp)
                    .width(unit - 10.dp)
                    .height(52.dp)
                    .clip(pillShape)
                    .background(
                        if (dark) Brush.verticalGradient(
                            0f to Color(0x3DFFFFFF),
                            0.45f to Color(0x1FFFFFFF),
                            1f to Color(0x0FFFFFFF)
                        ) else Brush.verticalGradient(
                            0f to Color(0xFFFFFFFF),
                            0.5f to Color(0xF2F7F5FF),
                            1f to Color(0xD9EFEAFF)
                        )
                    )
                    .border(1.dp, if (dark) Color(0x33FFFFFF) else Color(0x1F6C55C9), pillShape)
            ) {
                // Top inner highlight (glass rule #4).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                0f to Color(0x66FFFFFF),
                                1f to Color(0x00FFFFFF)
                            )
                        )
                )
                // Diagonal sheen: the refraction edge you read as "liquid".
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end = androidx.compose.ui.geometry.Offset(420f, 180f),
                                colors = listOf(
                                    Color(0x33FFFFFF),
                                    Color(0x0DFFFFFF),
                                    Color(0x00FFFFFF)
                                )
                            )
                        )
                )
                }
            }

            lensSlot?.invoke(this)

            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassNavItem(
                    modifier = Modifier.weight(1f),
                    index = 0,
                    onPressedChange = onPressedChange,
                    iconRes = R.drawable.ic_home_24,
                    label = stringResource(R.string.nav_home),
                    tint = if (selectedIndex == 0) activeTint else idleTint,
                    selected = selectedIndex == 0,
                    onClick = onHomeClick
                )
                GlassNavItem(
                    modifier = Modifier.weight(1f),
                    // ic_default_app_icon is an adaptive-icon wrapper, which painterResource
                    // rejects at measure time ("Only VectorDrawables and rasterized asset types
                    // are supported"), so the Apps tab uses the Android robot instead - which is
                    // also what the label means, while the puzzle piece stays the modules glyph.
                    index = 1,
                    onPressedChange = onPressedChange,
                    iconRes = R.drawable.ic_android_24,
                    label = stringResource(R.string.nav_apps),
                    tint = if (selectedIndex == 1) activeTint else idleTint,
                    selected = selectedIndex == 1,
                    onClick = onAppsClick
                )
                GlassNavItem(
                    modifier = Modifier.weight(1f),
                    // ADB modules sit between the app list and the terminal: both neighbours are
                    // the usual way in, and modules are what you install to extend the shell.
                    index = 2,
                    onPressedChange = onPressedChange,
                    iconRes = R.drawable.ic_extension_24,
                    label = stringResource(R.string.nav_modules),
                    tint = if (selectedIndex == 2) activeTint else idleTint,
                    selected = selectedIndex == 2,
                    onClick = onModulesClick
                )
                GlassNavItem(
                    modifier = Modifier.weight(1f),
                    index = 3,
                    onPressedChange = onPressedChange,
                    iconRes = R.drawable.ic_terminal_24,
                    label = stringResource(R.string.nav_terminal),
                    tint = if (selectedIndex == 3) activeTint else idleTint,
                    selected = selectedIndex == 3,
                    onClick = onTerminalClick
                )
                GlassNavItem(
                    modifier = Modifier.weight(1f),
                    index = 4,
                    onPressedChange = onPressedChange,
                    iconRes = R.drawable.ic_settings_outline_24,
                    label = stringResource(R.string.nav_settings),
                    tint = if (selectedIndex == 4) activeTint else idleTint,
                    selected = selectedIndex == 4,
                    onClick = onSettingsClick
                )
            }
        }
    }
}

/**
 * The tab bar as a page-level bottom slot: used by the Compose pages (modules / settings), while
 * the XML pages mount the same [GlassBottomBar] through GlassTabBar.attach().
 */
@Composable
fun GlassTabStrip(
    selectedIndex: Int,
    onSelectTab: (Int) -> Unit,
    positionFraction: Float? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp)
    ) {
        GlassBottomBar(
            selectedIndex = selectedIndex,
            positionFraction = positionFraction,
            onHomeClick = { onSelectTab(af.shizuku.manager.utils.GlassTabs.INDEX_HOME) },
            onAppsClick = { onSelectTab(af.shizuku.manager.utils.GlassTabs.INDEX_APPS) },
            onModulesClick = { onSelectTab(af.shizuku.manager.utils.GlassTabs.INDEX_MODULES) },
            onTerminalClick = { onSelectTab(af.shizuku.manager.utils.GlassTabs.INDEX_TERMINAL) },
            onSettingsClick = { onSelectTab(af.shizuku.manager.utils.GlassTabs.INDEX_SETTINGS) }
        )
    }
}

@Composable
private fun GlassNavItem(
    index: Int,
    iconRes: Int,
    label: String,
    tint: Color,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onPressedChange: ((Int?) -> Unit)? = null,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val reportPress = onPressedChange
    if (reportPress != null) {
        // Press/Release instead of the ripple: a press reports the slot so the container can
        // squash the glass, and a drag that gets claimed by the bar arrives here as Cancel, which
        // is also the right moment to let the glass spring back.
        LaunchedEffect(interaction, reportPress) {
            interaction.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> reportPress(index)
                    is PressInteraction.Release -> reportPress(null)
                    is PressInteraction.Cancel -> reportPress(null)
                    else -> Unit
                }
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            // indication = null: the default ripple is drawn below the glass lens, so holding a
            // tab showed a grey lozenge under the glass. The lens itself is what reacts now.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
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
