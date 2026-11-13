package af.shizuku.manager.apps

import android.content.Intent
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import af.shizuku.manager.home.compose.GlassTabStrip
import af.shizuku.manager.utils.AppIconCache
import af.shizuku.manager.utils.GlassTabs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「应用」页。
 *
 * 结构：搜索 → 常用（固定 4 格，自己钉的，空位是添加按钮）→ 全部应用（星野scene + 商店/安装器装的包）。
 * 常用长按图标可拖动换位，原地长按则是移除；全部应用长按是卸载菜单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsPage(onSelectTab: (Int) -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var favorites by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var managed by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    var refreshKey by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<CatalogApp?>(null) }
    var removeFavoriteTarget by remember { mutableStateOf<CatalogApp?>(null) }
    var uninstallTarget by remember { mutableStateOf<CatalogApp?>(null) }
    var showFavoritePicker by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<Pair<Float, String>?>(null) }
    var busyText by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            val fav = AppCatalogStore.favorites(context).map { AppCatalogStore.resolve(context, it) }
            val mng = AppCatalogStore.managedPackages(context).map { AppCatalogStore.resolve(context, it) }
            withContext(Dispatchers.Main) {
                favorites = fav.filterNotNull()
                managed = mng.filterNotNull()
            }
        }
    }

    LaunchedEffect(refreshKey) { reload() }

    // 从详情 / 设置二级页回来时，安装状态和「已隐藏」标记可能已变，回来就重读一次
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
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

    fun uninstall(app: CatalogApp, removeFromList: Boolean) {
        uninstallTarget = null
        busyText = "正在卸载 ${app.label}"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                if (app.iconHidden && app.info == null) {
                    // 已隐藏且包确实不在当前用户（卸载式隐藏/DO 隐藏）：这里不再调 pm，只处理列表与留存状态
                    AppCatalogStore.clearIconHidden(context, app.packageName)
                    if (removeFromList) ApkInstaller.storedApk(context, app.packageName)?.delete()
                    ApkInstaller.InstallOutcome(
                        true,
                        if (removeFromList) "已移除 ${app.label}" else "已隐藏，手机里没有该应用",
                        app.packageName
                    )
                } else {
                    // 组件式隐藏时包还在（只是没有桌面入口），先清掉隐藏记录再正常卸载
                    if (app.iconHidden) AppCatalogStore.clearIconHidden(context, app.packageName)
                    // 「仅卸载应用」保留数据，之后「重新安装」能接回原数据；「卸载」连数据一起清
                    ApkInstaller.uninstall(app.packageName, keepData = !removeFromList)
                }
            }
            busyText = null
            if (outcome.success) {
                if (removeFromList) {
                    AppCatalogStore.removeManaged(context, app.packageName)
                    AppCatalogStore.clearIconHidden(context, app.packageName)
                    AppCatalogStore.removeFavorite(context, app.packageName)
                }
                refreshKey++
            }
            Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
        }
    }

    fun reinstall(app: CatalogApp) {
        uninstallTarget = null
        val apk = ApkInstaller.storedApk(context, app.packageName)
        if (apk == null) {
            Toast.makeText(context, "没有存留的安装包，去应用商店重新安装", Toast.LENGTH_LONG).show()
            context.startActivity(Intent(context, AppStoreActivity::class.java))
            return
        }
        installProgress = 0f to "准备安装"
        scope.launch {
            val outcome = ApkInstaller.installFile(context, apk) { fraction, stage ->
                installProgress = fraction to stage
            }
            installProgress = null
            Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            refreshKey++
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(text = "应用", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(context, AppStoreActivity::class.java))
                    }) {
                        Icon(Icons.Outlined.Storefront, contentDescription = "应用商店")
                    }
                    IconButton(onClick = {
                        if (!ApkInstaller.privilegeAvailable()) {
                            Toast.makeText(context, "Shizuku 服务未运行，无法静默安装", Toast.LENGTH_LONG).show()
                        } else {
                            apkPicker.launch(
                                arrayOf(
                                    "application/vnd.android.package-archive",
                                    "application/zip",
                                    "application/octet-stream"
                                )
                            )
                        }
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "安装安装包")
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
            // ---- 搜索（移到常用上方）----
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                placeholder = { Text("搜索应用", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None)
            )
            Spacer(Modifier.height(12.dp))

            if (query.isBlank()) {
                // ---- 常用 ----
                Text(
                    text = "常用",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
                Spacer(Modifier.height(10.dp))
                FavoriteGrid(
                    favorites = favorites,
                    canAdd = favorites.size < AppCatalogStore.MAX_FAVORITES,
                    onOpen = { detail = it },
                    onRemove = { removeFavoriteTarget = it },
                    onAddClick = { showFavoritePicker = true },
                    onSwap = { from, to ->
                        AppCatalogStore.moveFavorite(context, from, to)
                        refreshKey++
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

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
                    text = "${managed.size} 个",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(8.dp))

            val visible = if (query.isBlank()) managed else managed.filter {
                it.label.contains(query, true) || it.packageName.contains(query, true)
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 18.dp)
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        onClick = { detail = app },
                        onLongClick = { uninstallTarget = app }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "全部应用只收「应用商店」装的和「右上角 +」装的包；没装过任何东西时这里只有星野scene。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 24.dp)
                    )
                }
            }
        }
    }

    // ---- 常用选择器 ----
    if (showFavoritePicker) {
        FavoritePickerSheet(
            onDismiss = { showFavoritePicker = false },
            onPick = { pkg ->
                if (!AppCatalogStore.addFavorite(context, pkg)) {
                    Toast.makeText(context, "常用已满 4 个", Toast.LENGTH_SHORT).show()
                }
                showFavoritePicker = false
                refreshKey++
            }
        )
    }

    // ---- 移除常用确认 ----
    removeFavoriteTarget?.let { app ->
        AlertDialog(
            onDismissRequest = { removeFavoriteTarget = null },
            title = { Text("移除常用") },
            text = { Text("把「${app.label}」从常用里移出？应用本身不会被卸载。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    AppCatalogStore.removeFavorite(context, app.packageName)
                    removeFavoriteTarget = null
                    refreshKey++
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removeFavoriteTarget = null }) { Text("取消") }
            }
        )
    }

    // ---- 卸载菜单 ----
    uninstallTarget?.let { app ->
        UninstallDialog(
            app = app,
            onDismiss = { uninstallTarget = null },
            onRemoveFromList = {
                if (!app.installed) {
                    if (app.packageName == AppCatalogStore.SCENE_PACKAGE) {
                        Toast.makeText(context, "星野scene 是配套包，不能从这里移除", Toast.LENGTH_SHORT).show()
                        uninstallTarget = null
                    } else {
                        AppCatalogStore.removeManaged(context, app.packageName)
                        uninstallTarget = null
                        refreshKey++
                    }
                } else {
                    uninstall(app, removeFromList = true)
                }
            },
            onKeepInList = {
                if (!app.installed) {
                    reinstall(app)
                } else {
                    uninstall(app, removeFromList = false)
                }
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
            onSettings = {
                detail = null
                context.startActivity(
                    Intent(context, AppSettingsActivity::class.java)
                        .putExtra(AppSettingsActivity.EXTRA_PACKAGE, app.packageName)
                )
            },
            onInstall = {
                detail = null
                context.startActivity(Intent(context, AppStoreActivity::class.java))
            }
        )
    }

    // ---- 进度 ----
    installProgress?.let { (fraction, stage) ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("安装中") },
            text = {
                Column {
                    LinearProgressIndicator(progress = fraction, modifier = Modifier.fillMaxWidth())
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

    busyText?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = null,
            text = { Text(text, fontSize = 13.sp) },
            confirmButton = {}
        )
    }
}

