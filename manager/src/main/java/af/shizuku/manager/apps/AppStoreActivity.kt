package af.shizuku.manager.apps

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.app.ThemeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 应用商店。
 *
 * 数据来自 `qianyumeng0228/shizukux-apps` 的 `apps.json`，安装走 [ApkInstaller] 的 Shizuku 静默通道，
 * 和模块商店共用同一套镜像回退思路（gh-proxy → jsDelivr → raw → github）。
 */
class AppStoreActivity : ComponentActivity() {

    private var state by mutableStateOf(AppStoreUiState())
    private var uploading by mutableStateOf(false)
    private var uploadingName by mutableStateOf("")

    private val apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            uploading = true
            uploadingName = "读取安装包"
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { describeApk(uri) }
                uploading = false
                if (info == null) {
                    Toast.makeText(this@AppStoreActivity, "读不到这个安装包的包名/版本信息", Toast.LENGTH_LONG).show()
                } else {
                    openSubmission(info)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            af.shizuku.core.ui.compose.AppTheme(
                darkTheme = isSystemInDarkTheme(),
                isBlackNightTheme = ThemeHelper.isBlackNightTheme(this),
                isOneUi = ShizukuSettings.isOneUiThemeEnabled(),
                forceGlass = ShizukuSettings.isGlassUi()
            ) {
                AppStoreScreen(
                    state = state,
                    uploading = uploading,
                    uploadingName = uploadingName,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onRefresh = { refresh() },
                    onInstall = { entry -> install(entry) },
                    onUpload = {
                        apkPicker.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream"
                            )
                        )
                    },
                    onOpenRepo = {
                        runCatching {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(AppStoreClient.REPO_URL))
                            )
                        }
                    }
                )
            }
        }
        refresh()

        @Suppress("DEPRECATION")
        overridePendingTransition(af.shizuku.manager.R.anim.glass_tab_in_right, af.shizuku.manager.R.anim.glass_tab_out_left)
    }

    private fun refresh() {
        if (state.loading) return
        state = state.copy(loading = true, error = null)
        lifecycleScope.launch {
            try {
                val fetched = AppStoreClient.fetchIndex()
                val installed = installedVersions(fetched.index.apps.map { it.packageName })
                state = state.copy(
                    loading = false,
                    entries = fetched.index.apps,
                    installedVersions = installed,
                    sourceHost = fetched.source.host,
                    loaded = true
                )
            } catch (e: Exception) {
                state = state.copy(loading = false, error = e.message ?: "加载失败")
            }
        }
    }

    private fun installedVersions(packages: List<String>): Map<String, Long> {
        val pm = packageManager
        return packages.mapNotNull { pkg ->
            val info = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(pkg, 0)
                }
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            pkg to code
        }.toMap()
    }

    private fun install(entry: AppStoreEntry) {
        if (state.downloadingId != null || state.installingId != null) {
            Toast.makeText(this, "还有一项在下载/安装，等等它", Toast.LENGTH_SHORT).show()
            return
        }
        state = state.copy(downloadingId = entry.id, progress = 0f, error = null)
        lifecycleScope.launch {
            try {
                val file = AppStoreClient.download(this@AppStoreActivity, entry) { progress ->
                    state = state.copy(progress = progress)
                }
                state = state.copy(downloadingId = null, installingId = entry.id, progress = 0f)
                val outcome = ApkInstaller.installFile(this@AppStoreActivity, file) { fraction, _ ->
                    state = state.copy(progress = fraction)
                }
                state = state.copy(
                    installingId = null,
                    progress = null,
                    installedVersions = state.installedVersions + installedVersions(listOf(entry.packageName))
                )
                Toast.makeText(
                    this@AppStoreActivity,
                    if (outcome.success) "${entry.localizedName(true)} 安装成功" else outcome.message,
                    Toast.LENGTH_LONG
                ).show()
                runCatching { file.delete() }
            } catch (e: Exception) {
                state = state.copy(downloadingId = null, installingId = null, progress = null, error = e.message)
                Toast.makeText(this@AppStoreActivity, e.message ?: "安装失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------------------------------------------------------------- upload

    private data class ApkMeta(
        val name: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val sizeBytes: Long,
        val sha256: String,
        val fileName: String
    )

    private fun describeApk(uri: Uri): ApkMeta? {
        val cache = File(cacheDir, "upload").apply { mkdirs() }
        val temp = File(cache, "upload.apk")
        val name = queryDisplayName(uri) ?: "upload.apk"
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getPackageArchiveInfo(
                    temp.absolutePath,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(temp.absolutePath, 0)
            } ?: return null
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            ApkMeta(
                name = info.applicationInfo?.loadLabel(packageManager)?.toString() ?: info.packageName,
                packageName = info.packageName,
                versionName = info.versionName ?: "",
                versionCode = code,
                sizeBytes = temp.length(),
                sha256 = sha256(temp),
                fileName = name
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openSubmission(meta: ApkMeta) {
        val json = """
            {
              "id": "${meta.packageName}",
              "name": "${meta.name}",
              "nameZh": "${meta.name}",
              "packageName": "${meta.packageName}",
              "versionName": "${meta.versionName}",
              "versionCode": ${meta.versionCode},
              "downloadUrl": "https://github.com/<你的仓库>/releases/download/<tag>/${meta.fileName}",
              "sizeBytes": ${meta.sizeBytes},
              "sha256": "${meta.sha256}",
              "minSdk": 24,
              "tags": [],
              "sourceRepo": "https://github.com/<你的仓库>"
            }
        """.trimIndent()

        val body = buildString {
            appendLine("### 应用名称")
            appendLine(meta.name)
            appendLine()
            appendLine("### 包名")
            appendLine(meta.packageName)
            appendLine()
            appendLine("### 版本名")
            appendLine(meta.versionName)
            appendLine()
            appendLine("### 版本号（versionCode）")
            appendLine(meta.versionCode)
            appendLine()
            appendLine("### 下载直链（GitHub Release 资产）")
            appendLine("https://github.com/你/repo/releases/download/…/${meta.fileName}")
            appendLine()
            appendLine("### SHA-256")
            appendLine(meta.sha256)
            appendLine()
            appendLine("### 文件大小（字节）")
            appendLine(meta.sizeBytes)
            appendLine()
            appendLine("### 应用简介")
            appendLine("（一句话说明它做什么、依赖什么）")
            appendLine()
            appendLine("### 标签（逗号分隔）")
            appendLine("")
            appendLine()
            appendLine("### 开源仓库（可选）")
            appendLine("")
            appendLine()
            appendLine("### 确认")
            appendLine("- [X] 我确认该 APK 来源合法，且直链可公开下载")
            appendLine()
            appendLine("---")
            appendLine("由 ShizukuX 应用商店自动生成，可直接作为 apps.json 条目：")
            appendLine("```json")
            appendLine(json)
            appendLine("```")
        }

        val url = AppStoreClient.SUBMIT_URL +
            "?title=" + URLEncoder.encode("[App] ${meta.name} ${meta.versionName}", "UTF-8") +
            "&labels=" + URLEncoder.encode("app-submission", "UTF-8") +
            "&body=" + URLEncoder.encode(body, "UTF-8")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "没有可用的浏览器，条目已复制到剪贴板", Toast.LENGTH_LONG).show()
            copyToClipboard(json)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("apps.json entry", text))
    }
}
