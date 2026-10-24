package af.shizuku.manager.permissionmanager

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService
import timber.log.Timber

// Shell-grantable privileged permissions commonly needed by backup/root apps.
// These are PROTECTION_SIGNATURE but grantable to any package by uid=2000 (shell).
private val PRIVILEGED_PERMISSIONS = setOf(
    "android.permission.READ_LOGS",
    "android.permission.DUMP",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.WRITE_SECURE_SETTINGS",
    "android.permission.READ_FRAME_BUFFER",
    "android.permission.INTERACT_ACROSS_USERS",
    "android.permission.INTERACT_ACROSS_USERS_FULL",
    "android.permission.MANAGE_USB",
    "android.permission.BATTERY_STATS",
    "android.permission.MOUNT_UNMOUNT_FILESYSTEMS",
    "android.permission.INSTALL_PACKAGES",
    "android.permission.DELETE_PACKAGES",
    "android.permission.CHANGE_NETWORK_STATE",
    "android.permission.CHANGE_WIFI_STATE",
    "android.permission.ACCESS_WIFI_STATE",
    "android.permission.MANAGE_NETWORK_POLICY",
    "android.permission.CONNECTIVITY_INTERNAL",
    "android.permission.OBSERVE_APP_USAGE",
    "android.permission.GET_APP_OPS_STATS",
    "android.permission.MANAGE_APP_OPS_MODES",
    "android.permission.CHANGE_COMPONENT_ENABLED_STATE",
    "android.permission.FORCE_STOP_PACKAGES",
)

// Chinese display labels for common runtime/privileged permissions. The permission row shows
// shortName (last path segment) with a localized label where known, falling back to the raw
// short name. Keyed by short name (e.g. "CAMERA").
private val PERM_SHORT_LABELS = mapOf(
    // runtime (dangerous)
    "CAMERA" to "相机",
    "RECORD_AUDIO" to "麦克风",
    "READ_PHONE_STATE" to "读取手机状态",
    "CALL_PHONE" to "拨打电话",
    "READ_CALL_LOG" to "读取通话记录",
    "WRITE_CALL_LOG" to "写入通话记录",
    "ADD_VOICEMAIL" to "添加语音邮件",
    "USE_SIP" to "使用 SIP",
    "PROCESS_OUTGOING_CALLS" to "处理拨出电话",
    "READ_CONTACTS" to "读取联系人",
    "WRITE_CONTACTS" to "写入联系人",
    "GET_ACCOUNTS" to "获取账户",
    "READ_EXTERNAL_STORAGE" to "读取外部存储",
    "WRITE_EXTERNAL_STORAGE" to "写入外部存储",
    "READ_MEDIA_IMAGES" to "读取图片",
    "READ_MEDIA_VIDEO" to "读取视频",
    "READ_MEDIA_AUDIO" to "读取音频",
    "READ_MEDIA_VISUAL_USER_SELECTED" to "访问媒体选取内容",
    "ACCESS_MEDIA_LOCATION" to "访问媒体位置信息",
    "ACCESS_FINE_LOCATION" to "精确位置",
    "ACCESS_COARSE_LOCATION" to "粗略位置",
    "ACCESS_BACKGROUND_LOCATION" to "后台位置",
    "READ_SMS" to "读取短信",
    "RECEIVE_SMS" to "接收短信",
    "SEND_SMS" to "发送短信",
    "READ_WAP_PUSH" to "读取 WAP 推送",
    "RECEIVE_WAP_PUSH" to "接收 WAP 推送",
    "RECEIVE_MMS" to "接收彩信",
    "READ_CLIPBOARD_IN_HONORED_APP" to "读取剪贴板",
    "READ_CALENDAR" to "读取日历",
    "WRITE_CALENDAR" to "写入日历",
    "BODY_SENSORS" to "身体传感器",
    "BODY_SENSORS_BACKGROUND" to "后台身体传感器",
    "ACTIVITY_RECOGNITION" to "活动识别",
    "POST_NOTIFICATIONS" to "发送通知",
    "BLUETOOTH_SCAN" to "扫描蓝牙设备",
    "BLUETOOTH_CONNECT" to "连接蓝牙设备",
    "BLUETOOTH_ADVERTISE" to "蓝牙广播",
    "UWB_RANGING" to "UWB 测距",
    "USE_SENSOR_PRIVACY" to "传感器隐私",
    "NEARBY_WIFI_DEVICES" to "附近的 Wi-Fi 设备",
    "MANAGE_OWN_CALL_LOG" to "管理自己的通话记录",
    // privileged (signature / shell-grantable)
    "READ_LOGS" to "读取日志",
    "DUMP" to "系统转储",
    "PACKAGE_USAGE_STATS" to "使用情况访问",
    "WRITE_SECURE_SETTINGS" to "写入安全设置",
    "READ_FRAME_BUFFER" to "读取帧缓冲",
    "INTERACT_ACROSS_USERS" to "跨用户交互",
    "INTERACT_ACROSS_USERS_FULL" to "跨用户完全交互",
    "MANAGE_USB" to "管理 USB",
    "BATTERY_STATS" to "电池统计",
    "MOUNT_UNMOUNT_FILESYSTEMS" to "挂载/卸载文件系统",
    "INSTALL_PACKAGES" to "安装应用",
    "DELETE_PACKAGES" to "删除应用",
    "CHANGE_NETWORK_STATE" to "更改网络状态",
    "CHANGE_WIFI_STATE" to "更改 Wi-Fi 状态",
    "ACCESS_WIFI_STATE" to "读取 Wi-Fi 状态",
    "MANAGE_NETWORK_POLICY" to "管理网络策略",
    "CONNECTIVITY_INTERNAL" to "内部网络连接",
    "OBSERVE_APP_USAGE" to "观察应用使用",
    "GET_APP_OPS_STATS" to "获取 AppOps 统计",
    "MANAGE_APP_OPS_MODES" to "管理 AppOps 模式",
    "CHANGE_COMPONENT_ENABLED_STATE" to "更改组件启用状态",
    "FORCE_STOP_PACKAGES" to "强制停止应用",
    "WRITE_SETTINGS" to "修改系统设置",
    "SYSTEM_ALERT_WINDOW" to "悬浮窗",
    "REQUEST_INSTALL_PACKAGES" to "安装未知应用",
    "MANAGE_EXTERNAL_STORAGE" to "管理所有文件",
    "QUERY_ALL_PACKAGES" to "查询所有应用",
    "READ_PRIVILEGED_PHONE_STATE" to "读取受保护电话状态",
    "READ_BASIC_PHONE_STATE" to "读取基础电话状态",
)

