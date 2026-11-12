package af.shizuku.manager.apps

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppStoreScreen(
    state: AppStoreUiState,
    uploading: Boolean,
    uploadingName: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onInstall: (AppStoreEntry) -> Unit,
    onUpload: () -> Unit,
    onOpenRepo: () -> Unit
) {
    var detail by remember { mutableStateOf<AppStoreEntry?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("应用商店", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onUpload) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = "上传应用")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (uploading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("$uploadingName…", fontSize = 12.sp)
                }
            }

            if (state.error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.error ?: "",
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onRefresh) { Text("重试") }
                }
            }

            if (state.loading && state.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                    item {
                        Text(
                            text = "索引来自 qianyumeng0228/shizukux-apps" +
                                if (state.sourceHost.isNotBlank()) " · ${state.sourceHost}" else "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    items(state.entries, key = { it.id }) { entry ->
                        StoreRow(
                            entry = entry,
                            installed = state.isInstalled(entry),
                            hasUpdate = state.hasUpdate(entry),
                            busy = state.downloadingId == entry.id || state.installingId == entry.id,
                            progress = if (state.downloadingId == entry.id || state.installingId == entry.id) {
                                state.progress
                            } else null,
                            onClick = { detail = entry },
                            onInstall = { onInstall(entry) }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenRepo)
                                .padding(vertical = 16.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "没有你想要的应用？点这里去仓库提交",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    detail?.let { entry ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(entry.localizedName(true)) },
            text = {
                Column {
                    Text("包名 ${entry.packageName}", fontSize = 12.sp)
                    entry.versionName?.let { Text("版本 $it", fontSize = 12.sp) }
                    entry.formattedSize?.let { Text("大小 $it", fontSize = 12.sp) }
                    entry.author?.let { Text("作者 $it", fontSize = 12.sp) }
                    entry.localizedDescription(true)?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 12.sp)
                    }
                    entry.sha256?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("SHA-256 ${it.take(16)}…", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        detail = null
                        onInstall(entry)
                    },
                    enabled = state.downloadingId == null && state.installingId == null
                ) {
                    Text(
                        when {
                            state.hasUpdate(entry) -> "更新"
                            state.isInstalled(entry) -> "重新安装"
                            else -> "安装"
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { detail = null }) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun StoreRow(
    entry: AppStoreEntry,
    installed: Boolean,
    hasUpdate: Boolean,
    busy: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onInstall: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    entry.localizedName(true).take(1),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.localizedName(true), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(
                    buildString {
                        append(entry.packageName)
                        entry.versionName?.let { append(" · $it") }
                        entry.formattedSize?.let { append(" · $it") }
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1
                )
                if (entry.tags.isNotEmpty()) {
                    Text(
                        entry.tags.joinToString(" · "),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onInstall, enabled = !busy) {
                Text(
                    when {
                        busy -> "处理中"
                        hasUpdate -> "更新"
                        installed -> "重装"
                        else -> "安装"
                    },
                    fontSize = 12.sp
                )
            }
        }
        if (busy && progress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
        }
    }
}
