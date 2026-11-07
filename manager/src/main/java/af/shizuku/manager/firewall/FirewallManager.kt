package af.shizuku.manager.firewall

import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

/**
 * The firewall switch and the per-app rules, behind one API for the UI.
 *
 * Reads go through the binder interface first: one binder call per app is what makes listing a
 * few hundred apps possible without spawning a shell process per row. Writes go through
 * `cmd connectivity` first and are read back - its exit code is not the answer, because a ROM
 * can accept the verb and leave the rule where it was. Where that front end is missing or takes
 * no effect, the same rule is written through the interface behind it.
 *
 * The chain is shared on purpose: ShizuWall and anything else blocking through this platform
 * mechanism write the same chain, so this app never unblocks what it did not block.
 */
object FirewallManager {

    private const val CMD_GET_CHAIN = "cmd connectivity get-chain3-enabled"
    private const val CMD_SET_CHAIN = "cmd connectivity set-chain3-enabled"

    /** False when the Shizuku service is not running: every call below needs it. */
    fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Whether the chain is on, or null when neither the command nor the interface behind it
     * could say. Null is a real answer and is shown as itself rather than guessed as "off".
     */
    fun chainOn(): Boolean? {
        // Read once: asking twice could answer the two halves against two different states.
        val answer = shell(CMD_GET_CHAIN)?.trim()
        when {
            answer?.endsWith(":enabled") == true -> return true
            answer?.endsWith(":disabled") == true -> return false
        }
        return FirewallApi.chainEnabled()
    }

    /**
     * Switches the chain on or off, answering whether it is in the state that was asked for.
     *
     * The command's exit code is not the answer: the state is read back, and the interface is
     * tried when the command's answer is not the one that was asked for.
     */
    fun setChain(on: Boolean): Boolean {
        shell("$CMD_SET_CHAIN ${if (on) "true" else "false"}")
        if (chainOn() == on) return true

        if (!FirewallApi.setChainEnabled(on)) return false
        return chainOn() == on
    }

    /**
     * The blocked state of every uid in [uids], or null when the platform cannot be asked.
     *
     * Nothing is spawned here: this is one binder call per uid, which is why the list can be
     * filled in before the user sees it. Only uids the interface actually answered for are in the
     * map; a uid it could not be asked about stays unknown rather than being reported as allowed.
     */
    fun rulesSnapshot(uids: Collection<Int>): Map<Int, Boolean>? {
        if (!isAvailable()) return null
        val answered = HashMap<Int, Boolean>(uids.size)
        var any = false
        for (uid in uids) {
            val rule = FirewallApi.blockedRule(uid) ?: continue
            any = true
            FirewallApi.isBlocked(rule)?.let { answered[uid] = it }
        }
        return if (any) answered else null
    }

    /** Whether [packageName] is blocked, read back rather than remembered. */
    fun isBlocked(uid: Int, packageName: String): Boolean? {
        FirewallApi.isBlocked(FirewallApi.blockedRule(uid))?.let { return it }

        val answer = shell("cmd connectivity get-package-networking-enabled $packageName")?.trim()
        return when {
            answer?.endsWith(":deny") == true -> true
            answer?.endsWith(":allow") == true -> false
            else -> null
        }
    }

    /**
     * Blocks or allows this app's traffic with the platform's own firewall, no VPN involved.
     *
     * The bit lives in a chain that is off by default and does nothing until it is on, so the
     * chain is switched on first. It is left on afterwards on purpose: switching it off would
     * let every app anything else has blocked through it, which is not this switch's business.
     *
     * Answers whether the platform ended up in the state that was asked for, read back through
     * the channel that wrote it - the other channel may be the half that does not work here.
     */
    fun setBlocked(uid: Int, packageName: String, blocked: Boolean): Boolean {
        if (chainOn() != true) setChain(true)

        // The command's flag is the deny bit inverted: "false" means "do not allow", i.e. deny.
        val value = if (blocked) "false" else "true"
        var applied = shell("cmd connectivity set-package-networking-enabled $value $packageName") != null &&
            isBlocked(uid, packageName) == blocked

        if (!applied && FirewallApi.setBlocked(uid, blocked)) {
            applied = FirewallApi.isBlocked(FirewallApi.blockedRule(uid)) == blocked
        }
        return applied
    }

    /**
     * Runs one shell command as the Shizuku user and answers its stdout, or null when it did not
     * run or did not succeed.
     *
     * These commands are short-lived and print one line, so there is no timeout machinery here;
     * the process is destroyed in the finally block so a binder death mid-command cannot leak it.
     */
    private fun shell(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            process.errorStream.bufferedReader().readText()
            if (process.waitFor() == 0) out else null
        } catch (t: Throwable) {
            LOGGER.w("firewall shell failed: ${t.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // A dead binder makes destroy() throw as well; nothing left to clean up then.
            }
        }
    }
}
