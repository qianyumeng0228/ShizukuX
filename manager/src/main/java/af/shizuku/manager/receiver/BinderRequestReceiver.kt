package af.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import af.shizuku.manager.shell.ShellBinderRequestHandler

class BinderRequestReceiver : AuthenticatedReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER" &&
            intent.action != "${context.packageName}.intent.action.REQUEST_BINDER") {
            return
        }

        // Legacy clients (pre-v11) broadcast REQUEST_BINDER without an auth token.
        // The modern BinderSender mechanism handles binder delivery automatically via ContentProvider,
        // so just silently skip unauthenticated broadcasts instead of showing a confusing notification.
        //
        // NOTE: this also silently drops every rish/shell client (see ShizukuShellLoader), which has
        // no ContentProvider for BinderSender to push into and no supported way to obtain a token that
        // would pass AuthenticatedReceiver's IntentCrypto-encrypted comparison - tracked as a real gap,
        // not treated as fixed by this log line. See GH #368/#372/#374.
        if (intent.getStringExtra("auth") == null) {
            timber.log.Timber.tag("BinderRequestReceiver").d(
                "Dropped REQUEST_BINDER broadcast: no auth extra present (sender identity is not " +
                    "available on this raw broadcastIntent() path, see comment above)"
            )
            return
        }

        super.onReceive(context, intent)
    }

    override fun onAuthenticated(context: Context, intent: Intent) {
        ShellBinderRequestHandler.handleRequest(context, intent, true)
    }
}
