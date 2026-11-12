package af.shizuku.manager.apps

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import af.shizuku.manager.home.compose.GlassTabStrip
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.GlassTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「应用」页：用户自己的应用列表。
 *
 * 上半部分按使用频次取前 4 个（系统 UsageStats，未授权时给出授权入口而不是伪造顺序），
 * 下半部分是用户加进来的应用，星野scene 常驻第一条；右上角两个入口分别是应用商店与添加。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsPage(onSelectTab: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var curated by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var installed by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var frequent by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var usageAccess by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<CatalogApp?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    var pickingMode by remember { mutableStateOf(false) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installProgress by remember { mutableStateOf<Pair<Float, String>?>(null) }
    var installTitle by remember { mutableStateOf("") }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            val loadedCurated = AppCatalogStore.orderedPackages(context)
                .map { AppCatalogStore.resolve(context, it) }
                .filterNotNull()
            val loadedInstalled = AppCatalogStore.installedApps(context)
            val access = AppUsageRanker.hasAccess(context)
            val top = if (access) {
                AppUsageRanker.topPackages(context, 12)
                    .mapNotNull { pkg ->
                        loadedInstalled.firstOrNull { it.packageName == pkg }
                    }
                    .take(4)
            } else {
                emptyList()
            }
            withContext(Dispatchers.Main) {
                curated = loadedCurated
                installed = loadedInstalled
                usageAccess = access
                frequent = top
            }
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    val apkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            installTitle = "从安装包安装"
            installProgress = 0f to "读取安装包"
            scope.launch {
                val outcome = ApkInstaller.install(context, uri) { fraction, stage ->
                    installProgress = fraction to stage
                }
                installProgress = null
                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                refreshKey++
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = "应用", fontWeight = FontWeight.SemiBold)
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(
                            android.content.Intent(context, AppStoreActivity::class.java)
                        )
                    }) {
                        Icon(Icons.Outlined.Storefront, contentDescription = "应用商店")
                    }
                    IconButton(onClick = {
                        selectedPackages = emptySet()
                        pickingMode = false
                        showAddSheet = true
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加应用")
                    }
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ---- 常用 ----
            Text(
                text = "常用",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp)
            )
            Spacer(Modifier.height(10.dp))
            if (usageAccess && frequent.isNotEmpty()) {
                Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                    frequent.chunked(2).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { app ->
                                FrequentCard(
                                    app = app,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (AppCatalogStore.launch(context, app.packageName)) {
                                            refreshKey++
                                        } else {
                                            Toast.makeText(context, "${app.label} 没有可启动的界面", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                        .clickable { AppUsageRanker.openAccessSettings(context) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("按使用频次排序需要「使用情况访问」权限", fontSize = 13.sp)
                        Text(
                            "点此授予权限后，这里会显示最常用的 4 个应用",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // ---- 全部应用 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "全部应用",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${curated.size} 个",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                placeholder = { Text("搜索已添加的应用", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            Spacer(Modifier.height(8.dp))

            val visible = if (query.isBlank()) curated else curated.filter {
                it.label.contains(query, true) || it.packageName.contains(query, true)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 18.dp)
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(app = app, onClick = { detail = app })
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "应用页是空的？右上角「+」可以从已安装应用里挑，也可以直接选安装包静默安装。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        }
    }

    // ---- 添加面板 ----
    if (showAddSheet) {
        AddAppsSheet(
            installed = installed,
            selected = selectedPackages,
            picking = pickingMode,
            onPickingChange = { pickingMode = it },
            onToggle = { pkg ->
                selectedPackages = if (selectedPackages.contains(pkg)) {
                    selectedPackages - pkg
                } else {
                    selectedPackages + pkg
                }
            },
            onDismiss = { showAddSheet = false },
            onPickApk = { apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/zip", "application/octet-stream")) },
            onConfirm = {
                if (selectedPackages.isNotEmpty()) {
                    AppCatalogStore.add(context, selectedPackages)
                    refreshKey++
                    Toast.makeText(context, "已添加 ${selectedPackages.size} 个应用", Toast.LENGTH_SHORT).show()
                }
                showAddSheet = false
            }
        )
    }

    // ---- 详情 ----
    detail?.let { app ->
        AppDetailDialog(
            app = app,
            onDismiss = { detail = null },
            onOpen = {
                detail = null
                if (AppCatalogStore.launch(context, app.packageName)) refreshKey++
                else Toast.makeText(context, "启动失败", Toast.LENGTH_SHORT).show()
            },
            onInfo = {
                detail = null
                AppCatalogStore.openAppInfo(context, app.packageName)
            },
            onRemove = {
                detail = null
                AppCatalogStore.remove(context, app.packageName)
                refreshKey++
            },
            onInstall = {
                detail = null
                context.startActivity(android.content.Intent(context, AppStoreActivity::class.java))
            }
        )
    }

    // ---- 安装进度 ----
    installProgress?.let { (fraction, stage) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(installTitle) },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("$stage（${(fraction * 100).toInt()}%）", fontSize = 12.sp)
                    Text(
                        "通过 Shizuku 的 shell 权限静默安装，不经过系统安装器",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            },
            confirmButton = {}
        )
    }
}

@Composable
private fun FrequentCard(app: CatalogApp, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        app.info?.let { AppIconCache.getOrLoadBitmap(context, it, 0, 96) }
    }
    Row(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(1), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = app.label,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AppRow(app: CatalogApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName, app.installed) {
        app.info?.let { AppIconCache.getOrLoadBitmap(context, it, 0, 96) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(app.label.take(1), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, fontSize = 13.sp, maxLines = 1)
            Text(
                text = if (app.installed) {
                    app.packageName + (app.versionName?.let { " · $it" } ?: "")
                } else {
                    "未安装 · 点击去商店"
                },
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1
            )
        }
        if (app.packageName == AppCatalogStore.SCENE_PACKAGE) {
            Text(
                "配套",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAppsSheet(
    installed: List<CatalogApp>,
    selected: Set<String>,
    picking: Boolean,
    onPickingChange: (Boolean) -> Unit,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
    onPickApk: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("添加应用", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "可以把已安装的应用加到这里，也可以直接选安装包静默安装",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onPickingChange(true) }, modifier = Modifier.weight(1f)) {
                    Text("从已安装应用选", fontSize = 12.sp)
                }
                OutlinedButton(onClick = onPickApk, modifier = Modifier.weight(1f)) {
                    Text("选安装包安装", fontSize = 12.sp)
                }
            }

            if (picking) {
                Spacer(Modifier.height(10.dp))
                var filter by remember { mutableStateOf("") }
                val ctx = LocalContext.current
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall,
                    placeholder = { Text("搜索应用名或包名", fontSize = 12.sp) }
                )
                Spacer(Modifier.height(8.dp))
                val candidates = installed.filter {
                    !AppCatalogStore.isSaved(ctx, it.packageName) &&
                        (filter.isBlank() || it.label.contains(filter, true) ||
                            it.packageName.contains(filter, true))
                }
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(candidates, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(app.packageName) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = selected.contains(app.packageName),
                                onCheckedChange = { onToggle(app.packageName) }
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(app.label, fontSize = 13.sp, maxLines = 1)
                                Text(
                                    app.packageName,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("关闭") }
                if (picking) {
                    TextButton(onClick = onConfirm, enabled = selected.isNotEmpty()) {
                        Text("添加（${selected.size}）")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun AppDetailDialog(
    app: CatalogApp,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit,
    onInstall: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        app.info?.let { AppIconCache.getOrLoadBitmap(context, it, 0, 128) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.size(40.dp)
                )
            }
        },
        title = { Text(app.label) },
        text = {
            Column {
                Text(app.packageName, fontSize = 12.sp)
                if (app.versionName != null) Text("版本 ${app.versionName}", fontSize = 12.sp)
                if (!app.installed) Text("当前未安装", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (app.installed) {
                TextButton(onClick = onOpen) { Text("打开") }
            } else {
                TextButton(onClick = onInstall) { Text("去商店安装") }
            }
        },
        dismissButton = {
            Row {
                if (app.installed) {
                    TextButton(onClick = onInfo) { Text("应用信息") }
                    if (app.packageName != AppCatalogStore.SCENE_PACKAGE) {
                        TextButton(onClick = onRemove) { Text("移出列表") }
                    }
                } else {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    )
}