private fun permShortLabel(name: String): String {
    val short = name.substringAfterLast('.')
    return PERM_SHORT_LABELS[short] ?: short
}

data class AppItem(
    val packageName: String,
    val label: String,
)

data class PermItem(
    val name: String,
    val shortName: String,
    val isGranted: Boolean,
    val isPrivileged: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionManagerScreen(onBackClick: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = context.packageManager

    var query by remember { mutableStateOf("") }
    var allApps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }

    var selectedApp by remember { mutableStateOf<AppItem?>(null) }
    var appPerms by remember { mutableStateOf<List<PermItem>>(emptyList()) }
    var isLoadingPerms by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedApp != null) {
        selectedApp = null
        appPerms = emptyList()
    }

    // Load app list on open. Prefer the privileged server-side package query: under HyperOS
    // app-visibility privacy restrictions local pm.getInstalledPackages(0) returns only this
    // app (even with QUERY_ALL_PACKAGES granted), while the shell-uid server sees all 149+
    // third-party packages. Fall back to the local query when Shizuku is not running — the
    // fallback list may be incomplete on restricted devices.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ipcList = runCatching {
                val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                val bundles = service.backupRestoreExtra?.listInstalledPackages(false)
                if (bundles.isNullOrEmpty()) null
                else bundles.mapNotNull { b ->
                    val pkg = b.getString("packageName") ?: return@mapNotNull null
                    AppItem(pkg, b.getString("label") ?: pkg)
                }
            }.getOrNull()

            val list = (ipcList ?: run {
                val packages = pm.getInstalledPackages(0)
                packages.mapNotNull { pi ->
                    val label = try { pm.getApplicationLabel(pi.applicationInfo ?: return@mapNotNull null).toString() } catch (_: Exception) { pi.packageName }
                    AppItem(pi.packageName, label)
                }
            }).sortedBy { it.label.lowercase() }
            allApps = list
            isLoadingApps = false
        }
    }

    val filteredApps = remember(allApps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) allApps
        else allApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selectedApp != null) {
                        Text(selectedApp!!.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text(stringResource(R.string.home_permission_manager_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedApp != null) {
                            selectedApp = null
                            appPerms = emptyList()
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_back_24),
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        if (selectedApp != null) {
            // ── Permission detail view ────────────────────────────────────────
            PermissionDetailPane(
                app = selectedApp!!,
                perms = appPerms,
                isLoading = isLoadingPerms,
                paddingValues = paddingValues,
                onToggle = { perm, grant ->
                    scope.launch(Dispatchers.IO) {
                        // ShizukuX adaptation: upstream called ShizukuPlusAPI.PackageGovernor;
                        // resolve IPackageGovernorExtra directly from the live binder instead.
                        val ok = runCatching {
                            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                            val governor = service.packageGovernorExtra
                                ?: return@runCatching false
                            if (grant) governor.grantPermission(selectedApp!!.packageName, perm.name)
                            else governor.revokePermission(selectedApp!!.packageName, perm.name)
                        }.getOrDefault(false)
                        if (ok) {
                            withContext(Dispatchers.Main) {
                                appPerms = appPerms.map {
                                    if (it.name == perm.name) it.copy(isGranted = grant) else it
                                }
                            }
                        } else {
                            Timber.w("PermissionManager: toggle failed for ${perm.name}")
                        }
                    }
                }
            )
        } else {
            // ── App list view ─────────────────────────────────────────────────
            AppListPane(
                apps = filteredApps,
                isLoading = isLoadingApps,
                query = query,
                onQueryChange = { query = it },
                paddingValues = paddingValues,
                onAppClick = { app ->
                    selectedApp = app
                    isLoadingPerms = true
                    appPerms = emptyList()
                    scope.launch(Dispatchers.IO) {
                        val perms = loadPermissions(pm, app.packageName)
                        withContext(Dispatchers.Main) {
                            appPerms = perms
                            isLoadingPerms = false
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun AppListPane(
    apps: List<AppItem>,
    isLoading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    paddingValues: PaddingValues,
    onAppClick: (AppItem) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(stringResource(R.string.permission_manager_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {})
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (apps.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.permission_manager_no_apps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(apps, key = { it.packageName }) { app ->
                    AppRow(app = app, onClick = { onAppClick(app) })
                    HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, onClick: () -> Unit) {
    val context = LocalContext.current
    val pm = context.packageManager
    val iconBitmap = remember(app.packageName) {
        runCatching {
            pm.getApplicationIcon(app.packageName).toBitmap().asImageBitmap()
        }.getOrNull()
    }

    // Use Material3's clickable Card overload rather than Modifier.clickable: the latter reads
    // LocalIndication, and on Compose Foundation 1.7+ that threw
    // "clickable only supports IndicationNodeFactory instances provided to LocalIndication"
    // when a legacy Indication was in scope (SHIZUKUPLUS-72 / #309).
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_default_app_icon),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            painter = painterResource(R.drawable.ic_outline_open_in_new_24),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(20.dp)
        )
    }
    }
}

@Composable
private fun PermissionDetailPane(
    app: AppItem,
    perms: List<PermItem>,
    isLoading: Boolean,
    paddingValues: PaddingValues,
    onToggle: (PermItem, Boolean) -> Unit,
) {
    val privileged = perms.filter { it.isPrivileged }
    val runtime = perms.filter { !it.isPrivileged }

    if (isLoading) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    if (perms.isEmpty()) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Text(
                stringResource(R.string.permission_manager_no_permissions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        if (privileged.isNotEmpty()) {
            item {
                PermSectionHeader(stringResource(R.string.permission_manager_section_privileged))
            }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.permission_manager_privileged_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            items(privileged, key = { "priv_${it.name}" }) { perm ->
                PermToggleRow(perm = perm, onToggle = onToggle)
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }

        if (runtime.isNotEmpty()) {
            item {
                PermSectionHeader(stringResource(R.string.permission_manager_section_runtime))
            }
            items(runtime, key = { "rt_${it.name}" }) { perm ->
                PermToggleRow(perm = perm, onToggle = onToggle)
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PermSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp, end = 16.dp)
    )
}

@Composable
private fun PermToggleRow(perm: PermItem, onToggle: (PermItem, Boolean) -> Unit) {
    // Card(onClick) handles the click; Switch.onCheckedChange=null avoids double-firing.
    // Modifier.clickable would read LocalIndication and crash on Compose Foundation 1.7+
    // with a legacy Indication in scope (SHIZUKUPLUS-72 / #309).
    Card(
        onClick = { onToggle(perm, !perm.isGranted) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = perm.shortName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = perm.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(checked = perm.isGranted, onCheckedChange = null)
    }
    }
}

private fun loadPermissions(pm: PackageManager, packageName: String): List<PermItem> {
    return try {
        val info: PackageInfo = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val declared = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags ?: IntArray(declared.size)
        val result = mutableListOf<PermItem>()
        for (i in declared.indices) {
            val name = declared[i] ?: continue
            val isGranted = flags.getOrElse(i) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            val isPriv = name in PRIVILEGED_PERMISSIONS
            val protection = try {
                val pi = pm.getPermissionInfo(name, 0)
                pi.protection
            } catch (_: Exception) {
                // Unknown permission — include if it's in our privileged set, otherwise skip
                if (isPriv) PermissionInfo.PROTECTION_SIGNATURE else continue
            }
            when {
                isPriv -> result.add(PermItem(name, permShortLabel(name), isGranted, isPrivileged = true))
                protection == PermissionInfo.PROTECTION_DANGEROUS ->
                    result.add(PermItem(name, permShortLabel(name), isGranted, isPrivileged = false))
            }
        }
        // Sort: privileged first (by name), then runtime (by name)
        result.sortWith(compareBy({ !it.isPrivileged }, { it.shortName }))
        result
    } catch (e: Exception) {
        Timber.w(e, "loadPermissions failed for $packageName")
        emptyList()
    }
}
