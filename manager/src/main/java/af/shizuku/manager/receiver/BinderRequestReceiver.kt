package af.shizuku.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.legacy.ShellConsentActivity
import af.shizuku.manager.shell.PendingConsentStore
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.shell.ShellConsentActionReceiver
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.utils.IntentCrypto
import java.security.MessageDigest
import timber.log.Timber

class BinderRequestReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "shell_consent"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER" &&
            intent.action != "${context.packageName}.intent.action.REQUEST_BINDER") {
            return
        }

        val rawToken = intent.getStringExtra("auth")
        val authToken = if (rawToken != null) IntentCrypto.decrypt(rawToken) else null
        val expectedToken = ShizukuSettings.getAuthToken()
        // Constant-time compare: this gates handing out the live Shizuku binder.
        val authValid = authToken != null &&
            MessageDigest.isEqual(authToken.toByteArray(), expectedToken.toByteArray())

        if (authValid) {
            // deliverBinder() may Thread.sleep() up to 2.3 s on freeze-retry — move off main thread.
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ShellBinderRequestHandler.handleRequest(context, intent, requireAuth = true)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // No/invalid auth token - the path every rish/shell client takes today, since
        // IntentCrypto's AndroidKeyStore key is scoped to this app's own UID and a shell
        // process can never produce a token that decrypts correctly (GH #368/#372/#374).
        // Ask the user for one-time consent instead of silently dropping the request, but
        // only if there's a live callback binder to reply to - otherwise there's nothing
        // to grant access to.
        val callbackBinder = intent.getBundleExtra("data")?.getBinder("binder")
        if (callbackBinder != null) {
            // SECURITY: there used to be a "fast path" here that trusted intent-supplied
            // callingPackage/callingUid extras to silently auto-deliver the live Shizuku binder
            // for already-authorized callers, skipping the consent notification (added for #398).
            // Broadcast Intent extras carry NO verified sender identity - Binder.getCallingUid()
            // is not meaningful in onReceive() for a plain sendBroadcast(), and this action
            // (rikka.shizuku.intent.action.REQUEST_BINDER) is the public, unauthenticated part of
            // Shizuku's client API that any app on the device can send. That fast path let ANY
            // installed app claim callingPackage="<any already-authorized package>" and supply
            // its OWN callbackBinder, resolve that package's real (public) UID via
            // PackageManager, pass AuthorizationManager.granted() using someone else's real grant,
            // and receive the live, full-privilege Shizuku service binder directly into its own
            // process - a complete, silent, zero-interaction privilege escalation requiring only
            // that ANY app on the device had ever been authorized (an extremely common state).
            // There is no reliable way to verify broadcast sender identity here, so every
            // unauthenticated request now always requires explicit user consent via the
            // notification below - the #398 UX convenience (silently skipping the prompt for
            // already-approved callers) cannot be safely restored without a different mechanism
            // that doesn't trust spoofable Intent extras (e.g. verifying identity inside a real
            // AIDL transaction on the server, which does have a trustworthy Binder.getCallingUid()).
            val intentCallingUid = intent.getIntExtra("callingUid", -1).takeIf { it >= 0 }
            // A manifest-registered BroadcastReceiver has no visible UI, so a direct
            // startActivity() here is exactly the pattern Android's background-activity-start
            // (BAL) restrictions are designed to block - on modern OEM builds (e.g. Samsung
            // One UI) it is silently dropped, ShellConsentActivity never appears, and
            // ShizukuShellLoader's 15s timeout fires with a misleading "may be blocked by your
            // system / disable battery optimization" message (#377). Route through a
            // notification instead: tapping it is a user-initiated foreground action and is
            // exempt from BAL, so the consent dialog reliably shows up.
            //
            // Android 15+ (API 35) does not reliably preserve IBinder objects embedded in
            // PendingIntent extras — the binder arrives null when the notification fires (#387).
            // Store it in PendingConsentStore and pass only a lightweight key in the intent.
            postConsentNotification(context, intent, callbackBinder, intentCallingUid)
        }
    }

    private fun postConsentNotification(context: Context, intent: Intent, callbackBinder: IBinder, intentCallingUid: Int? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            // notify() below would silently no-op without this - same symptom as the original
            // BAL-blocked startActivity (#377) with no visible cause. Home screen now requests
            // POST_NOTIFICATIONS proactively, but log this so a still-blocked case is diagnosable
            // instead of reproducing the exact same "mysteriously still times out" report.
            Timber.tag("BinderRequestReceiver").w(
                "Notifications disabled for %s - shell consent request will silently fail to display",
                context.packageName
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_shell_consent),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        // Store the callback binder in-memory. Android 15+ (API 35) does not reliably
        // deliver IBinder objects through PendingIntent extras — the binder arrives null when
        // ShellConsentActivity reads it (#387). We pass only the key; the activity takes the
        // live binder from PendingConsentStore eagerly in onCreate.
        // put() returns null when the binder is already dead — don't show a notification that
        // can't possibly deliver anything.
        val consentKey = PendingConsentStore.put(callbackBinder, context) ?: return

        val callingPackage = intent.getStringExtra("callingPackage")
        // appLabel: try PM lookup; fall back to package name; then UID string (#391 — some
        // devices/callers can't be resolved via PM but the package name is still display-useful).
        val appLabel = callingPackage?.let { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { pkg }
        }
        ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: consent requested")
        val consentIntent = Intent(context, ShellConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
            callingPackage?.let { putExtra("callingPackage", it) }
            // Pass callingUid so ShellConsentActivity can grant authorization even when
            // PackageManager.getApplicationInfo() fails (e.g. classic rish_shizuku.dex, #391).
            intentCallingUid?.let { putExtra("callingUid", it) }
        }
        // Use the key's hash as both the PendingIntent requestCode and the notification ID so
        // concurrent consent requests each get their own slot in the shade. A shared ID would
        // let a second nm.notify() silently replace the first notification — the first request's
        // binder would be orphaned with no UI to deliver it. The death recipient in
        // PendingConsentStore cancels the notification if rish times out before the user taps.
        val notificationId = consentKey.hashCode()
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            consentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notifTitle = if (appLabel != null)
            context.getString(R.string.notification_shell_consent_title_identified, appLabel)
        else
            context.getString(R.string.notification_shell_consent_title)
        val notifText = if (appLabel != null)
            context.getString(R.string.notification_shell_consent_text_identified)
        else
            context.getString(R.string.notification_shell_consent_text)

        // Action intents: explicit component + exported=false keeps these internal.
        // Strings (consentKey, callingPackage, callingUid) survive PendingIntent serialization
        // safely; the live binder stays in PendingConsentStore and is fetched inside the receiver.
        val allowIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            Intent(ShellConsentActionReceiver.ACTION_ALLOW, null, context, ShellConsentActionReceiver::class.java).apply {
                putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
                callingPackage?.let { putExtra("callingPackage", it) }
                intentCallingUid?.let { putExtra("callingUid", it) }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val denyIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            Intent(ShellConsentActionReceiver.ACTION_DENY, null, context, ShellConsentActionReceiver::class.java).apply {
                putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(notifTitle)
            .setContentText(notifText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notification_shell_consent_action_allow), allowIntent)
            .addAction(0, context.getString(R.string.notification_shell_consent_action_deny), denyIntent)
            .build()

        nm.notify(notificationId, notification)
    }
}
