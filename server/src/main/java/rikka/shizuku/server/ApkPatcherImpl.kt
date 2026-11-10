package rikka.shizuku.server

import af.shizuku.server.IApkPatcher
import android.os.ParcelFileDescriptor
import android.util.Log
import rikka.shizuku.server.util.ArchiveVetting
import rikka.shizuku.server.util.InputValidationUtils
import rikka.shizuku.server.util.ShellExecutor
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * @param unrestricted the "Unrestricted app data access" developer option. Making an app
 *     debuggable lets the shell read and replace its private data, so starting a temp-debug
 *     session or moving data through one needs it on. Restoring the original APK never does,
 *     so turning the option off can't strand a patched app.
 */
class ApkPatcherImpl(private val unrestricted: java.util.function.BooleanSupplier) : IApkPatcher.Stub() {

    companion object {
        private const val TAG = "ApkPatcher"

        /** Historical temp dir; a root-mode server may have left it owned by root:root 0755. */
        private const val LEGACY_TMP_DIR = "/data/local/tmp/splus_td"
    }

    /**
     * Resolved once: the legacy directory can be owned by root and refuse writes, and every
     * temp-debug step would then fail silently (measured 2026-11-11: `splus_td` is
     * root:root 0755, so shell cannot create a file in it).
     */
    private val tmpDir: File by lazy { resolveTmpDir() }

    /**
     * Sessions must survive a server restart: the app on disk is still the temporary debug
     * build, and an in-memory map that died with the process left it stuck there.
     */
    private val sessionsFile: File get() = File(tmpDir, "sessions.tsv")

    // pkg → list of saved original APK paths (base first, then splits)
    private val sessions = ConcurrentHashMap<String, List<String>>()

    private fun resolveTmpDir(): File {
        val preferred = File(LEGACY_TMP_DIR)
        if (hardenDir(preferred)) return preferred
        // Fall back to a directory named after the running uid: /data/local/tmp is
        // world-traversable, and a fresh name is the only reliable way for shell to own it.
        val fallback = File("/data/local/tmp", "splus_td_" + android.os.Process.myUid())
        fallback.mkdirs()
        // mkdirs() lands at 0777 here (this process runs with a permissive umask). The
        // session file decides which packages get uninstalled and reinstalled, so the
        // directory must be private to this uid.
        hardenDir(fallback)
        Log.w(TAG, "using writable temp dir ${fallback.absolutePath} instead of $LEGACY_TMP_DIR")
        return fallback
    }

