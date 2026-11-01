package af.shizuku.manager.module

import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Shared runtime utilities for ShizukuX ADB Modules (local variant, ported from
 * Nightzuku's ModuleRuntimeUtils with the Nightzuku-specific host-attestation
 * machinery stripped out).
 *
 * Kept intentionally Android-light: only [readStreamTail] and [buildModuleEnvironment]
 * touch the platform; path/prop validation delegates to [ModuleProp] / [ModuleValidator].
 */
object ModuleRuntimeUtils {

    const val MAX_OUTPUT_CHARS_DEFAULT = 64 * 1024

    /** Validates a module id (grammar: letter + letters/digits/._-). */
    val MODULE_ID_REGEX: Regex get() = ModuleProp.MODULE_ID_REGEX

    /** Parses a `.prop` payload (delegates to [ModuleProp]). */
    fun parseProps(raw: String): Map<String, String> = ModuleProp.parse(raw)

    /** Parses a `.prop` file if it exists and is a regular readable file. */
    fun readPropsFile(file: File): Map<String, String> = ModuleProp.parseFile(file)

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
     */
    fun buildModuleEnvironment(
        module: AdbModule,
        cwd: File = module.directory,
        extraEnv: Map<String, String> = emptyMap()
    ): Array<String> {
        val mode = ModuleSettings.getAccessMode().value
        val trusted = if (ModuleSettings.isModuleTrusted(module.id)) "1" else "0"
        val background = if (ModuleSettings.canRunBackground(module)) "1" else "0"

        val env = linkedMapOf<String, String>()
        extraEnv.forEach { (k, v) -> env[k] = v }

        env["MODDIR"] = module.directory.absolutePath
        env["SHIZUKU_MODULE_CWD"] = cwd.absolutePath
        env["ASH_STANDALONE"] = "1"
        env["SHIZUKU_MODULE_ID"] = module.id
        env["SHIZUKU_MODULE_MODE"] = mode
        env["SHIZUKU_MODULE_TRUSTED"] = trusted
        env["SHIZUKU_MODULE_BACKGROUND"] = background

        return env.map { (k, v) -> "$k=$v" }.toTypedArray()
    }
}
