package af.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import af.shizuku.manager.BuildConfig

class ManualStartReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.START") return

        // A caller firing START is a user-visible action (Tasker/automation), i.e. an explicit
        // start: it must clear the "the user stopped it" tombstone, otherwise every automation-
        // driven start would be skipped after a manual stop.
        ShizukuReceiverStarter.start(context, true)
    }
}