    /** Drops group/other access and confirms the directory is usable by this process. */
    private fun hardenDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        runCatching {
            dir.setReadable(false, false)
            dir.setWritable(false, false)
            dir.setExecutable(false, false)
            dir.setReadable(true, true)
            dir.setWritable(true, true)
            dir.setExecutable(true, true)
        }
        return isWritableDir(dir)
    }

    private fun isWritableDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // canWrite() checks the real POSIX permission of this process; the probe also rules
        // out a mount or SELinux policy that denies creation despite the mode bits.
        val probe = File(dir, ".write-probe-${System.nanoTime()}")
        return try {
            probe.writeText("x")
            probe.delete()
            true
        } catch (_: Exception) {
            false
        }
    }


    init {
        loadSessions()
        if (sessions.isNotEmpty()) {
            // A previous server died while an app was still patched. Restore it in the
            // background, after the service has finished wiring its binders.
            Thread({
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                runCatching { cleanupAllTempDebug() }
            }, "apk-patcher-recover").apply { isDaemon = true }.start()
        }
    }

    private fun pipe(vararg args: String): ParcelFileDescriptor? = try {
        val (readSide, writeSide) = ParcelFileDescriptor.createPipe()
        Thread {
            try {
                val proc = Runtime.getRuntime().exec(args)
                try {
                    proc.inputStream.use { src ->
                        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { dst ->
                            src.copyTo(dst)
                        }
                    }
                    proc.waitFor()
                } finally {
                    proc.destroy()
                }
            } catch (_: Exception) {
                try { writeSide.close() } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true }.start()
        readSide
    } catch (_: Exception) { null }

    private fun pipeFrom(pfd: ParcelFileDescriptor, vararg args: String): Boolean = try {
        val proc = ProcessBuilder(*args).start()
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(pfd).use { src ->
                    proc.outputStream.use { dst -> src.copyTo(dst) }
                }
            } catch (_: Exception) {
                proc.outputStream.runCatching { close() }
            }
        }.also { it.isDaemon = true }.start()
        try { proc.waitFor() == 0 } finally { proc.destroy() }
    } catch (_: Exception) { false }

    private fun findAllApks(packageName: String): List<String> {
        val out = ShellExecutor.exec("pm", "path", packageName)
        return out.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
    }

    /**
     * Install a list of APK files atomically via a pm install session.
     * Works for both single APKs and split APK sets.
     * All APKs must be signed with the same certificate.
     */
    private fun installViaSession(apkPaths: List<String>, grantPerms: Boolean = true): Boolean {
        if (apkPaths.isEmpty()) return false

        val sessionArgs = buildList {
            add("pm");
            add("install-create")
            if (grantPerms) add("-g")
        }
        val sessionOut = ShellExecutor.exec(*sessionArgs.toTypedArray())
        val sessionId = Regex("\\[(\\d+)]").find(sessionOut)?.groupValues?.get(1)?.toIntOrNull()
            ?: return false

        for (path in apkPaths) {
            // Derive the canonical split name from the filename (strip our temp prefix)
            val fileName = File(path).name
            val splitName = when {
                fileName.contains("_orig_") -> fileName.substringAfter("_orig_")
                fileName.contains("_dbg_") -> fileName.substringAfter("_dbg_")
                else -> fileName
            }
            if (ShellExecutor.execCode("pm", "install-write", sessionId.toString(), splitName, path) != 0) {
                ShellExecutor.execCode("pm", "install-abandon", sessionId.toString())
                return false
            }
        }
        return ShellExecutor.execCode("pm", "install-commit", sessionId.toString()) == 0
    }

    private fun dataAccessAllowed(): Boolean {
        if (unrestricted.asBoolean) return true
        Log.w(TAG, "temp-debug data access refused: developer option off")
        return false
    }

    /** Writes the session map to disk. Absolute paths contain no tabs, so `\t` is a safe field separator. */
    private fun persistSessions() {
        try {
            tmpDir.mkdirs()
            val body = buildString {
                sessions.forEach { (pkg, paths) ->
                    append(pkg).append('\t').append(paths.joinToString("\t")).append('\n')
                }
            }
            sessionsFile.writeText(body)
            ShellExecutor.execCode("chmod", "600", sessionsFile.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "persistSessions failed: ${e.message}")
        }
    }

    /** Rebuilds the session map after a restart; malformed lines and unknown paths are skipped. */
    private fun loadSessions() {
        try {
            val file = sessionsFile
            if (!file.isFile) return
            file.useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val fields = line.split('\t')
                    if (fields.size < 2) return@forEach
                    val pkg = fields[0]
                    if (!InputValidationUtils.isValidPackageName(pkg)) return@forEach
                    val paths = fields.drop(1).filter {
                        it.startsWith(tmpDir.absolutePath) || it.startsWith(LEGACY_TMP_DIR)
                    }
                    if (paths.isNotEmpty()) sessions[pkg] = paths
                }
            }
            if (sessions.isNotEmpty()) Log.i(TAG, "recovered ${sessions.size} temp-debug session(s)")
        } catch (e: Exception) {
            Log.w(TAG, "loadSessions failed: ${e.message}")
        }
    }

    /** Feeds [file] to a process's stdin; used after the archive has been vetted on disk. */
    private fun pipeFromFile(file: File, vararg args: String): Boolean = try {
        val proc = ProcessBuilder(*args).start()
        Thread {
            try {
                file.inputStream().use { src ->
                    proc.outputStream.use { dst -> src.copyTo(dst) }
                }
            } catch (_: Exception) {
                proc.outputStream.runCatching { close() }
            }
        }.also { it.isDaemon = true }.start()
        try { proc.waitFor() == 0 } finally { proc.destroy() }
    } catch (_: Exception) { false }

    override fun prepareTempDebug(rawPackageName: String?): Boolean {
        // Reject names containing '/', '..' or shell metacharacters before the value is
        // interpolated into `/data/data/$packageName` and passed to pm (audit slice4 P1-3).
        val packageName = rawPackageName ?: return false
        if (!dataAccessAllowed()) return false
        if (!InputValidationUtils.isValidPackageName(packageName)) return false

        // Serialize prepare/restore: two binder threads must not both pass the
        // containsKey gate and run the uninstall/reinstall dance concurrently.
        synchronized(this) {
            if (sessions[packageName] != null) return true

            val apkPaths = findAllApks(packageName)
            if (apkPaths.isEmpty()) return false

            tmpDir.mkdirs()

            // Save all original APKs to temp dir
            val origPaths = mutableListOf<String>()
            for (path in apkPaths) {
                val fileName = File(path).name // base.apk, split_config.arm64_v8a.apk, etc.
                val dest = File(tmpDir, "${packageName}_orig_$fileName").absolutePath
                if (ShellExecutor.execCode("cp", path, dest) != 0) {
                    origPaths.forEach { File(it).delete() }
                    return false
                }
                origPaths.add(dest)
            }

            // Patch base APK manifest; sign ALL APKs with the same ephemeral key.
            // Android requires all APKs in a session to share a certificate, so signing
            // must happen before uninstall, and all with the same ApkSigner instance.
            // signFile streams off disk entry-by-entry — never readBytes(), which OOM'd the
            // server on multi-GB game splits (2097992168-byte allocation).
            val patchedPaths = mutableListOf<String>()
            try {
                for (origPath in origPaths) {
                    val fileName = File(origPath).name
                    val isBase = fileName.endsWith("_orig_base.apk") || !fileName.contains("split_")
                    val patchedPath = File(tmpDir, "${packageName}_dbg_${fileName.substringAfter("_orig_")}").absolutePath
                    if (!ApkSigner.signFile(File(origPath), File(patchedPath), patchManifest = isBase)) {
                        origPaths.forEach { File(it).delete() }
                        patchedPaths.forEach { File(it).delete() }
                        return false
                    }
                    patchedPaths.add(patchedPath)
                }
            } catch (_: Exception) {
                origPaths.forEach { File(it).delete() }
                patchedPaths.forEach { File(it).delete() }
                return false
            }

            // pm uninstall -k preserves all app data
            if (ShellExecutor.execCode("pm", "uninstall", "--user", "0", "-k", packageName) != 0) {
                origPaths.forEach { File(it).delete() }
                patchedPaths.forEach { File(it).delete() }
                return false
            }

            val installed = installViaSession(patchedPaths)
            patchedPaths.forEach { File(it).delete() }

            if (!installed) {
                // Try to put the original back. If that fails too, keep the session so a
                // later restoreOriginal (including the one on next startup) can retry,
                // rather than deleting the only copy of the original APK.
                if (installViaSession(origPaths)) {
                    origPaths.forEach { File(it).delete() }
                } else {
                    sessions[packageName] = origPaths
                    persistSessions()
                    Log.w(TAG, "recovery install failed for $packageName; originals kept")
                }
                return false
            }

            sessions[packageName] = origPaths
            persistSessions()
            return true
        }
    }

    override fun streamDataDir(packageName: String?): ParcelFileDescriptor? {
        if (packageName == null || !InputValidationUtils.isValidPackageName(packageName)) return null
        if (!dataAccessAllowed()) return null
        if (!sessions.containsKey(packageName)) return null
        return pipe("run-as", packageName, "tar", "-czf", "-", "-C", "/data/data/$packageName", ".")
    }

    override fun restoreDataDir(packageName: String?, tarStream: ParcelFileDescriptor?): Boolean {
        if (packageName == null || !InputValidationUtils.isValidPackageName(packageName) || tarStream == null) return false
        if (!dataAccessAllowed()) return false
        if (!sessions.containsKey(packageName)) return false

        // The archive is inspected before it reaches run-as. Extracting it unchecked let an
        // entry with `..` (or a write under a symlink) escape /data/data/<pkg>; the lister
        // needs a real file, so the stream is staged first and removed in every case.
        val staged = File(tmpDir, "vet-${System.nanoTime()}.tar.gz")
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(tarStream).use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            ArchiveVetting.vetArchiveOrThrow(staged)
            pipeFromFile(staged, "run-as", packageName, "tar", "-xzf", "-", "-C", "/data/data/$packageName")
        } catch (e: Exception) {
            Log.w(TAG, "restoreDataDir rejected for $packageName: ${e.message}")
            false
        } finally {
            staged.delete()
        }
    }

    override fun restoreOriginal(packageName: String?): Boolean {
        if (packageName == null || !InputValidationUtils.isValidPackageName(packageName)) return false
        synchronized(this) {
            val origPaths = sessions[packageName] ?: return false

            // Nothing left to restore: the saved copy is gone (wiped tmp dir, manual
            // cleanup). Keeping the session would retry the same impossible install on
            // every server start.
            if (origPaths.none { File(it).exists() }) {
                Log.w(TAG, "originals for $packageName are gone; dropping session")
                sessions.remove(packageName)
                persistSessions()
                return false
            }

            // A previous attempt can have uninstalled the package and then failed to
            // reinstall it. `pm uninstall -k` on a package that is already gone answers
            // non-zero, so checking it unconditionally made every retry fail and left the
            // session stuck forever. Only uninstall when the package still exists.
            val stillInstalled = ShellExecutor.exec("pm", "path", packageName).contains("package:")
            val uninstallOk = !stillInstalled ||
                ShellExecutor.execCode("pm", "uninstall", "--user", "0", "-k", packageName) == 0

            val ok = uninstallOk && installViaSession(origPaths)

            if (ok) {
                sessions.remove(packageName)
                persistSessions()
                origPaths.forEach { File(it).delete() }
            } else {
                // Keep the session and the saved APK: the app is still the temporary build
                // and these paths are the only way back. Deleting them here stranded the
                // app permanently on the temporary signature.
                Log.w(TAG, "restoreOriginal failed for $packageName; session and originals kept")
            }
            return ok
        }
    }

    override fun streamOriginalApk(packageName: String?): ParcelFileDescriptor? {
        if (packageName == null || !InputValidationUtils.isValidPackageName(packageName)) return null
        // The original APK is the app's pre-patch binary; handing it to a caller that is not
        // allowed to move app data would leak it past the option this class gates on.
        if (!dataAccessAllowed()) return null
        // Stream the base APK (prefer explicit base.apk, fall back to first)
        val origPath = sessions[packageName]
            ?.firstOrNull { it.endsWith("_orig_base.apk") }
            ?: sessions[packageName]?.firstOrNull()
            ?: return null
        return pipe("cat", origPath)
    }

    override fun isTempDebugging(packageName: String?): Boolean {
        if (packageName == null || !InputValidationUtils.isValidPackageName(packageName)) return false
        // Deliberately not gated on the developer option: this is a state query, not data
        // access, and it has to stay truthful while the option is off. Otherwise a caller
        // reads false, skips restoreOriginal, and leaves the app on the temporary build —
        // exactly the stranding that restoreOriginal is exempted from to prevent.
        return sessions.containsKey(packageName)
    }

    override fun cleanupAllTempDebug() {
        // Re-read first: a session written by a previous process is the only record that an
        // app is still running the temporary build.
        loadSessions()
        for (pkg in sessions.keys.toList()) {
            restoreOriginal(pkg)
        }
        // Only files no session still references are orphans. A failed restore keeps its
        // session (and therefore its originals), so this sweep cannot delete them.
        val tracked = sessions.values.flatten().toSet()
        tmpDir.listFiles()?.forEach { f ->
            if ((f.name.contains("_orig_") || f.name.contains("_dbg_")) &&
                !tracked.contains(f.absolutePath)
            ) {
                f.delete()
            }
        }
    }
}
