package af.shizuku.manager.module

import java.io.InputStream
import java.io.OutputStream

/**
 * Centralized enforcement of the 8 ADB-module safety constraints (roadmap).
 *
 * Pure JVM (no Android dependencies) so every constraint is directly unit-testable
 * (ModuleValidatorTest). All throw [IllegalArgumentException] on violation so
 * callers fail loudly at the point of attack instead of silently continuing.
 */
object ModuleValidator {

    // -- Constraint limits (constraint #3/#4/#1/#5/#6/#7) ---------------------
    const val MAX_PROP_BYTES = 64L * 1024L            // #1: module.prop size cap
    const val MAX_EXTRACTED_BYTES = 200L * 1024L * 1024L // #3: total extracted cap (ZIP bomb)
    const val MAX_ENTRY_COUNT = 2048                  // #4: entry count cap
    const val MAX_SCRIPT_BYTES = 256 * 1024           // #5: script size cap
    const val MAX_SCRIPT_SECONDS = 120L               // #6: execution timeout
    const val MAX_OUTPUT_CHARS = 64 * 1024            // #7: output cap

    /**
     * Constraint #2 (path traversal): normalizes a relative ZIP entry name and rejects
     * blank segments, `.`, `..`, absolute paths and any backslash/forward-slash tricks.
     */
    fun cleanRelativePath(path: String): String {
        val clean = path.replace('\\', '/').trim('/')
        require(clean.isNotBlank()) { "Invalid relative path." }
        // Regex.matches() is a FULL match — use containsMatchIn so a drive-rooted
        // prefix ("C:/...") is caught, not only a bare "C:" input.
        require(!clean.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(clean)) {
            "Absolute path is not allowed: $path"
        }
        val parts = clean.split('/')
        require(parts.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe relative path: $path"
        }
        return clean
    }

    /**
     * Constraint #4: rejects archives with too many entries.
     */
    fun checkEntryCount(count: Int) {
        require(count <= MAX_ENTRY_COUNT) { "Module ZIP has too many files (max $MAX_ENTRY_COUNT)." }
    }

    /**
     * Constraint #1: rejects oversized module.prop payloads.
     */
    fun checkPropSize(bytes: Long) {
        require(bytes <= MAX_PROP_BYTES) { "module.prop exceeds maximum allowed size ($MAX_PROP_BYTES bytes)." }
    }

    /**
     * Constraint #5: rejects oversized scripts.
     */
    fun checkScriptSize(bytes: Long) {
        require(bytes <= MAX_SCRIPT_BYTES) { "Script exceeds maximum allowed size ($MAX_SCRIPT_BYTES bytes)." }
    }

    /**
     * Constraint #3 (ZIP bomb): copies [input] to [output] while enforcing a hard byte cap.
     * Throws [IllegalArgumentException] once the cumulative copy exceeds [maxBytes].
     */
    fun copyStreamBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        val buf = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buf)
            if (read < 0) break
            if (read == 0) continue
            copied += read.toLong()
            require(copied <= maxBytes) { "Module ZIP exceeds maximum allowed size ($maxBytes bytes)." }
            output.write(buf, 0, read)
        }
        return copied
    }

    /**
     * Verifies that [child] resolves canonically inside [root] (symlink-safe containment).
     */
    fun ensureInside(root: java.io.File, child: java.io.File) {
        val rootPath = root.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        require(childPath.startsWith(rootPath)) {
            "Unsafe module path: ${child.name}"
        }
    }

    /** Non-throwing variant of [ensureInside] for probes. */
    fun isInside(root: java.io.File, child: java.io.File): Boolean =
        runCatching { ensureInside(root, child) }.isSuccess
}
