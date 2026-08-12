package af.shizuku.manager.shell

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
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
                val intentCallingUid = intent.getIntExtra("callingUid", -1).takeIf { it >= 0 }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        var appLabel = callingPackage
                        // Prefer PM-derived UID (verifies package ownership); fall back to
                        // the UID from the intent (set by ShizukuShellLoader, #391).
                        val callingUid = if (callingPackage != null) {
                            try {
                                val info = context.packageManager.getApplicationInfo(callingPackage, 0)
                                appLabel = context.packageManager.getApplicationLabel(info).toString()
                                info.uid
                            } catch (_: Exception) { intentCallingUid }
                        } else intentCallingUid
                        if (callingUid != null) {
                            AuthorizationManager.grant(callingPackage ?: "", callingUid)
                        }
                        ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: allowed always (notification)")
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
                val callingPackage = intent.getStringExtra("callingPackage")
                val appLabel = callingPackage?.let {
                    try {
                        val info = context.packageManager.getApplicationInfo(it, 0)
                        context.packageManager.getApplicationLabel(info).toString()
                    } catch (_: Exception) { it }
                }
                ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: denied (notification)")
            }
        }
    }
}
