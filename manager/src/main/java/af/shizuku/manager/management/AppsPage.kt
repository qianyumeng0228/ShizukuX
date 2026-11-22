package af.shizuku.manager.management

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import af.shizuku.manager.R
import af.shizuku.manager.home.compose.GlassPageMetrics
import af.shizuku.manager.home.compose.GlassPageScaffold
import af.shizuku.manager.utils.GlassTabs

/**
 * The apps destination inside the single container.
 *
 * The list itself is still the XML page ([AppsController.pageView]): rows, swipe backgrounds and
 * decorations stay exactly as they were on the standalone page. The shell around it is now shared:
 * the container owns the tab strip and the header, and the search field, refresh action and filter
 * chips are Compose controls above the list instead of the XML AppBarLayout. The overflow menu
 * (sort + SU bridge, or the five bulk actions while selecting) stays in the shared header too.
 */
@Composable
fun AppsPage(
    controller: AppsController,
    onRootCompat: () -> Unit,
    showTabBar: Boolean = true,
    /**
     * Which nav slot this page occupies. The page moved from slot 1 to the "Authorize" slot, so
     * hard-coding INDEX_APPS here would light up the catalogue tab instead of this one.
     */
    selectedTab: Int = GlassTabs.INDEX_AUTHORIZE,
    onSelectTab: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val selectionMode = controller.selectionMode
    val selectionCount = controller.selectionCount

    GlassPageScaffold(
        title = if (selectionMode) {
            stringResource(R.string.app_management_selected, selectionCount)
        } else {
            stringResource(R.string.home_app_management_title)
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
                                    text = { Text(stringResource(R.string.app_management_bulk_unhide)) },
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
        showTabBar = showTabBar,
        selectedTab = selectedTab,
        onSelectTab = onSelectTab
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AuthorizationSearchBar(
                query = controller.searchQuery,
                onQueryChange = controller::updateSearchQuery,
                onRefresh = controller::refresh
            )
            AuthorizationFilterRow(
                selected = controller.filterState,
                onSelect = controller::updateFilterState
            )
            AndroidView(
                factory = { controller.pageView(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private val authorizationFilters = listOf(
    FilterState.ALL to R.string.app_management_filter_all,
    FilterState.GRANTED to R.string.app_management_filter_granted,
    FilterState.DENIED to R.string.app_management_filter_denied,
    FilterState.HIDDEN to R.string.app_management_filter_hidden
)

@Composable
private fun AuthorizationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = GlassPageMetrics.HorizontalPadding,
                end = GlassPageMetrics.HorizontalPadding,
                top = 4.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.app_management_hint_search)) },
            leadingIcon = {
                Icon(
                    painter = painterResource(R.drawable.ic_search_24),
                    contentDescription = null
                )
            },
            singleLine = true,
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                cursorColor = MaterialTheme.colorScheme.primary,
                focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
                unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        IconButton(
            onClick = onRefresh,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh_24),
                contentDescription = stringResource(R.string.app_management_action_refresh),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AuthorizationFilterRow(
    selected: FilterState,
    onSelect: (FilterState) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                start = GlassPageMetrics.HorizontalPadding,
                end = GlassPageMetrics.HorizontalPadding,
                top = 6.dp,
                bottom = 8.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        authorizationFilters.forEach { (state, labelRes) ->
            AuthorizationFilterPill(
                label = stringResource(labelRes),
                selected = selected == state,
                onClick = { onSelect(state) }
            )
        }
    }
}

@Composable
private fun AuthorizationFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val outline = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(container)
            .border(BorderStroke(1.dp, outline), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    painter = painterResource(R.drawable.ic_check_24),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = content
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = content,
                maxLines = 1
            )
        }
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


