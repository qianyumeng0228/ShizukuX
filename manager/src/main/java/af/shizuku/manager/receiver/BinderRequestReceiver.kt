package af.shizuku.manager.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.legacy.ShellConsentActivity
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.utils.IntentCrypto
import java.security.MessageDigest
import timber.log.Timber

class BinderRequestReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "shell_consent"
        private const val NOTIFICATION_ID = 1451
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
            ShellBinderRequestHandler.handleRequest(context, intent, true)
            return
        }

        // No/invalid auth token - the path every rish/shell client takes today, since
        // IntentCrypto's AndroidKeyStore key is scoped to this app's own UID and a shell
        // process can never produce a token that decrypts correctly (GH #368/#372/#374).
        // Ask the user for one-time consent instead of silently dropping the request, but
        // only if there's a live callback binder to reply to - otherwise there's nothing
        // to grant access to.
        if (intent.getBundleExtra("data")?.getBinder("binder") != null) {
            // A manifest-registered BroadcastReceiver has no visible UI, so a direct
            // startActivity() here is exactly the pattern Android's background-activity-start
            // (BAL) restrictions are designed to block - on modern OEM builds (e.g. Samsung
            // One UI) it is silently dropped, ShellConsentActivity never appears, and
            // ShizukuShellLoader's 15s timeout fires with a misleading "may be blocked by your
            // system / disable battery optimization" message (#377). Route through a
            // notification instead: tapping it is a user-initiated foreground action and is
            // exempt from BAL, so the consent dialog reliably shows up.
            postConsentNotification(context, intent)
        }
    }

    private fun postConsentNotification(context: Context, intent: Intent) {
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

        val consentIntent = Intent(context, ShellConsentActivity::class.java).apply {
            // putExtras() only copies the extras Bundle, not the action string -
            // ShellBinderRequestHandler.handleRequest gates on intent.action matching
            // REQUEST_BINDER, so it must be carried over explicitly or the forwarded
            // request silently fails that check.
            action = intent.action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtras(intent)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            consentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_system_icon)
            .setContentTitle(context.getString(R.string.notification_shell_consent_title))
            .setContentText(context.getString(R.string.notification_shell_consent_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }
}
