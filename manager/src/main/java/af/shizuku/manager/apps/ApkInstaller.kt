package af.shizuku.manager.apps

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 不经过系统安装器的 APK 安装通道。
 *
 * 和 InstallerX-Revived 的 Shizuku 模式同一条路：把 APK 交给已以 shell(uid 2000) 运行的 Shizuku，
 * 由它执行 `pm install`。不弹系统安装确认框，也不需要 REQUEST_INSTALL_PACKAGES。
 *
 * 文件搬运：`cat > /data/local/tmp/...` 通过 Shizuku 进程 stdin 流式写入。app 私有目录 shell 读不到，
 * 而 /data/local/tmp 对 shell 可读写，装完立刻清理。
 *
 * 安装成功后把包名登记进「全部应用」——这是全部应用唯一的两个来源之一（另一个是商店）。
 */
object ApkInstaller {

    private const val REMOTE_DIR = "/data/local/tmp/shizukux_install"

    data class InstallOutcome(
        val success: Boolean,
        val message: String,
        val packageName: String? = null
    )

    fun privilegeAvailable(): Boolean = PrivilegedShell.available()

    suspend fun install(
        context: Context,
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): InstallOutcome = withContext(Dispatchers.IO) {
        if (!privilegeAvailable()) {
            return@withContext InstallOutcome(false, "Shizuku 服务未运行，也没有 root，无法静默安装")
        }
        val name = displayName(context, uri) ?: "package.apk"
        val declaredSize = displaySize(context, uri)
        val cacheDir = File(context.cacheDir, "install").apply { mkdirs() }
        val staged = File(cacheDir, sanitize(name))
        try {
            onProgress(0f, "读取安装包")
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    copyWithProgress(input, output, declaredSize, 0f, 0.45f, onProgress)
                }
            } ?: return@withContext InstallOutcome(false, "无法读取所选文件")
        } catch (e: Exception) {
            Timber.tag("ApkInstaller").w(e, "staging failed")
            return@withContext InstallOutcome(false, "读取安装包失败：${e.message}")
        }

        val outcome = try {
            installStaged(context, staged, cacheDir, onProgress)
        } catch (e: Exception) {
            InstallOutcome(false, "安装失败：${e.message}")
        }
        val isContainer = staged.name.endsWith(".apks", true) ||
            staged.name.endsWith(".xapk", true) ||
            staged.name.endsWith(".zip", true) ||
            staged.name.endsWith(".apkm", true)
        if (outcome.success && outcome.packageName != null && !isContainer) {
            keepCopy(context, staged, outcome.packageName)
        }
        runCatching { staged.delete() }
        outcome
    }

    suspend fun installFile(
        context: Context,
        file: File,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): InstallOutcome = withContext(Dispatchers.IO) {
        if (!privilegeAvailable()) {
            return@withContext InstallOutcome(false, "Shizuku 服务未运行，也没有 root，无法静默安装")
        }
        val cacheDir = File(context.cacheDir, "install").apply { mkdirs() }
        installStaged(context, file, cacheDir, onProgress)
    }

    private fun installStaged(
        context: Context,
        staged: File,
        cacheDir: File,
        onProgress: (Float, String) -> Unit
    ): InstallOutcome {
        val apkPackage = readPackageName(context, staged)
        val splitContainer = staged.name.endsWith(".apks", true) ||
            staged.name.endsWith(".xapk", true) ||
            staged.name.endsWith(".zip", true) ||
            staged.name.endsWith(".apkm", true)

        if (staged.name.endsWith(".apkm", true)) {
            return InstallOutcome(false, "APKM 是加密容器，暂不支持；请提供 APK / APKS")
        }

        val apks: List<File> = if (splitContainer) {
            try {
                extractApks(staged, cacheDir)
            } catch (e: Exception) {
                return InstallOutcome(false, "解包失败：${e.message}")
            }
        } else {
            listOf(staged)
        }

        if (apks.isEmpty()) return InstallOutcome(false, "压缩包里没有 APK")

        return try {
            pushAndInstall(context, apks, apkPackage, onProgress)
        } finally {
            if (splitContainer) apks.forEach { runCatching { it.delete() } }
        }
    }

    private fun pushAndInstall(
        context: Context,
        apks: List<File>,
        apkPackage: String?,
        onProgress: (Float, String) -> Unit
    ): InstallOutcome {
        PrivilegedShell.run("rm -rf $REMOTE_DIR; mkdir -p $REMOTE_DIR")
            ?: return InstallOutcome(false, "无法获得 shell 权限")

        val remotePaths = ArrayList<String>(apks.size)
        apks.forEachIndexed { index, file ->
            val remote = "$REMOTE_DIR/${index}_${sanitize(file.name)}"
            onProgress(0.45f + 0.35f * (index.toFloat() / apks.size), "传输 ${file.name}")
            val pushed = PrivilegedShell.push(file, remote) { written ->
                val perFile = (index.toFloat() + written.toFloat() / file.length().coerceAtLeast(1)) /
                    apks.size
                onProgress(0.45f + 0.35f * perFile, "传输 ${file.name}")
            }
            if (!pushed) {
                PrivilegedShell.run("rm -rf $REMOTE_DIR")
                return InstallOutcome(false, "传输失败：${file.name}")
            }
            remotePaths.add(remote)
        }

        onProgress(0.82f, "安装中")
        val quoted = remotePaths.joinToString(" ") { "'$it'" }
        val command = if (remotePaths.size == 1) {
            "pm install -r -d $quoted 2>&1; echo EXIT:$?; rm -rf $REMOTE_DIR"
        } else {
            "pm install-multiple -r -d $quoted 2>&1; echo EXIT:$?; rm -rf $REMOTE_DIR"
        }
        val output = PrivilegedShell.run(command) ?: return InstallOutcome(false, "安装命令执行失败")
        onProgress(1f, "完成")

        val success = output.contains("Success") || output.contains("EXIT:0")
        val failureLine = output.lineSequence()
            .firstOrNull { it.contains("Failure") || it.contains("INSTALL_FAILED") || it.contains("Error") }
            ?.trim()

        val pkg = apkPackage ?: successPackage(output)
        if (success && pkg != null) {
            AppCatalogStore.addManaged(context, pkg)
        }
        return InstallOutcome(
            success = success,
            message = if (success) "安装成功" else (failureLine ?: output.trim().takeLast(200)),
            packageName = pkg
        )
    }

    fun uninstall(packageName: String, keepData: Boolean = false): InstallOutcome {
        if (!privilegeAvailable()) return InstallOutcome(false, "Shizuku 服务未运行，也没有 root")
        val flags = if (keepData) "-k " else ""
        val output = PrivilegedShell.run("pm uninstall $flags--user 0 '$packageName' 2>&1; echo EXIT:$?")
            ?: return InstallOutcome(false, "卸载命令执行失败")
        val success = output.contains("Success") || output.contains("EXIT:0")
        return InstallOutcome(success, if (success) "已卸载 $packageName" else output.trim().takeLast(200), packageName)
    }

    // ---------------------------------------------------------------- helpers

    /** 装成功后留一份 APK，供「全部应用」里的「重新安装」直接用，不必再让用户选文件。 */
    private fun keepCopy(context: Context, source: File, packageName: String) {
        try {
            val dir = File(context.filesDir, "apks").apply { mkdirs() }
            source.copyTo(File(dir, "$packageName.apk"), overwrite = true)
        } catch (e: Exception) {
            Timber.tag("ApkInstaller").w(e, "keepCopy failed")
        }
    }

    fun storedApk(context: Context, packageName: String): File? =
        File(File(context.filesDir, "apks"), "$packageName.apk")
            .takeIf { it.isFile && it.length() > 0 }

    private fun successPackage(output: String): String? =
        Regex("""Package\s+([A-Za-z0-9_.]+)\s+installed""").find(output)?.groupValues?.getOrNull(1)

    private fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        totalBytes: Long?,
        from: Float,
        to: Float,
        onProgress: (Float, String) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            written += read
            val fraction = totalBytes?.takeIf { it > 0 }?.let {
                from + (to - from) * (written.toFloat() / it)
            } ?: from
            onProgress(fraction.coerceIn(from, to), "读取安装包")
        }
    }

    private fun displaySize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                cursor.getLong(index)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun extractApks(container: File, cacheDir: File): List<File> {
        val outDir = File(cacheDir, "split_${System.nanoTime()}").apply { mkdirs() }
        val result = ArrayList<File>()
        ZipInputStream(container.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                if (!entry.name.lowercase().endsWith(".apk")) continue
                if (result.size >= 32) break
                val target = File(outDir, "part_${result.size}.apk")
                target.outputStream().use { output -> zip.copyTo(output) }
                result.add(target)
            }
        }
        return result
    }

    private fun readPackageName(context: Context, file: File): String? {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
            info?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96).ifBlank { "package.apk" }
}
