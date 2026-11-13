package af.shizuku.manager.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.utils.AppIconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个应用的二级设置页。目前只有一项：隐藏桌面图标。
 *
 * 无 root、无 Device Owner 时，Android 13+ 的 shell 改不了组件状态（SecurityException），
 * `pm hide` 又要 MANAGE_USERS，因此走「从当前用户卸载、`-k` 保留数据」：
 * 包从 user 0 摘掉后桌面图标消失，应用数据目录保留；关闭开关时用安装时留存的 APK 装回，
 * 数据继续复用。这一机制意味着隐藏期间应用本身不可启动，界面文案已如实写明。
 */
class AppSettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }

    private var hidden by mutableStateOf(false)
    private var working by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }
        hidden = AppCatalogStore.isIconHidden(this, packageName)

        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                AppSettingsScreen(
                    packageName = packageName,
                    hidden = hidden,
                    working = working,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onToggle = { want ->
                        if (working) return@AppSettingsScreen
                        working = true
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                AppCatalogStore.setIconHidden(this@AppSettingsActivity, packageName, want)
                            }
                            working = false
                            result.onSuccess {
                                hidden = want
                            }.onFailure {
                                errorMessage = it.message ?: "操作失败"
                            }
                        }
                    }
                )
                errorMessage?.let { message ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { errorMessage = null },
                        title = { Text("无法切换桌面图标") },
                        text = { Text(message, fontSize = 12.sp) },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = { errorMessage = null }) {
                                Text("知道了")
                            }
                        }
                    )
                }
            }
        }

        @Suppress("DEPRECATION")
        overridePendingTransition(af.shizuku.manager.R.anim.glass_tab_in_right, af.shizuku.manager.R.anim.glass_tab_out_left)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettingsScreen(
    packageName: String,
    hidden: Boolean,
    working: Boolean,
    onBack: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val app = androidx.compose.runtime.remember(packageName) {
        AppCatalogStore.resolve(context, packageName)
    }
    val bitmap = androidx.compose.runtime.remember(packageName) {
        app?.info?.let { AppIconCache.getOrLoadBitmap(context, it, 0, 128) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = { Text("设置", fontWeight = FontWeight.SemiBold) },
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
                .padding(horizontal = 18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = app?.label,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text((app?.label ?: "?").take(1), fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(app?.label ?: packageName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(
                        packageName + (app?.versionName?.let { " · $it" } ?: ""),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("隐藏桌面图标", fontSize = 14.sp)
                    Text(
                        if (hidden) {
                            "已从当前用户卸载，桌面不再显示图标；数据保留，关闭开关会装回来"
                        } else {
                            "开启后会从当前用户卸载该应用以移除桌面图标，数据保留"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Switch(
                    checked = hidden,
                    onCheckedChange = onToggle,
                    enabled = !working
                )
            }
        }
    }
}
