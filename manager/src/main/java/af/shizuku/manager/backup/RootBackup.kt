package af.shizuku.manager.backup

import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuWait
import rikka.shizuku.Shizuku

/**
 * Backs up and restores app data by reading `/data/data/<pkg>` as root.
 *
 * Why this exists next to the APK-patching path: [rikka.shizuku.server.ApkPatcherImpl] can only
 * reach an app's private directory through `run-as`, which requires the app to be debuggable —
 * so it had to uninstall the app, install a re-signed debuggable copy, stream the tar, then
 * uninstall again and reinstall the original. Every single backup therefore removed the target
 * app from the system for a few seconds (icon gone, launcher/widget/link state lost, and any
 * integrity or signature check inside the app saw a foreign certificate). Users read that as
 * "备份会把应用卸载掉".
 *
 * Root can read `/data/data/<pkg>` directly, so with root available there is no reason to touch
 * the APK at all: tar in, tar out, chown back to the app's uid. When root is unavailable the
 * caller falls back to the old SAF + temp-debug path.
 *
 * The script runs through the Shizuku shell (`su -c`, the same channel the file browser already
 * uses for root reads), so no extra su prompt is raised for this app itself.
 */
object RootBackup {

    const val DATA_ARCHIVE = "data.tar.gz"
    const val EXTERNAL_ARCHIVE = "external.tar.gz"
    const val OBB_ARCHIVE = "obb.tar.gz"

    const val PRE_RESTORE_PREFIX = "data.pre-restore."

    /** Where backups go when root is available but the user has not picked a directory yet. */
    const val DEFAULT_DIR = "/sdcard/ShizukuX"

    /** One probe per process, because the probe can block on a root-manager prompt. */
    @Volatile private var rootChecked = false
    @Volatile private var rootAvailable = false

    private val warmLock = Any()
    @Volatile private var warming = false

    /**
     * Whether root was already proven usable in this process.
     *
     * Answers false before the probe ran and never starts one: the home screen binds this per
     * card on the main thread, where a `su` round-trip would stall the list.
     */
    fun availableKnown(): Boolean = rootChecked && rootAvailable

    /** Whether root is usable for direct data access. */
    fun available(): Boolean {
        if (rootChecked) return rootAvailable
        rootChecked = true
        rootAvailable = exec("id -u")?.trim() == "0"
        return rootAvailable
    }

    /**
     * Runs [available] once on a background thread and reports the answer.
     *
     * The home card needs to know whether the backup is root-backed before it can say where files
     * go, but its bind runs on the main thread where `su` would stall the list.
     */
    fun warmUp(onDone: (Boolean) -> Unit) {
        if (rootChecked) {
            onDone(rootAvailable)
            return
        }
        synchronized(warmLock) {
            if (rootChecked) {
                onDone(rootAvailable)
                return
            }
            if (warming) return
            warming = true
        }
        Thread({
            val ok = try {
                available()
            } catch (_: Throwable) {
                false
            }
            warming = false
            onDone(ok)
        }, "root-backup-probe").apply { isDaemon = true }.start()
    }

    /**
     * Whether [path] is an existing directory, or null when the probe cannot run.
     *
     * Distinguishes "no such directory yet" from "exists but unreadable": the picker opens on
     * [DEFAULT_DIR], which does not exist until the first backup creates it, and reporting that as
     * unreadable reads like a permission problem.
     */
    fun isDir(path: String): Boolean? {
        if (!available()) return null
        val out = exec("if [ -d ${q(path)} ]; then echo DIR; else echo MISSING; fi") ?: return null
        return out.contains("DIR")
    }

    fun dataDir(pkg: String): String = "/data/data/$pkg"
    fun externalDir(pkg: String): String = "/sdcard/Android/data/$pkg"
    fun obbDir(pkg: String): String = "/sdcard/Android/obb/$pkg"

    /** `<rootDir>/<pkg>` — where the three archives of one app live. */
    fun packageDir(rootDir: String, pkg: String): String = rootDir.trimEnd('/') + "/" + pkg

