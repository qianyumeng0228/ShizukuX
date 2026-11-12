package af.shizuku.manager.apps

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 不经过系统安装器的 APK 安装通道。
 *
 * 走的是和 InstallerX-Revived 的 Shizuku 模式同一条路：把 APK 交给已经以 shell(uid 2000) 身份运行的
 * Shizuku 服务，由它执行 `pm install`。区别在于这里不弹系统安装确认框，也不依赖
 * REQUEST_INSTALL_PACKAGES 的"未知来源"授权——shell 本身就有 INSTALL_PACKAGES。
 *
 * 为什么不用 PackageInstaller session：那套 API 仍然要经过系统的安装确认 UI（IntentSender），
 * 与"给 ShizukuX 安装器能力、不弹系统安装器"的目标相反。
 *
 * 文件搬运方式：`cat > /data/local/tmp/...` 通过 Shizuku 进程的 stdin 流式写入。app 私有目录
 * shell 读不到（UID 沙箱），而 /data/local/tmp 对 shell 可读写，装完立刻清理。
 */
object ApkInstaller {

    private const val REMOTE_DIR = "/data/local/tmp/shizukux_install"

    data class InstallOutcome(
        val success: Boolean,
        val message: String,
        val packageName: String? = null
    )

    private fun hasShizuku(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    private fun hasRoot(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        false
    }

    fun privilegeAvailable(): Boolean = hasShizuku() || hasRoot()

    /**
     * Installs an APK / APKS / XAPK / ZIP-of-APKs picked from SAF.
     * [onProgress] receives 0..1 and a short stage label for the UI.
     */
    suspend fun install(
        context: Context,
        uri: Uri,
        onProgress: (Float, String) -> Unit = { _, _ -> }
    ): InstallOutcome = withContext(Dispatchers.IO) {
        if (!privilegeAvailable()) {
            return@withContext InstallOutcome(false, "Shizuku 服务未运行，也没有 root，无法静默安装")
        }
        val name = displayName(context, uri) ?: "package.apk"
        val cacheDir = File(context.cacheDir, "install").apply { mkdirs() }
        val staged = File(cacheDir, sanitize(name))
        try {
            onProgress(0f, "读取安装包")
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    copyWithProgress(input, output, 0f, 0.45f, onProgress)
                }
            } ?: return@withContext InstallOutcome(false, "无法读取所选文件")
        } catch (e: Exception) {
            Timber.tag("ApkInstaller").w(e, "staging failed")
            return@withContext InstallOutcome(false, "读取安装包失败：${e.message}")
        }

        try {
            installStaged(context, staged, cacheDir, onProgress)
        } finally {
            runCatching { staged.delete() }
        }
    }

    /** Installs a file that already exists locally (store download, backup restore, etc.). */
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

        if (splitContainer && staged.name.endsWith(".apkm", true)) {
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
            pushAndInstall(apks, onProgress)
        } finally {
            if (splitContainer) apks.forEach { runCatching { it.delete() } }
        }
    }

    private fun pushAndInstall(apks: List<File>, onProgress: (Float, String) -> Unit): InstallOutcome {
        runPrivileged("rm -rf $REMOTE_DIR; mkdir -p $REMOTE_DIR")
            ?: return InstallOutcome(false, "无法获得 shell 权限")

        val remotePaths = ArrayList<String>(apks.size)
        apks.forEachIndexed { index, file ->
            val remote = "$REMOTE_DIR/${index}_${sanitize(file.name)}"
            onProgress(0.45f + 0.35f * (index.toFloat() / apks.size), "传输 ${file.name}")
            try {
                pushFile(file, remote) { written ->
                    val perFile = (index.toFloat() + written.toFloat() / file.length().coerceAtLeast(1)) /
                        apks.size
                    onProgress(0.45f + 0.35f * perFile, "传输 ${file.name}")
                }
            } catch (e: Exception) {
                runPrivileged("rm -rf $REMOTE_DIR")
                return InstallOutcome(false, "传输失败：${e.message}")
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
        val output = runPrivileged(command) ?: return InstallOutcome(false, "安装命令执行失败")
        onProgress(1f, "完成")

        val success = output.contains("Success") || output.contains("EXIT:0")
        val failureLine = output.lineSequence()
            .firstOrNull { it.contains("Failure") || it.contains("INSTALL_FAILED") || it.contains("Error") }
            ?.trim()
        return InstallOutcome(
            success = success,
            message = if (success) "安装成功" else (failureLine ?: output.trim().takeLast(200)),
            packageName = null
        )
    }

    fun uninstall(packageName: String, keepData: Boolean = false): InstallOutcome {
        if (!privilegeAvailable()) return InstallOutcome(false, "Shizuku 服务未运行，也没有 root")
        val flags = if (keepData) "-k " else ""
        val output = runPrivileged("pm uninstall $flags--user 0 '$packageName' 2>&1; echo EXIT:$?")
            ?: return InstallOutcome(false, "卸载命令执行失败")
        val success = output.contains("Success") || output.contains("EXIT:0")
        return InstallOutcome(success, if (success) "已卸载 $packageName" else output.trim().takeLast(200))
    }

    // ---------------------------------------------------------------- helpers

    private fun runPrivileged(command: String): String? {
        if (hasShizuku()) {
            return try {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
                    ?: return null
                try {
                    process.inputStream.bufferedReader().readText().also { process.waitFor() }
                } finally {
                    runCatching { process.destroy() }
                }
            } catch (e: Exception) {
                Timber.tag("ApkInstaller").w(e, "shizuku command failed")
                null
            }
        }
        if (hasRoot()) {
            return try {
                Shell.cmd(command).exec().out.joinToString("\n")
            } catch (e: Exception) {
                Timber.tag("ApkInstaller").w(e, "root command failed")
                null
            }
        }
        return null
    }

    private fun pushFile(file: File, remotePath: String, onBytes: (Long) -> Unit) {
        if (!hasShizuku()) {
            // Root can read the app cache directly, so a copy is enough.
            val out = runPrivileged("cp '${file.absolutePath}' '$remotePath' && chmod 644 '$remotePath'; echo EXIT:$?")
            if (out == null || !out.contains("EXIT:0")) throw IOException("复制到 $remotePath 失败")
            onBytes(file.length())
            return
        }
        val process = Shizuku.newProcess(arrayOf("sh", "-c", "cat > '$remotePath'"), null, null)
            ?: throw IOException("无法创建写入进程")
        try {
            file.inputStream().use { input ->
                process.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onBytes(written)
                    }
                    output.flush()
                }
            }
        } finally {
            val code = try {
                process.waitFor()
            } catch (e: Exception) {
                -1
            }
            runCatching { process.destroy() }
            if (code != 0) throw IOException("写入 $remotePath 失败（exit $code）")
        }
    }

    private fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        from: Float,
        to: Float,
        onProgress: (Float, String) -> Unit
    ) {
        val total = input.available().toLong().takeIf { it > 0 }
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            written += read
            total?.let { onProgress(from + (to - from) * (written.toFloat() / it), "读取安装包") }
        }
    }

    private fun extractApks(container: File, cacheDir: File): List<File> {
        val outDir = File(cacheDir, "split_${System.nanoTime()}").apply { mkdirs() }
        val result = ArrayList<File>()
        ZipInputStream(container.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val lower = entry.name.lowercase()
                if (!lower.endsWith(".apk")) continue
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
