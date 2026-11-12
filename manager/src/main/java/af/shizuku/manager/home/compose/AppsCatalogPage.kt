package af.shizuku.manager.home.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import af.shizuku.manager.R
import af.shizuku.manager.utils.GlassTabs

/**
 * The "Apps" destination: a catalogue of the capabilities built into ShizukuX, laid out as the
 * rounded card grid users already recognise from bundled-tool launchers, instead of a second
 * list of installed packages.
 *
 * The installed-app list is the neighbouring "Authorize" page - it exists to grant shell access
 * per app. This page answers a different question ("what can ShizukuX do?"), so each card is a
 * real entry point into an existing activity; there are no decorative tiles here.
 */
@Composable
fun AppsCatalogPage(
    onOpen: (Class<*>) -> Unit,
    onSelectTab: (Int) -> Unit = {}
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.nav_apps),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            GlassTabStrip(selectedIndex = GlassTabs.INDEX_APPS, onSelectTab = onSelectTab)
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            CatalogSection(
                titleRes = R.string.apps_catalog_section_common,
                entries = commonEntries,
                onOpen = onOpen
            )
            Spacer(modifier = Modifier.height(18.dp))
            CatalogSection(
                titleRes = R.string.apps_catalog_section_all,
                entries = allEntries,
                onOpen = onOpen
            )
            Spacer(modifier = Modifier.height(26.dp))
        }
    }
}

private class CatalogEntry(val labelRes: Int, val target: Class<*>)

/** Four entries: the ones reached most often from the home cards. */
private val commonEntries = listOf(
    CatalogEntry(R.string.apps_catalog_backup, af.shizuku.manager.backup.AppBackupActivity::class.java),
    CatalogEntry(R.string.apps_catalog_firewall, af.shizuku.manager.firewall.FirewallActivity::class.java),
    CatalogEntry(R.string.apps_catalog_battery, af.shizuku.manager.battery.BatteryWhitelistActivity::class.java),
    CatalogEntry(
        R.string.apps_catalog_permissions,
        af.shizuku.manager.permissionmanager.PermissionManagerActivity::class.java
    )
)

/** Everything else, still grouped so the grid does not become one undifferentiated wall. */
private val allEntries = listOf(
    CatalogEntry(R.string.apps_catalog_hiding, af.shizuku.manager.hiding.HidingActivity::class.java),
    CatalogEntry(R.string.apps_catalog_autostart, af.shizuku.manager.autostart.AutoStartActivity::class.java),
    CatalogEntry(R.string.apps_catalog_appops, af.shizuku.manager.appops.AppOpsActivity::class.java),
    CatalogEntry(R.string.apps_catalog_files, af.shizuku.manager.files.FilesActivity::class.java),
    CatalogEntry(R.string.apps_catalog_wifi, af.shizuku.manager.wifi.WifiPasswordActivity::class.java),
    CatalogEntry(R.string.apps_catalog_device, af.shizuku.manager.device.DeviceInfoActivity::class.java),
    CatalogEntry(R.string.apps_catalog_intents, af.shizuku.manager.intents.IntentsActivity::class.java),
    CatalogEntry(R.string.apps_catalog_store, af.shizuku.manager.module.ModuleStoreActivity::class.java),
    CatalogEntry(R.string.apps_catalog_scripting, af.shizuku.manager.scripting.ScriptingActivity::class.java),
    CatalogEntry(R.string.apps_catalog_system_hub, af.shizuku.manager.home.SystemHubActivity::class.java),
    CatalogEntry(
        R.string.apps_catalog_root_compat,
        af.shizuku.manager.settings.RootCompatibilityActivity::class.java
    ),
    CatalogEntry(R.string.apps_catalog_relay, af.shizuku.manager.settings.ExternalRelayActivity::class.java),
    CatalogEntry(
        R.string.apps_catalog_dhizuku,
        af.shizuku.manager.settings.DhizukuAppManagementActivity::class.java
    ),
    CatalogEntry(R.string.apps_catalog_stealth, af.shizuku.manager.settings.StealthModeActivity::class.java)
)

@Composable
private fun CatalogSection(titleRes: Int, entries: List<CatalogEntry>, onOpen: (Class<*>) -> Unit) {
    Text(
        text = stringResource(titleRes),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
    )
    Spacer(modifier = Modifier.height(10.dp))
    // Two columns, built by chunking rather than a wrapped grid: an odd tail would otherwise
    // stretch its last card to full width and read as a different kind of element.
    entries.chunked(2).forEach { row ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            row.forEach { entry ->
                CatalogCard(entry = entry, modifier = Modifier.weight(1f), onOpen = onOpen)
            }
            if (row.size == 1) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun CatalogCard(entry: CatalogEntry, modifier: Modifier, onOpen: (Class<*>) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier = modifier
            .height(56.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), shape)
            .clickable { onOpen(entry.target) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(entry.labelRes),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
