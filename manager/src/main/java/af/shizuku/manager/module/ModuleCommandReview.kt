package af.shizuku.manager.module

/**
 * Command review gate — constraint #8 (every execution must surface the full command
 * for explicit user confirmation).
 *
 * Pure-JVM model + policy so it is unit-testable (ModuleCommandTest). The Compose
 * dialog that renders [ModuleCommandRequest] for user approval is wired in a later stage; the
 * reviewer hook is invoked by the module runner before any script executes.
 */
enum class ModuleCommandSource {
    WEB_UI,
    ACTION
}

data class ModuleCommandRequest(
    val module: AdbModule,
    val source: ModuleCommandSource,
    val command: String
)

fun interface ModuleCommandReviewer {
    fun confirmCommand(request: ModuleCommandRequest): Boolean
}

/**
 * Default policy: in SAFE mode only whitelisted commands may run unattended; any
 * command not on the whitelist requires explicit confirmation.
 */
object ModuleCommandPolicy {

    /** Scripts/commands that are safe to run in SAFE mode without a per-run prompt. */
    private val SAFE_WHITELIST = setOf(
        "id", "pwd", "echo", "ls", "cat", "head", "tail", "uname",
        "date", "whoami", "env", "printenv", "true", "false"
    )

    /** Binaries that are always dangerous regardless of mode. */
    private val ALWAYS_DANGEROUS = setOf(
        "rm", "mkfs", "dd", "shred", "reboot", "poweroff", "shutdown",
        "format", "fdisk", "parted", "killall", "pkill", "iptables",
        "mount", "umount", "chmod", "chown", "su", "setprop", "am", "pm"
    )

    /**
     * Returns true if the command may run without an explicit per-command prompt.
     * Always false for unknown binaries in SAFE mode — the safest default.
     */
    fun isSafeToAutoRun(command: String, accessMode: ModuleSettings.AccessMode): Boolean {
        if (command.isBlank()) return false
        val binary = command.trim().substringBefore(' ').substringBefore('\n').trim()
        if (binary.isEmpty()) return false
        if (binary in ALWAYS_DANGEROUS) return false
        return when (accessMode) {
            ModuleSettings.AccessMode.SAFE -> binary in SAFE_WHITELIST
            ModuleSettings.AccessMode.FULL -> true
            ModuleSettings.AccessMode.CUSTOM -> binary in SAFE_WHITELIST
        }
    }

    /**
     * Classifies a command for display: returns a short reason why it needs review,
     * or null if it passes the policy gate.
     */
    fun whyNeedsReview(command: String, accessMode: ModuleSettings.AccessMode): String? {
        if (command.isBlank()) return "empty command"
        val binary = command.trim().substringBefore(' ').substringBefore('\n').trim()
        return when {
            binary.isEmpty() -> "empty command"
            binary in ALWAYS_DANGEROUS -> "dangerous binary: $binary"
            accessMode == ModuleSettings.AccessMode.SAFE && binary !in SAFE_WHITELIST ->
                "not on SAFE-mode whitelist: $binary"
            accessMode == ModuleSettings.AccessMode.CUSTOM && binary !in SAFE_WHITELIST ->
                "not allowed by CUSTOM permissions"
            else -> null
        }
    }
}
