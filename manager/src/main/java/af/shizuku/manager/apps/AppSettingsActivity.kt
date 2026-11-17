package af.shizuku.manager.apps

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import af.shizuku.manager.hiding.HidingWatchService
import af.shizuku.manager.utils.AppIconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个应用的二级设置页。目前只有一项：隐藏桌面图标。
 *
 * 路径一（本应用是 Device Owner）：`DevicePolicyManager.setApplicationHidden`，不卸载、不动数据，
 * 恢复瞬时完成。
 * 路径二（无 DO）：Android 13+ 的 shell 改不了组件状态（`CHANGE_COMPONENT_ENABLED_STATE` 是
 * `signature|privileged|role`，shell 对 targetSdk ≥ 33 的应用被显式拒绝），`pm hide` 又要
 * MANAGE_USERS，因此走「从当前用户卸载、`-k` 保留数据」：包从 user 0 摘掉后桌面图标消失，
 * 数据目录保留；关闭开关时用安装时留存的 APK 装回。两条路径的文案不同，避免误导。
 */
class AppSettingsActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }

    private var hidden by mutableStateOf(false)
    private var working by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var errorTitle by mutableStateOf("操作失败")

    /** Android 16 的 per-app 方向覆盖；低于该版本系统没有这个 compat change。 */
    private val rotationSupported = RotationOverride.isSupported()
    private var rotationMode by mutableStateOf(RotationMode.OFF)
    private var rotationWorking by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE)
        if (packageName.isNullOrBlank()) {
            finish()
            return
        }
        hidden = AppCatalogStore.isIconHidden(this, packageName)
        if (rotationSupported) {
            lifecycleScope.launch {
                rotationMode = withContext(Dispatchers.IO) {
                    runCatching { RotationOverride.modeFor(packageName) }
                        .getOrDefault(RotationMode.OFF)
                }
            }
        }

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
                    rotationMode = rotationMode,
                    rotationSupported = rotationSupported,
                    rotationWorking = rotationWorking,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onSelectMode = { mode ->
                        if (rotationWorking || mode == rotationMode) return@AppSettingsScreen
                        rotationWorking = true
                        lifecycleScope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { RotationOverride.setMode(packageName, mode) }
                            }
                            rotationWorking = false
                            result.onSuccess { applied ->
                                if (applied) {
                                    rotationMode = mode
                                    // A forced mode is held by the watch, which reads the foreground
                                    // every pass: with no watch running nothing would ever lock.
                                    runCatching {
                                        HidingWatchService.refresh(this@AppSettingsActivity)
                                    }
                                } else {
                                    errorTitle = "无法设置屏幕方向"
                                    errorMessage = "系统没有接受这次改动"
                                }
                            }.onFailure {
                                errorTitle = "无法设置屏幕方向"
                                errorMessage = it.message ?: "操作失败"
                            }
                        }
                    },
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
                        title = { Text(errorTitle) },
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
    rotationMode: RotationMode,
    rotationSupported: Boolean,
    rotationWorking: Boolean,
    onBack: () -> Unit,
    onSelectMode: (RotationMode) -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val hiddenMode = androidx.compose.runtime.remember(packageName, hidden) {
        AppCatalogStore.hiddenMode(context, packageName)
    }
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
                            when (hiddenMode) {
                                "component" -> "已隐藏桌面图标，应用与后台服务照常运行；关闭开关即恢复"
                                "do" -> "已隐藏：包仍安装、数据未动，但系统视作未安装，隐藏期间不能启动"
                                else -> "已从当前用户卸载，桌面不再显示图标；数据保留，关闭开关会装回来"
                            }
                        } else {
                            "开启后只移除桌面图标；应用仍在运行、数据不动"
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

            Spacer(Modifier.height(10.dp))
            var modeDialog by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                    .clickable(enabled = rotationSupported && !rotationWorking) { modeDialog = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("屏幕方向", fontSize = 14.sp)
                    Text(
                        if (!rotationSupported) {
                            "需要 Android 16 及以上；这个功能依赖系统的方向覆盖"
                        } else {
                            modeNote(rotationMode) + if (rotationWorking) "（处理中）" else ""
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Text(
                    modeLabel(rotationMode),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (modeDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { modeDialog = false },
                    title = { Text("屏幕方向") },
                    text = {
                        Column {
                            RotationMode.values().forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            modeDialog = false
                                            onSelectMode(mode)
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = rotationMode == mode,
                                        onClick = {
                                            modeDialog = false
                                            onSelectMode(mode)
                                        }
                                    )
                                    Column {
                                        Text(modeLabel(mode), fontSize = 14.sp)
                                        Text(
                                            modeNote(mode),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                        )
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { modeDialog = false }) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

private fun modeLabel(mode: RotationMode): String = when (mode) {
    RotationMode.OFF -> "关闭"
    RotationMode.AUTO -> "跟随系统"
    RotationMode.PORTRAIT -> "强制竖屏"
    RotationMode.LANDSCAPE -> "强制横屏"
}

private fun modeNote(mode: RotationMode): String = when (mode) {
    RotationMode.OFF -> "不处理这个应用的方向"
    RotationMode.AUTO -> "应用自己锁定的横屏/竖屏会被忽略，跟随系统旋转"
    RotationMode.PORTRAIT -> "这个应用在前台时，整块屏幕锁成竖屏"
    RotationMode.LANDSCAPE -> "这个应用在前台时，整块屏幕锁成横屏"
}
