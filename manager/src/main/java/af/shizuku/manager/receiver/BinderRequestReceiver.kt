package af.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import af.shizuku.manager.shell.ShellBinderRequestHandler

class BinderRequestReceiver : AuthenticatedReceiver() {

    override fun onAuthenticated(context: Context, intent: Intent) {
        if (intent.action == "rikka.shizuku.intent.action.REQUEST_BINDER") {
            ShellBinderRequestHandler.handleRequest(context, intent, true)
        }
    }
}
