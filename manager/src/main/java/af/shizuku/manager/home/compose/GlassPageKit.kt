package af.shizuku.manager.home.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import af.shizuku.manager.ShizukuSettings

/**
 * Shared layout values for every page hosted by [af.shizuku.manager.home.GlassShellActivity].
 *
 * The home page is the performance baseline, so these match its 16dp card gutter and 12dp card
 * rhythm. The extra bottom inset is the height of the single floating tab strip plus its lift;
 * hosted pages use it instead of each inventing a different final spacer.
 */
object GlassPageMetrics {
    val HorizontalPadding = 16.dp
    val CardSpacing = 12.dp
    val HostedBottomInset = 104.dp
    val InlineBottomInset = 32.dp

    fun bottomInset(showTabBar: Boolean): androidx.compose.ui.unit.Dp =
        if (showTabBar) InlineBottomInset else HostedBottomInset
}

/**
 * One top bar for every Compose page: same transparent container, title size/weight, subtitle
 * treatment and icon tint. Page-specific actions stay in the actions slot; they do not get to
 * redraw a differently weighted title.
 *
 * Pages that need an input field in the title slot (settings search) pass [titleContent]. The
 * OneUI-style large header is selected with [large]; callers that want it to collapse must also
 * provide the same [scrollBehavior] to the surrounding Scaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassPageTopBar(
    title: String = "",
    subtitle: String? = null,
    large: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    containerColor: Color = Color.Transparent,
    scrolledContainerColor: Color = Color.Transparent
) {
    val titleSlot: @Composable () -> Unit = titleContent ?: {
        GlassPageTitle(title = title, subtitle = subtitle, large = large)
    }

    if (large) {
        LargeTopAppBar(
            title = titleSlot,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = scrolledContainerColor,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            scrollBehavior = scrollBehavior
        )
    } else {
        TopAppBar(
            title = titleSlot,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = containerColor,
                scrolledContainerColor = scrolledContainerColor,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
    }
}

@Composable
private fun GlassPageTitle(
    title: String,
    subtitle: String?,
    large: Boolean
) {
    Column {
        Text(
            text = title,
            style = if (large) {
                MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 28.sp,
                    letterSpacing = (-0.5).sp
                )
            } else {
                MaterialTheme.typography.titleLarge
            },
            fontWeight = if (large) FontWeight.ExtraBold else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Shared Scaffold shell for pages inside the pager. Home keeps its own large, collapsible top bar
 * because it is the interaction/perf reference; every secondary page uses this header and the
 * same floating tab strip. [titleContent], [large] and [scrollBehavior] are forwarded to
 * [GlassPageTopBar] so settings can keep its search field and OneUI header without a second
 * header implementation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassPageScaffold(
    title: String,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    titleContent: (@Composable () -> Unit)? = null,
    large: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    containerColor: Color = Color.Transparent,
    scrolledContainerColor: Color = Color.Transparent,
    showTabBar: Boolean = true,
    selectedTab: Int,
    onSelectTab: (Int) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            GlassPageTopBar(
                title = title,
                subtitle = subtitle,
                large = large,
                scrollBehavior = scrollBehavior,
                navigationIcon = navigationIcon,
                actions = actions,
                titleContent = titleContent,
                containerColor = containerColor,
                scrolledContainerColor = scrolledContainerColor
            )
        },
        bottomBar = {
            if (showTabBar) {
                GlassTabStrip(selectedIndex = selectedTab, onSelectTab = onSelectTab)
            }
        },
        content = content
    )
}

/**
 * Non-interactive liquid-glass card. Uses the exact same live backdrop path as [LiquidGlassSurface]
 * but does not install a press scale when there is no click target, so a module card with inner
 * buttons stays still while those buttons handle their own gestures.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    if (ShizukuSettings.isGlassUi()) {
        LiquidGlassSurface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            contentAlignment = contentAlignment,
            content = content
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = contentAlignment,
                content = content
            )
        }
    }
}
