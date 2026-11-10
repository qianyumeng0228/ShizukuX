package af.shizuku.manager.module

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import af.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.server.IShizukuService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared runtime utilities for ShizukuX ADB Modules (local variant, ported from
 * Nightzuku's ModuleRuntimeUtils with the Nightzuku-specific host-attestation
 * machinery stripped out).
 *
 * Kept intentionally Android-light: only [readStreamTail], [buildModuleEnvironment]
 * and [ensureSuShim] touch the platform; path/prop validation delegates to
 * [ModuleProp] / [ModuleValidator].
 */
object ModuleRuntimeUtils {

    const val MAX_OUTPUT_CHARS_DEFAULT = 64 * 1024

    /**
     * PATH handed to module scripts, mirroring the shevery / shizuku-next runtime so a
     * module written against that host finds every tool it expects. The shim bin directory
     * is prepended at call time (see [buildModuleEnvironment]).
     */
    const val MODULE_PATH =
        "/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:" +
            "/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin:" +
            "/sbin:/data/adb/apatch:/data/adb/ksu/bin"

    /**
     * Where the shim actually has to live: module scripts run as `shell` (uid 2000), which
     * cannot read the app's private `filesDir` at all — SELinux denies it, so a shim placed
     * there is never found on PATH. `/data/local/tmp` is the one directory both the app (via
     * the Shizuku process) and the script process can reach.
     */
    const val SU_SHIM_SHELL_DIR = "/data/local/tmp/.shizukux-bin"

    /**
     * Shell-readable mirror root for module trees.
     *
     * Module scripts are not executed from the app's `filesDir`: the app reads the script text
     * (untrusted_app may read its own files) and pipes it into `sh -c`, which runs as `shell`
     * (uid 2000). From that point on every file the script opens itself — a `source` of
     * a helper under `lib/`, a `cat` of `module.prop`, the initial `cd "$MODDIR"` — is opened
     * by `shell`, and SELinux denies `u:r:shell:s0` -> `app_data_file` with `{ search }`. The
     * files are 0644 and look perfectly readable, yet the shell reports
     * "No such file or directory" / "Permission denied".
     *
     * So each run stages the module tree here first (written by the shell side, because the
     * app cannot write `/data/local/tmp` at all) and points MODDIR/MODPATH at the mirror.
     */
    const val MODULE_MIRROR_DIR = "/data/local/tmp/.shizukux-mod"

    /** Total source bytes we are willing to inline into the staging prelude. */
    private const val MIRROR_MAX_TOTAL_BYTES = 512L * 1024

    /** Per-file ceiling: scripts, props and small data only — no binaries. */
    private const val MIRROR_MAX_FILE_BYTES = 128L * 1024

    /** Beyond this the prelude gets too large for one `sh -c` argument. */
    private const val MIRROR_MAX_FILES = 64

    /** Never staged: logs are host-owned, webui is read by the app, not by shell. */
    private val MIRROR_SKIP_DIRS = setOf("logs", "webui")

    /** Shell metacharacters that would let a module filename break the generated prelude. */
    private val MIRROR_UNSAFE_PATH_CHARS = charArrayOf('\'', '"', '$', '`', '\\', '\n', '\r')

    /**
     * `su` shim for ADB modules.
     *
     * A shevery-family module assumes `su` exists because that host runs its modules under
     * a root-capable environment. Under ShizukuX the module runs as shell, so a script that
     * calls `su -c ...` would previously die with "su: not found" from the shell itself and
     * look like a module bug.
     *
     * Behaviour, deliberately honest:
     *  - already root (id -u == 0): behave like a real su — `-c CMD` runs CMD, no args opens
     *    a shell, so nested calls keep the root context;
     *  - not root: hand the call to a real su binary if one exists on this device
     *    (KernelSU / SukiSU / APatch layouts included), which is what makes root-capable
     *    modules work;
     *  - no su anywhere: print `su: not found` and exit 127 — never fake a root shell.
     */
    private val SU_SHIM_SCRIPT = """
        #!/system/bin/sh
        # su shim installed by ShizukuX for ADB modules.
        if [ "${'$'}(id -u)" -eq 0 ]; then
            while [ ${'$'}# -gt 0 ]; do
                case "${'$'}1" in
                    -c)
                        shift
                        exec sh -c "${'$'}*"
                        ;;
                    *)
                        shift
                        ;;
                esac
            done
            exec sh
        fi
        for p in /system/bin/su /system/xbin/su /sbin/su /vendor/bin/su /data/adb/ksu/bin/su /data/adb/apatch/su; do
            if [ -x "${'$'}p" ] && [ "${'$'}p" != "${'$'}0" ]; then
                exec "${'$'}p" "${'$'}@"
            fi
        done
        echo "su: not found" >&2
        exit 127
    """.trimIndent()

    /** Validates a module id (grammar: letter + letters/digits/._-). */
    val MODULE_ID_REGEX: Regex get() = ModuleProp.MODULE_ID_REGEX

    /** Parses a `.prop` payload (delegates to [ModuleProp]). */
    fun parseProps(raw: String): Map<String, String> = ModuleProp.parse(raw)

