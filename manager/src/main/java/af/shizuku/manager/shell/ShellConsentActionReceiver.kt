package af.shizuku.manager.shell

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.utils.Logger.LOGGER

/**
 * Handles direct "Allow" / "Deny" taps on shell consent notification action buttons.
 *
 * Using action buttons avoids opening [af.shizuku.manager.legacy.ShellConsentActivity] entirely
 * for callers that are already familiar. The consent key travels as a plain string through the
 * PendingIntent (safe — strings, unlike IBinder, are not dropped by Android 15+ PendingIntent
 * serialization), and the live callback binder is fetched from [PendingConsentStore] here.
 */
class ShellConsentActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALLOW = "af.shizuku.plus.ACTION_SHELL_CONSENT_ALLOW"
        const val ACTION_DENY = "af.shizuku.plus.ACTION_SHELL_CONSENT_DENY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val consentKey = intent.getStringExtra(PendingConsentStore.EXTRA_CONSENT_KEY) ?: return
        val callbackBinder = PendingConsentStore.take(consentKey) ?: return

        val notificationId = consentKey.hashCode()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(notificationId)

        when (intent.action) {
            ACTION_ALLOW -> {
                val callingPackage = intent.getStringExtra("callingPackage")
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (callingPackage != null) {
                            val callingUid = try {
                                context.packageManager.getApplicationInfo(callingPackage, 0).uid
                            } catch (_: Exception) { null }
                            if (callingUid != null) {
                                AuthorizationManager.grant(callingPackage, callingUid)
                            }
                        }
                        ShellBinderRequestHandler.deliverBinder(context, callbackBinder)
                    } catch (e: Exception) {
                        LOGGER.w(e, "ShellConsentActionReceiver: deliver failed")
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_DENY -> {
                // callbackBinder was taken from the store and discarded — rish will time out.
            }
        }
    }
}
