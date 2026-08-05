package af.shizuku.manager.shell

import android.app.NotificationManager
import android.content.Context
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
     *
     * When the caller dies (rish timed out), the death recipient auto-removes the entry and
     * cancels the notification ([context] is used for that cancellation only).
     */
    fun put(binder: IBinder, context: Context): String? {
        val key = UUID.randomUUID().toString()
        store[key] = binder
        return try {
            // Auto-remove if the caller dies (rish timed out / was killed).
            // Also cancel the pending consent notification so it doesn't linger in the shade
            // after rish is already gone — the user tapping a stale notification would open
            // ShellConsentActivity, get null from take(), and see the dialog silently dismiss.
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            binder.linkToDeath({
                store.remove(key)
                nm?.cancel(key.hashCode())
            }, 0)
            key
        } catch (_: android.os.RemoteException) {
            // linkToDeath throws RemoteException when the binder is already dead.
            // Remove the entry and return null so the caller skips notification posting.
            store.remove(key)
            null
        }
    }

    /** Removes and returns the binder for [key]. Returns null if expired or unknown. */
    fun take(key: String): IBinder? = store.remove(key)
}