    /** Parses a `.prop` file if it exists and is a regular readable file. */
    fun readPropsFile(file: File): Map<String, String> = ModuleProp.parseFile(file)

    /**
     * Installs (or refreshes) the `su` shim through the Shizuku process and returns the
     * directory to prepend to PATH, or null when it could not be installed.
     *
     * The shim is written via the process's stdin rather than the app filesystem: the app
     * cannot write `/data/local/tmp` itself, and the script process cannot read the app's
     * private directory, so stdin is the only channel both sides share. Content is compared
     * first and rewritten only when it differs; the directory and the script are re-verified
     * on every call rather than cached for the life of the app process.
     */
    fun ensureSuShim(service: IShizukuService): String? {
        return try {
            val suPath = "$SU_SHIM_SHELL_DIR/su"

            // /data/local/tmp is world-writable: another shell user can create this
            // directory first, or rewrite the script after the fact. Ownership and mode are
            // re-checked on every call — the old one-shot "verified" flag kept using a
            // hijacked shim for the rest of the process — and a directory that cannot be
            // brought back to shell/0700 is refused instead of being prepended to PATH.
            fun statDir(): String =
                runShell(service, "stat -c '%U %a' $SU_SHIM_SHELL_DIR 2>/dev/null", null, 5)
                    .second.trim()

            if (statDir() != "shell 700") {
                runShell(
                    service,
                    "mkdir -p $SU_SHIM_SHELL_DIR 2>/dev/null; " +
                        "chmod 700 $SU_SHIM_SHELL_DIR 2>/dev/null",
                    null, 5
                )
                if (statDir() != "shell 700") {
                    LOGGER.w("su shim dir is not shell:0700, refusing to use it")
                    return null
                }
            }

            val existing = runShell(service, "cat $suPath 2>/dev/null", null, 5).second
            if (existing.trim() == SU_SHIM_SCRIPT.trim()) {
                runShell(service, "chmod 700 $suPath 2>/dev/null", null, 5)
                return SU_SHIM_SHELL_DIR
            }

            val installer = "mkdir -p $SU_SHIM_SHELL_DIR 2>/dev/null; " +
                "chmod 700 $SU_SHIM_SHELL_DIR 2>/dev/null; " +
                "cat > $SU_SHIM_SHELL_DIR/su.part 2>/dev/null; " +
                "chmod 700 $SU_SHIM_SHELL_DIR/su.part 2>/dev/null; " +
                "mv $SU_SHIM_SHELL_DIR/su.part $suPath 2>/dev/null"
            val (code, _) = runShell(service, installer, SU_SHIM_SCRIPT, 8)
            if (code != 0) return null

            // Verify what landed rather than what was sent: `mv` replaced the file after
            // the write, and a concurrent writer can win the race at any point.
            if (runShell(service, "cat $suPath 2>/dev/null", null, 5).second.trim() !=
                SU_SHIM_SCRIPT.trim()
            ) {
                LOGGER.w("su shim content mismatch after install")
                return null
            }
            SU_SHIM_SHELL_DIR
        } catch (_: Exception) {
            null
        }
    }

    /** Absolute mirror path for a module id. */
    fun moduleMirrorPath(moduleId: String): String = "$MODULE_MIRROR_DIR/$moduleId"

    /**
     * Builds the shell prelude that stages [module] under [moduleMirrorPath] and retargets the
     * module env vars at the mirror. Returns `mirrorPath to prelude`, or null when the module
     * cannot be mirrored (bad id, too many/large files, unsafe filename) — callers then keep
     * the legacy `cd "$MODDIR"`, which still works for single-file modules that never open a
     * helper of their own.
     *
     * The staging runs on the shell side (stdin/`sh -c`), never from the app: the app has no
     * write access to `/data/local/tmp`, and the shell has no read access to `filesDir`.
     */
    fun buildModuleMirrorPrelude(module: AdbModule): Pair<String, String>? {
        val id = module.id
        if (!MODULE_ID_REGEX.matches(id)) return null
        val root = module.directory
        if (!root.isDirectory) return null

        val files = ArrayList<File>()
        runCatching {
            root.walkTopDown()
                .onEnter { it == root || (it.name !in MIRROR_SKIP_DIRS && !it.name.startsWith(".")) }
                .filter { it.isFile }
                .filter { it.length() in 1L..MIRROR_MAX_FILE_BYTES }
                .filter { !it.name.startsWith("banner.") }
                .forEach { files += it }
        }
        if (files.isEmpty() || files.size > MIRROR_MAX_FILES) return null
        if (files.sumOf { it.length() } > MIRROR_MAX_TOTAL_BYTES) return null

        val dir = moduleMirrorPath(id)
        val prelude = StringBuilder()
        prelude.append("D=").append(dir).append('\n')
        prelude.append("rm -rf \"\$D\" 2>/dev/null\n")
        prelude.append(
            "mkdir -p \"\$D\" 2>/dev/null || { echo 'module mirror unavailable' >&2; exit 126; }\n"
        )
        prelude.append("chmod 700 \"\$D\" 2>/dev/null\n")
        // `mkdir -p` inherits this process's umask (0 here), which leaves the mirror root
        // world-writable — a second shell-level writer could then swap the module tree under
        // the next run. Tighten the parent too, not just the per-module directory.
        prelude.append("chmod 700 $MODULE_MIRROR_DIR 2>/dev/null\n")

        for (file in files) {
            val rel = file.relativeTo(root).invariantSeparatorsPath
            if (rel.isEmpty() || rel.any { it in MIRROR_UNSAFE_PATH_CHARS }) return null
            val payload = runCatching {
                Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
            }.getOrNull() ?: return null
            val parent = rel.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                prelude.append("mkdir -p \"\$D/").append(parent).append("\" 2>/dev/null\n")
            }
            prelude.append("printf '%s' '").append(payload)
                .append("' | base64 -d > \"\$D/").append(rel).append("\" 2>/dev/null\n")
        }

