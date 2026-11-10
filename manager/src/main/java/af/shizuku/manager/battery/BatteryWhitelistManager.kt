package af.shizuku.manager.battery

import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

/**
 * The platform's Doze whitelist (`dumpsys deviceidle whitelist`), read and written through the
 * Shizuku service's shell.
 *
 * This is the same list the system's "Battery optimization / Don't optimize" screen edits:
 * `user,<pkg>,<uid>` entries are the ones a user added, `system` and `system-excidle` entries ship
 * with the platform and cannot be removed with `whitelist -<pkg>`. The kind is therefore kept
 * instead of only a set of names - a switch that is rendered on a package the command cannot
 * remove would be a lie the first time somebody tapped it.
 *
 * Nothing is remembered locally: every read asks the platform, and every write is read back before
 * it is reported, because `dumpsys deviceidle whitelist +<pkg>` exits 0 on ROMs that ignore it.
 *
 * `dumpsys deviceidle whitelist =<pkg>` is deliberately never used: measured on Android 16, that
 * query answers only for the *user* list, so a `system` / `system-excidle` package reads back
 * `false` while it is still exempt. The full list is the only answer that matches the screen.
 */
object BatteryWhitelistManager {

    private const val CMD_WHITELIST = "dumpsys deviceidle whitelist"

    /** Where an entry comes from. User entries are the only removable ones. */
    enum class Kind { USER, SYSTEM, SYSTEM_EXCIDLE, OTHER }

    data class Snapshot(
        val entries: Map<String, Kind> = emptyMap(),
        /** Set when the command ran but could not be trusted (no DUMP permission, etc.). */
        val error: String? = null,
    )

    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Every whitelisted package with its strongest source.
     *
     * A package can be listed more than once (user + system); the user entry wins, because that is
     * the one `whitelist -` acts on.
     */
    fun read(): Snapshot {
        if (!isAvailable()) return Snapshot(error = NO_SERVICE)
        val out = shell(CMD_WHITELIST) ?: return Snapshot(error = NO_SERVICE)
        if (out.contains("Permission Denial", ignoreCase = true) ||
            out.contains("SHIZUKU_NO_PRIVILEGE", ignoreCase = true)
        ) {
            return Snapshot(error = NO_DUMP_PERMISSION)
        }

        val entries = LinkedHashMap<String, Kind>()
        for (raw in out.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val parts = line.split(',')
            if (parts.size != 3) continue
            val kind = parts[0].trim()
            val pkg = parts[1].trim()
            val uid = parts[2].trim().toIntOrNull() ?: continue
            if (pkg.isEmpty() || uid < 0) continue
            if (!pkg.matches(PACKAGE_NAME)) continue

            val parsed = when (kind) {
                "user" -> Kind.USER
                "system" -> Kind.SYSTEM
                "system-excidle" -> Kind.SYSTEM_EXCIDLE
                else -> Kind.OTHER
            }
            val existing = entries[pkg]
            entries[pkg] = when {
                existing == Kind.USER -> Kind.USER
                parsed == Kind.USER -> Kind.USER
                existing == Kind.SYSTEM_EXCIDLE -> Kind.SYSTEM_EXCIDLE
                else -> parsed
            }
        }

        if (entries.isEmpty()) {
            // An empty answer is only believable when the platform printed a header; a bare
            // permission failure or an unrecognised format would otherwise read as "nothing is
            // exempt", which is the opposite of the truth on a device with a full list.
            val looksEmpty = out.isBlank() || out.lines().all { it.trim().isEmpty() }
            if (!looksEmpty && !out.contains("Whitelist", ignoreCase = true)) {
                return Snapshot(entries = emptyMap(), error = UNRECOGNISED)
            }
        }
        return Snapshot(entries = entries)
    }

    /** What a write actually did. Removals can be refused for reasons other than a failed command. */
    enum class WriteResult {
        OK,

        /**
         * The package is still exempt after a removal because the platform keeps it in its own
         * `system` / `system-excidle` list. `whitelist -<pkg>` only edits the user list, so this is
         * not a failed command - it is a state this screen cannot change.
         */
        SYSTEM_ENTRY,
        FAILED,
    }

    /**
     * Adds or removes [packageName] and answers what the platform ended up in.
     *
     * Only `user` entries can be removed; asking to remove a system entry returns [WriteResult.SYSTEM_ENTRY]
     * without running anything, so the caller reports a refusal instead of a failed command.
     */
    fun setExempt(packageName: String, exempt: Boolean): WriteResult {
        // The package name is interpolated into a shell command; reject anything that could end the
        // argument or start a second one instead of trusting the caller.
        if (!packageName.matches(PACKAGE_NAME)) return WriteResult.FAILED
        if (!isAvailable()) return WriteResult.FAILED
        val before = read()
        if (before.error != null) return WriteResult.FAILED

        val kind = before.entries[packageName]
        if (!exempt && kind != null && kind != Kind.USER) return WriteResult.SYSTEM_ENTRY

        val verb = if (exempt) "+" else "-"
        shell("$CMD_WHITELIST $verb$packageName")
        // Read back: the verb is accepted even by ROMs that ignore it.
        val after = read()
        if (after.error != null) return WriteResult.FAILED
        val left = after.entries[packageName]
        val actual = left != null
        if (actual == exempt) return WriteResult.OK

        // A removal that leaves the package in the platform's own list is the one case that is
        // not a failure: the command worked, the entry was never the user's to remove.
        if (!exempt && left != null && left != Kind.USER) return WriteResult.SYSTEM_ENTRY

        LOGGER.w("battery whitelist unchanged for $packageName (wanted $exempt)")
        return WriteResult.FAILED
    }

    /**
     * Runs one command as the Shizuku user and answers its stdout, or null when it did not run.
     *
     * stderr is drained before waitFor() so a command that writes a long error cannot block on a
     * full pipe; that hang used to look like a slow refresh rather than a failed one.
     */
    private fun shell(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = process.inputStream.bufferedReader().readText()
            process.errorStream.bufferedReader().readText()
            if (process.waitFor() == 0) out.trim() else null
        } catch (t: Throwable) {
            LOGGER.w("battery whitelist shell failed: ${t.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // A dead binder makes destroy() throw too; nothing left to clean up.
            }
        }
    }

    private val PACKAGE_NAME = Regex("[A-Za-z0-9_.]+")

    const val NO_SERVICE = "SERVICE_NOT_RUNNING"
    const val NO_DUMP_PERMISSION = "NO_DUMP_PERMISSION"
    const val UNRECOGNISED = "OUTPUT_NOT_RECOGNISED"
}