    /**
     * Writes data/external/obb archives for [pkg] under [rootDir], without touching the APK.
     *
     * The package directory is wiped first so a second backup cannot leave a stale archive
     * (matching the SAF path's invariant of one archive per leg). Only the internal tar decides
     * success: external and OBB are optional legs that many apps simply do not have.
     */
    fun backup(pkg: String, rootDir: String): Boolean {
        if (!available() || !isValidPackage(pkg)) return false
        val dir = packageDir(rootDir, pkg)
        val internal = dataDir(pkg)
        val script = """
            DIR=${q(dir)}
            mkdir -p ${'$'}DIR || exit 11
            rm -f ${'$'}DIR/$DATA_ARCHIVE ${'$'}DIR/$EXTERNAL_ARCHIVE ${'$'}DIR/$OBB_ARCHIVE
            tar -czf ${'$'}DIR/$DATA_ARCHIVE -C ${q(internal)} . 2>/dev/null || exit 12
            if [ -d ${q(externalDir(pkg))} ]; then
              tar -czf ${'$'}DIR/$EXTERNAL_ARCHIVE -C ${q(externalDir(pkg))} . 2>/dev/null
            fi
            if [ -d ${q(obbDir(pkg))} ]; then
              tar -czf ${'$'}DIR/$OBB_ARCHIVE -C ${q(obbDir(pkg))} . 2>/dev/null
            fi
            echo BACKUP_OK
        """.trimIndent()
        val out = exec(script)
        val ok = out?.contains("BACKUP_OK") == true
        if (!ok) LOGGER.w("root backup failed for $pkg: $out")
        return ok
    }

    /**
     * Restores [pkg] from `<rootDir>/<pkg>`.
     *
     * Order matters: the app is force-stopped first, the current data is snapshotted to
     * `data.pre-restore.<ts>.tar.gz` (so a restore can itself be rolled back), then the internal
     * directory contents are cleared and re-filled. `chown` to the uid/gid the directory already
     * had plus `restorecon` are mandatory — without them the restored files belong to root and
     * SELinux denies the app every one of them, which shows up as the app starting from scratch.
     *
     * When [snapshot] is given the restore reads that snapshot instead of [DATA_ARCHIVE].
     */
    fun restore(
        pkg: String,
        rootDir: String,
        snapshot: String? = null,
        includeInternal: Boolean = true
    ): Boolean {
        if (!available() || !isValidPackage(pkg)) return false
        val dir = packageDir(rootDir, pkg)
        val internal = dataDir(pkg)
        val source = if (snapshot != null) "$PRE_RESTORE_PREFIX$snapshot.tar.gz" else DATA_ARCHIVE
        val internalBlock = if (includeInternal) """
            [ -f ${'$'}DIR/${'$'}SRCN ] || exit 21
            [ -d ${q(internal)} ] || exit 22
            if [ -f ${'$'}DIR/$DATA_ARCHIVE ]; then
              TS=${'$'}(date +%Y%m%d-%H%M%S)
              cp ${'$'}DIR/$DATA_ARCHIVE ${'$'}DIR/$PRE_RESTORE_PREFIX${'$'}TS.tar.gz
            fi
            UID_=${'$'}(stat -c %u ${q(internal)})
            GID_=${'$'}(stat -c %g ${q(internal)})
            rm -rf ${q(internal)}/* ${q(internal)}/.[!.]* 2>/dev/null
            tar -xzf ${'$'}DIR/${'$'}SRCN -C ${q(internal)} || exit 23
            chown -R ${'$'}{UID_}:${'$'}{GID_} ${q(internal)}
            restorecon -R ${q(internal)} 2>/dev/null
        """.trimIndent() else ""
        val script = """
            DIR=${q(dir)}
            SRCN=${q(source)}
            am force-stop ${q(pkg)}
            $internalBlock
            UID_=${'$'}(stat -c %u ${q(internal)} 2>/dev/null || echo 0)
            GID_=${'$'}(stat -c %g ${q(internal)} 2>/dev/null || echo 0)
            if [ -f ${'$'}DIR/$EXTERNAL_ARCHIVE ] && [ -d ${q(externalDir(pkg))} ]; then
              rm -rf ${q(externalDir(pkg))}/* 2>/dev/null
              tar -xzf ${'$'}DIR/$EXTERNAL_ARCHIVE -C ${q(externalDir(pkg))} 2>/dev/null
              chown -R ${'$'}{UID_}:${'$'}{GID_} ${q(externalDir(pkg))} 2>/dev/null
            fi
            if [ -f ${'$'}DIR/$OBB_ARCHIVE ] && [ -d ${q(obbDir(pkg))} ]; then
              rm -rf ${q(obbDir(pkg))}/* 2>/dev/null
              tar -xzf ${'$'}DIR/$OBB_ARCHIVE -C ${q(obbDir(pkg))} 2>/dev/null
              chown -R ${'$'}{UID_}:${'$'}{GID_} ${q(obbDir(pkg))} 2>/dev/null
            fi
            echo RESTORE_OK
        """.trimIndent()
        val out = exec(script)
        val ok = out?.contains("RESTORE_OK") == true
        if (!ok) LOGGER.w("root restore failed for $pkg: $out")
        return ok
    }