/** 2×2 常用网格；空位依次放「添加」，已满 4 个则不再出现添加按钮。 */
@Composable
private fun FavoriteGrid(
    favorites: List<CatalogApp>,
    canAdd: Boolean,
    onOpen: (CatalogApp) -> Unit,
    onRemove: (CatalogApp) -> Unit,
    onAddClick: () -> Unit,
    onSwap: (Int, Int) -> Unit
) {
    val bounds = remember { HashMap<Int, Rect>() }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
        val counts = favorites.size + if (canAdd) 1 else 0
        val rows = (counts + 1) / 2
        (0 until rows).forEach { r ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                (0 until 2).forEach { c ->
                    val index = r * 2 + c
                    Box(modifier = Modifier.weight(1f)) {
                        when {
                            index < favorites.size -> {
                                val app = favorites[index]
                                FavoriteTile(
                                    app = app,
                                    dragOffset = if (draggingIndex == index) dragOffset else Offset.Zero,
                                    dragging = draggingIndex == index,
                                    onClick = { onOpen(app) },
                                    onRemove = { onRemove(app) },
                                    onDragStart = { draggingIndex = index },
                                    onDrag = { dragOffset += it },
                                    onDragEnd = { movedFar ->
                                        if (movedFar) {
                                            val center = bounds[index]?.center ?: Offset.Zero
                                            val drop = center + dragOffset
                                            val target = bounds.entries.firstOrNull { (i, rect) ->
                                                i != index && rect.contains(drop)
                                            }?.key
                                            if (target != null) onSwap(index, target)
                                        }
                                        draggingIndex = null
                                        dragOffset = Offset.Zero
                                    },
                                    onBounds = { bounds[index] = it }
                                )
                            }
                            index == favorites.size && canAdd -> AddTile(onClick = onAddClick)
                            else -> Unit
                        }
                    }
                }
            }
            if (r != rows - 1) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun FavoriteTile(
    app: CatalogApp,
    dragOffset: Offset,
    dragging: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    onBounds: (Rect) -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) {
        app.info?.let { AppIconCache.getOrLoadBitmap(context, it, 0, 96) }
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .height(64.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .graphicsLayer {
                translationX = dragOffset.x
                translationY = dragOffset.y
                alpha = if (dragging) 0.85f else 1f
                scaleX = if (dragging) 1.04f else 1f
                scaleY = if (dragging) 1.04f else 1f
            }
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .pointerInput(app.packageName) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startMs = SystemClock.uptimeMillis()
                    val downPos = down.position
                    var dragStarted = false
                    var movedFar = false
                    var released = false
                    while (!released) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val elapsed = SystemClock.uptimeMillis() - startMs
                        if (change.pressed) {
                            if (!dragStarted && elapsed >= viewConfiguration.longPressTimeoutMillis) {
                                dragStarted = true
                                onDragStart()
                            }
                            if (dragStarted) {
                                onDrag(change.position - change.previousPosition)
                                if ((change.position - downPos).getDistance() > viewConfiguration.touchSlop) {
                                    movedFar = true
                                }
                                change.consume()
                            }
                        } else {
                            released = true
                            when {
                                elapsed < viewConfiguration.longPressTimeoutMillis -> onClick()
                                !dragStarted -> onRemove()
                                movedFar -> onDragEnd(true)
                                else -> {
                                    onDragEnd(false)
                                    onRemove()
                                }
                            }
                        }
                    }
                }
            }
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
        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, fontSize = 13.sp, maxLines = 1)
            if (!app.installed) {
                Text("未安装", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
            } else if (app.iconHidden) {
                Text("已隐藏", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .height(64.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "添加常用", modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text("添加常用", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}

@Composable
private fun AppRow(app: CatalogApp, onClick: () -> Unit, onLongClick: () -> Unit) {
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
            .pointerInput(app.packageName, app.installed) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
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
                text = when {
                    app.iconHidden -> app.packageName + " · 已隐藏桌面图标"
                    app.installed -> app.packageName + (app.versionName?.let { " · $it" } ?: "")
                    else -> "未安装 · 点击查看"
                },
                fontSize = 10.sp,
                color = if (app.installed) {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                } else {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                },
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
private fun FavoritePickerSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<CatalogApp>>(emptyList()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            apps = AppCatalogStore.installedApps(context).filter {
                AppCatalogStore.launcherComponents(context, it.packageName).isNotEmpty()
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
            Text("添加到常用", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "最多 4 个；长按常用图标可以移除或拖动换位",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("搜索应用名或包名", fontSize = 12.sp) }
            )
            Spacer(Modifier.height(8.dp))
            val candidates = apps.filter {
                !AppCatalogStore.isFavorite(context, it.packageName) &&
                    (filter.isBlank() || it.label.contains(filter, true) ||
                        it.packageName.contains(filter, true))
            }
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                items(candidates, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(app.packageName) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bmp = remember(app.packageName) {
                            app.info?.let { AppIconCache.getOrLoadBitmap(context, it, 0, 72) }
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                            )
                        } else {
                            Spacer(Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(10.dp))
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
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun UninstallDialog(
    app: CatalogApp,
    onDismiss: () -> Unit,
    onRemoveFromList: () -> Unit,
    onKeepInList: () -> Unit
) {
    val installed = app.installed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (installed) "卸载 ${app.label}" else "${app.label} 未安装") },
        text = {
            Column {
                Text(
                    if (installed) {
                        "「卸载」会从手机卸载并把它从全部应用移除；「仅卸载应用」只卸载，列表保留一行未安装。"
                    } else {
                        "它已经不在手机里了。可以选择重新安装，或者把这个空位从列表移除。"
                    },
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("取消", fontSize = 13.sp)
                }
                if (installed) {
                    TextButton(onClick = onRemoveFromList, modifier = Modifier.fillMaxWidth()) {
                        Text("卸载", fontSize = 13.sp)
                    }
                    TextButton(onClick = onKeepInList, modifier = Modifier.fillMaxWidth()) {
                        Text("仅卸载应用", fontSize = 13.sp)
                    }
                } else {
                    TextButton(onClick = onKeepInList, modifier = Modifier.fillMaxWidth()) {
                        Text("重新安装", fontSize = 13.sp)
                    }
                    TextButton(onClick = onRemoveFromList, modifier = Modifier.fillMaxWidth()) {
                        Text("移除显示", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun AppDetailDialog(
    app: CatalogApp,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
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
                if (!app.installed) {
                    Text("当前未安装", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Row {
                if (app.installed) {
                    TextButton(onClick = onInfo) { Text("应用信息") }
                    TextButton(onClick = onSettings) { Text("设置") }
                    TextButton(onClick = onOpen) { Text("打开") }
                } else {
                    TextButton(onClick = onInstall) { Text("去商店安装") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