        prelude.append("export MODDIR=\"\$D\" MODPATH=\"\$D\" SHIZUKU_MODULE_CWD=\"\$D\"\n")
        prelude.append("cd \"\$D\" 2>/dev/null || true\n")
        return dir to prelude.toString()
    }

    /** Runs a small shell command through [service] and returns (exitCode, stdout). */
    private fun runShell(
        service: IShizukuService,
        script: String,
        stdin: String?,
        timeoutSeconds: Long
    ): Pair<Int, String> {
        val remote = service.newProcess(arrayOf("sh", "-c", script), emptyArray(), "/data/local/tmp")
        val stdoutPfd = remote.getInputStream()
        val stderrPfd = remote.getErrorStream()
        var out = ""
        var err = ""
        val reader = Thread {
            try {
                out = readStreamTail(stdoutPfd, 8192)
            } catch (_: Exception) {
            }
        }
        val errReader = Thread {
            try {
                err = readStreamTail(stderrPfd, 8192)
            } catch (_: Exception) {
            }
        }
        reader.start()
        errReader.start()
        runCatching {
            ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).use { stream ->
                if (stdin != null) stream.write(stdin.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
        }
        val finished = remote.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS.name)
        if (!finished) runCatching { remote.destroy() }
        reader.join(2000)
        errReader.join(2000)
        val code = if (finished) runCatching { remote.exitValue() }.getOrDefault(-1) else 124
        return code to out
    }

    /**
     * Pure-JVM tail truncation used by [readStreamTail] — keeps at most the last
     * [maxChars] characters. Extracted so the output-cap behavior (constraint #7)
     * is directly unit-testable (P1-4 ModuleOutputTest).
     */
    internal fun truncateTail(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.substring(text.length - maxChars)
    }

    /**
     * Reads UTF-8 output from a [ParcelFileDescriptor] while retaining at most the last
     * [maxChars] characters — constraint #7 (output cap).
     */
    fun readStreamTail(
        fd: ParcelFileDescriptor,
        maxChars: Int = MAX_OUTPUT_CHARS_DEFAULT
    ): String {
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
            java.io.BufferedReader(java.io.InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val tail = StringBuilder()
                val buffer = CharArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = reader.read(buffer)
                    if (read <= 0) break
                    tail.append(buffer, 0, read)
                    if (tail.length > maxChars * 2) {
                        tail.delete(0, tail.length - maxChars)
                    }
                }
                truncateTail(tail.toString(), maxChars)
            }
        }
    }

    /**
     * Builds the canonical module environment variables for a script run.
     * Reserved variables are set last so no caller-supplied extraEnv can spoof them.
     *
     * [binDir] is the shim directory returned by [ensureSuShim]; it is prepended to PATH so
     * `su` resolves to the shim before any device binary.
     */
    fun buildModuleEnvironment(
        module: AdbModule,
        cwd: File = module.directory,
        extraEnv: Map<String, String> = emptyMap(),
        binDir: String? = null,
        modDirPath: String? = null
    ): Array<String> {
        val mode = ModuleSettings.getAccessMode().value
        val trusted = if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"
        val background = if (ModuleSettings.canRunBackground(module)) "1" else "0"

        val env = linkedMapOf<String, String>()
        extraEnv.forEach { (k, v) -> env[k] = v }

        // When the script runs from the shell-readable mirror, MODDIR must point there too —
        // a script that trusts MODDIR would otherwise re-open the private path and fail.
        val modDir = modDirPath ?: module.directory.absolutePath
        env["MODDIR"] = modDir
        env["MODPATH"] = modDir
        env["SHIZUKU_MODULE_CWD"] = cwd.absolutePath
        env["ASH_STANDALONE"] = "1"
        env["SHIZUKU_MODULE_ID"] = module.id
        env["SHIZUKU_MODULE_MODE"] = mode
        env["SHIZUKU_MODULE_TRUSTED"] = trusted
        env["SHIZUKU_MODULE_BACKGROUND"] = background
        env["AXERON"] = "true"
        env["AXERONVER"] = "1.0.0"
        env["ARCH"] = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        env["PATH"] = listOfNotNull(binDir?.takeIf { it.isNotBlank() }, MODULE_PATH).joinToString(":")

        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }
}