    /** Timestamps of the pre-restore snapshots kept for [pkg], newest first. */
    fun snapshots(rootDir: String, pkg: String): List<String> {
        if (!available()) return emptyList()
        val dir = packageDir(rootDir, pkg)
        val out = exec(
            "ls -1 ${q(dir)} 2>/dev/null | grep '^$PRE_RESTORE_PREFIX' | sed " +
                "\"s/^$PRE_RESTORE_PREFIX//;s/\\.tar\\.gz\\$//\" | sort -r"
        ) ?: return emptyList()
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** Whether at least one archive of [pkg] exists under [rootDir]. */
    fun hasBackup(rootDir: String, pkg: String): Boolean {
        if (!available()) return false
        val dir = packageDir(rootDir, pkg)
        return exec("[ -f ${q("$dir/$DATA_ARCHIVE")} ] && echo YES")?.contains("YES") == true
    }

    /**
     * Runs [script] as root and answers its merged stdout/stderr.
     *
     * The body travels as base64 and is decoded by the root shell itself
     * (`echo <b64> | base64 -d | sh`), for two reasons:
     *  - `su -c '<script>'` would parse the body a second time; these bodies carry quotes, `$`
     *    expansions and `[!.]` globs that only survive one parse.
     *  - writing the script to a file is not possible from here: the app's cache dir is mode 0700
     *    owned by the app uid, so the shell user cannot open it at all (that is why the first
     *    version's probe came back empty and the picker fell back to the SAF explanation).
     * Base64's alphabet has no shell metacharacters, so a single pair of quotes is enough.
     */
    private fun exec(script: String): String? {
        if (!ShizukuWait.ping()) return null
        var process: Process? = null
        return try {
            val payload = android.util.Base64.encodeToString(
                script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            val cmd = "su -c " + doubleQuote("echo '$payload' | base64 -d | sh") + " 2>&1"
            process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            if (out.isEmpty()) null else out
        } catch (t: Throwable) {
            LOGGER.w(t, "root backup shell failed")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // Dead binder: nothing left to release.
            }
        }
    }

    /** A path as a single shell word. */
    private fun q(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    /** [cmd] as one double-quoted argument for `su -c`, which the shell re-parses once more. */
    private fun doubleQuote(cmd: String): String =
        "\"" + cmd.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\$", "\\\$").replace("`", "\\`") + "\""

    /** Same rule the privileged side enforces before interpolating a package name. */
    private fun isValidPackage(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg.length > 128) return false
        return pkg.matches(Regex("^[A-Za-z0-9_.]+$")) && pkg.contains('.')
    }
}
