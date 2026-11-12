package af.shizuku.manager.apps

import com.topjohnwu.superuser.Shell
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * 一条统一的"以 shell(uid 2000) 身份执行命令"的通道：优先 Shizuku，其次 root。
 * 安装器、卸载、桌面图标开关都走这里，避免各自复制一套 ping/回退逻辑。
 */
object PrivilegedShell {

    fun available(): Boolean = hasShizuku() || hasRoot()

    fun hasShizuku(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    fun hasRoot(): Boolean = try {
        Shell.getShell().isRoot
    } catch (e: Exception) {
        false
    }

    /** Returns combined stdout/stderr, or null when no shell is available. */
    fun run(command: String): String? {
        val wrapped = "{ $command ; } 2>&1"
        if (hasShizuku()) {
            return try {
                val process = Shizuku.newProcess(arrayOf("sh", "-c", wrapped), null, null) ?: return null
                try {
                    process.inputStream.bufferedReader().readText().also { process.waitFor() }
                } finally {
                    runCatching { process.destroy() }
                }
            } catch (e: Exception) {
                Timber.tag("PrivilegedShell").w(e, "shizuku command failed")
                null
            }
        }
        if (hasRoot()) {
            return try {
                val result = Shell.cmd(command).exec()
                (result.out + result.err).joinToString("\n")
            } catch (e: Exception) {
                Timber.tag("PrivilegedShell").w(e, "root command failed")
                null
            }
        }
        return null
    }

    /**
     * Stream a local file into a remote path through the shell's stdin (`cat > path`).
     * app 私有目录对 shell 不可读，所以不复制、直接流进去。
     */
    fun push(file: java.io.File, remotePath: String, onBytes: (Long) -> Unit): Boolean {
        if (!hasShizuku()) {
            val out = run("cp '${file.absolutePath}' '$remotePath' && chmod 644 '$remotePath'; echo EXIT:$?")
            if (out == null || !out.contains("EXIT:0")) return false
            onBytes(file.length())
            return true
        }
        val process = try {
            Shizuku.newProcess(arrayOf("sh", "-c", "cat > '$remotePath'"), null, null)
        } catch (e: Exception) {
            null
        } ?: return false
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
        } catch (e: Exception) {
            Timber.tag("PrivilegedShell").w(e, "push failed")
            return false
        } finally {
            runCatching { process.waitFor() }
            runCatching { process.destroy() }
        }
        return true
    }
}
