package af.shizuku.manager.firewall

import android.os.IBinder
import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The platform firewall (FIREWALL_CHAIN_OEM_DENY_3) reached through IConnectivityManager.
 *
 * `cmd connectivity set-chain3-enabled` and `set-package-networking-enabled` are the front end
 * for the calls below; a ROM that trimmed those verbs still answers through the interface behind
 * them, which is the whole reason this exists. Ported from shizuku-next (`manage/FirewallApi.kt`),
 * which credits ShizuWall for working the transaction shapes out.
 *
 * What each call takes was read from `IConnectivityManager.aidl`, including the two ways to ask
 * the state back - which is what lets a switch verify that it did what it said.
 */
object FirewallApi {

    private const val TAG = "FirewallApi"

    /**
     * `ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3`, which is what `chain3` names in
     * `cmd connectivity`. A number rather than a name because the constant lives in a hidden
     * class; the platform's own help text pins the meaning.
     */
    const val CHAIN = 9

    /** `ConnectivityManager.FIREWALL_RULE_ALLOW` and `FIREWALL_RULE_DENY`. */
    const val RULE_ALLOW = 1
    const val RULE_DENY = 2

    /** `ConnectivityManager.FIREWALL_RULE_DEFAULT`. */
    private const val RULE_DEFAULT = 0

    /** The rule a switch means: blocking denies, and unblocking allows. */
    fun ruleFor(blocked: Boolean): Int = if (blocked) RULE_DENY else RULE_ALLOW

    /**
     * What a rule from the platform means, or null for one this app does not recognise.
     *
     * The default rule is an answer rather than a gap: a uid with none set is not blocked by this
     * chain, which is what the switch shows.
     */
    fun isBlocked(rule: Int?): Boolean? = when (rule) {
        RULE_DENY -> true
        RULE_ALLOW, RULE_DEFAULT -> false
        else -> null
    }

    /** Whether the chain is on, or null when the platform cannot be asked. */
    fun chainEnabled(): Boolean? = withService { service ->
        service.javaClass
            .getMethod("getFirewallChainEnabled", Int::class.javaPrimitiveType)
            .invoke(service, CHAIN) as Boolean
    }

    /**
     * Switches the chain on or off, answering whether the platform took the call.
     *
     * The call is void, and `invoke` answers null for a void method: reaching the end of the
     * block is the answer, not that null.
     */
    fun setChainEnabled(enabled: Boolean): Boolean = withService { service ->
        service.javaClass
            .getMethod(
                "setFirewallChainEnabled",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            )
            .invoke(service, CHAIN, enabled)
        true
    } == true

    /** The rule set for [uid] on this chain, or null when the platform cannot be asked. */
    fun blockedRule(uid: Int): Int? = withService { service ->
        service.javaClass
            .getMethod(
                "getUidFirewallRule",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .invoke(service, CHAIN, uid) as Int
    }

    /** Blocks or allows [uid] on this chain, answering whether the platform took the call. */
    fun setBlocked(uid: Int, blocked: Boolean): Boolean = withService { service ->
        service.javaClass
            .getMethod(
                "setUidFirewallRule",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            .invoke(service, CHAIN, uid, ruleFor(blocked))
        true
    } == true

    /**
     * Calls [block] with the connectivity interface, or answers null when there is nothing to call.
     *
     * The interface is fetched and made over on every call rather than kept: it is two binder
     * lookups, and a cached proxy would be a cached set of assumptions about a service that can
     * be restarted underneath it.
     */
    private fun <T> withService(block: (Any) -> T): T? {
        val service = runCatching {
            val binder: IBinder = SystemServiceHelper.getSystemService("connectivity")
                ?: return null

            Class.forName("android.net.IConnectivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, ShizukuBinderWrapper(binder))
        }
            .onFailure { LOGGER.w("$TAG: connectivity interface unreachable: ${it.message}") }
            .getOrNull() ?: return null

        return runCatching { block(service) }
            .onFailure { LOGGER.w("$TAG: connectivity refused the call: ${it.message}") }
            .getOrNull()
    }
}
