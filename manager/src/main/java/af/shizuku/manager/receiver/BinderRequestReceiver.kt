package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.legacy.ShellConsentActivity
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.utils.IntentCrypto
import java.security.MessageDigest

class BinderRequestReceiver : BroadcastReceiver() {

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
            context.startActivity(
                Intent(context, ShellConsentActivity::class.java).apply {
                    // putExtras() only copies the extras Bundle, not the action string -
                    // ShellBinderRequestHandler.handleRequest gates on intent.action matching
                    // REQUEST_BINDER, so it must be carried over explicitly or the forwarded
                    // request silently fails that check.
                    action = intent.action
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtras(intent)
                }
            )
        }
    }
}
