package af.shizuku.manager.module

import android.os.Build
import android.os.ParcelFileDescriptor
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

    @Volatile
    private var suShimVerified = false

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
     * first and rewritten only when it differs; the result is cached for the app process.
     */
    fun ensureSuShim(service: IShizukuService): String? {
        if (suShimVerified) return SU_SHIM_SHELL_DIR
        return try {
            val suPath = "$SU_SHIM_SHELL_DIR/su"
            val existing = runShell(service, "cat $suPath 2>/dev/null", null, 5).second
            if (existing.trim() == SU_SHIM_SCRIPT.trim()) {
                runShell(service, "chmod 700 $suPath 2>/dev/null", null, 5)
                suShimVerified = true
                return SU_SHIM_SHELL_DIR
            }

            val installer = "mkdir -p $SU_SHIM_SHELL_DIR 2>/dev/null; " +
                "cat > $SU_SHIM_SHELL_DIR/su.part 2>/dev/null; " +
                "chmod 700 $SU_SHIM_SHELL_DIR/su.part 2>/dev/null; " +
                "mv $SU_SHIM_SHELL_DIR/su.part $suPath 2>/dev/null"
            val (code, _) = runShell(service, installer, SU_SHIM_SCRIPT, 8)
            if (code == 0) {
                suShimVerified = true
                SU_SHIM_SHELL_DIR
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
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
        binDir: String? = null
    ): Array<String> {
        val mode = ModuleSettings.getAccessMode().value
        val trusted = if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"
        val background = if (ModuleSettings.canRunBackground(module)) "1" else "0"

        val env = linkedMapOf<String, String>()
        extraEnv.forEach { (k, v) -> env[k] = v }

        env["MODDIR"] = module.directory.absolutePath
        env["MODPATH"] = module.directory.absolutePath
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
