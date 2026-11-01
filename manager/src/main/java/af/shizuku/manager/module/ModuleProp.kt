package af.shizuku.manager.module

/**
 * Pure-JVM parser for `module.prop` (and other `.prop`) payloads.
 *
 * Format: `key=value` lines, ignoring blank lines and `#` comments. Values may contain `=`
 * (only the first `=` is the separator). Keys/values are trimmed.
 *
 * Kept free of Android dependencies so it is directly unit-testable (P1-4 ModulePropTest).
 */
object ModuleProp {

    /**
     * Parses a `.prop` text payload into an insertion-ordered map.
     * Malformed lines are skipped, never thrown.
     */
    fun parse(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate { line ->
                val index = line.indexOf('=')
                line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
    }

    /** Parses a `.prop` file if it exists and is readable, otherwise an empty map. */
    fun parseFile(file: java.io.File): Map<String, String> {
        if (!file.isFile || !file.canRead()) return emptyMap()
        return runCatching { parse(file.readText(Charsets.UTF_8)) }.getOrDefault(emptyMap())
    }

    /**
     * Validates a module id against the canonical ADB-module id grammar:
     * starts with a letter, then letters/digits/`._-`, 2..64 chars total.
     */
    val MODULE_ID_REGEX = Regex("[A-Za-z][A-Za-z0-9._-]{1,63}")

    fun isValidModuleId(id: String): Boolean = MODULE_ID_REGEX.matches(id)
}
