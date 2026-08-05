package af.shizuku.manager.shell

import android.os.IBinder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store for shell consent callback binders.
 *
 * Android 15+ (API 35+) does not reliably preserve custom IBinder objects placed in
 * PendingIntent extras — the binder arrives null when the notification fires and
 * ShellConsentActivity reads it. By keeping the binder here and only passing a
 * lightweight string key through the PendingIntent, the binder is looked up from
 * the live manager process instead of being re-parcelled across the system service.
 *
 * Entries are removed as soon as the consent is consumed (Allow/Deny) or when the
 * calling binder dies (rish timed out).
 */
object PendingConsentStore {

    const val EXTRA_CONSENT_KEY = "af.shizuku.plus.CONSENT_KEY"

    private val store = ConcurrentHashMap<String, IBinder>()

    /**
     * Stores [binder] and returns a key to retrieve it later, or null if [binder] is already
     * dead (linkToDeath threw). Callers should not post the consent notification when null is
     * returned — the requesting process is gone and there is nobody to send the grant to.
     */
    fun put(binder: IBinder): String? {
        val key = UUID.randomUUID().toString()
        store[key] = binder
        return try {
            // Auto-remove if the caller dies (rish timed out / was killed).
            binder.linkToDeath({ store.remove(key) }, 0)
            key
        } catch (_: Exception) {
            // linkToDeath throws RemoteException when the binder is already dead.
            // Remove the entry and return null so the caller skips notification posting.
            store.remove(key)
            null
        }
    }

    /** Removes and returns the binder for [key]. Returns null if expired or unknown. */
    fun take(key: String): IBinder? = store.remove(key)
}
