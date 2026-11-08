package af.shizuku.manager.management

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import af.shizuku.manager.R
import af.shizuku.manager.home.compose.GlassTabStrip
import af.shizuku.manager.utils.GlassTabs

/**
 * The apps destination inside the single container.
 *
 * The list itself is still the XML page ([AppsController.pageView]): rows, swipe backgrounds and
 * decorations stay exactly as they were on the standalone page. What is rebuilt here is the shell
 * around it — the container owns the tab strip and the app bar, so the title, the selection state
 * and the overflow menu (sort + SU bridge, or the five bulk actions while selecting) are drawn in
 * Compose instead of by an OptionsMenu that no longer exists.
 */
@Composable
fun AppsPage(
    controller: AppsController,
    onRootCompat: () -> Unit,
    showTabBar: Boolean = true,
    onSelectTab: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val selectionMode = controller.selectionMode
    val selectionCount = controller.selectionCount

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectionMode) {
                            stringResource(R.string.app_management_selected, selectionCount)
                        } else {
                            stringResource(R.string.home_app_management_title)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                },
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { controller.exitSelection() }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_back_24),
                                contentDescription = stringResource(R.string.accessibility_back)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert_24),
                            contentDescription = stringResource(R.string.app_management_action_sort)
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (selectionMode) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.app_management_bulk_select_all)) },
                                onClick = { menuOpen = false; controller.selectAll() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.app_management_bulk_grant)) },
                                onClick = { menuOpen = false; controller.grantSelected() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.app_management_bulk_revoke)) },
                                onClick = { menuOpen = false; controller.revokeSelected() }
                            )
                            if (controller.filterState == FilterState.HIDDEN) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.app_management_undo)) },
                                    onClick = { menuOpen = false; controller.unhideSelected() }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.app_management_bulk_hide)) },
                                    onClick = { menuOpen = false; controller.hideSelected() }
                                )
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.app_management_action_sort),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            SortItem(
                                text = stringResource(R.string.app_management_sort_a_to_z),
                                checked = controller.sortOrder == SortOrder.NAME_ASC
                            ) {
                                menuOpen = false
                                controller.setSort(SortOrder.NAME_ASC)
                            }
                            SortItem(
                                text = stringResource(R.string.app_management_sort_last_installed),
                                checked = controller.sortOrder == SortOrder.LAST_INSTALLED
                            ) {
                                menuOpen = false
                                controller.setSort(SortOrder.LAST_INSTALLED)
                            }
                            SortItem(
                                text = stringResource(R.string.app_management_sort_last_updated),
                                checked = controller.sortOrder == SortOrder.LAST_UPDATED
                            ) {
                                menuOpen = false
                                controller.setSort(SortOrder.LAST_UPDATED)
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.su_bridge_title)) },
                                onClick = { menuOpen = false; onRootCompat() }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        bottomBar = {
            if (showTabBar) {
                GlassTabStrip(selectedIndex = GlassTabs.INDEX_APPS, onSelectTab = onSelectTab)
            }
        }
    ) { innerPadding ->
        AndroidView(
            factory = { controller.pageView(context) },
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                )
        )
    }
}

@Composable
private fun SortItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                color = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        },
        // The XML menu was a checkable group and showed a tick next to the active order; a colour
        // change alone is too weak a signal at this size.
        trailingIcon = {
            if (checked) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_24),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        },
        onClick = onClick
    )
}
