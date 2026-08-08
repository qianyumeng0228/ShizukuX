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
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.legacy.ShellConsentActivity
import af.shizuku.manager.shell.PendingConsentStore
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.shell.ShellConsentActionReceiver
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
            ShellBinderRequestHandler.handleRequest(context, intent, requireAuth = true)
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
            // Fast path: if the caller is already permanently authorized, skip the consent
            // notification and deliver directly (#398 — "Allow always" was not persisting
            // because this check was absent; every new rish process re-prompted even though
            // AuthorizationManager.grant() had already been stored for that UID).
            val callingPackage = intent.getStringExtra("callingPackage")
            if (callingPackage != null) {
                try {
                    val callingUid = context.packageManager.getApplicationInfo(callingPackage, 0).uid
                    if (AuthorizationManager.granted(callingPackage, callingUid)) {
                        ShellBinderRequestHandler.deliverBinder(context, callbackBinder)
                        return
                    }
                } catch (_: Exception) {
                    // Check failed — fall through to the notification consent path below.
                }
            }
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
            postConsentNotification(context, intent, callbackBinder)
        }
    }

    private fun postConsentNotification(context: Context, intent: Intent, callbackBinder: IBinder) {
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
        val appLabel = callingPackage?.let { pkg ->
            try {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString()
            } catch (_: Exception) { null }
        }
        val consentIntent = Intent(context, ShellConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
            callingPackage?.let { putExtra("callingPackage", it) }
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
        // Strings (consentKey, callingPackage) survive PendingIntent serialization safely;
        // the live binder stays in PendingConsentStore and is fetched inside the receiver.
        val allowIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            Intent(ShellConsentActionReceiver.ACTION_ALLOW, null, context, ShellConsentActionReceiver::class.java).apply {
                putExtra(PendingConsentStore.EXTRA_CONSENT_KEY, consentKey)
                callingPackage?.let { putExtra("callingPackage", it) }
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
