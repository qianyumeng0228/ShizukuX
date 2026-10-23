package af.shizuku.manager.receiver

import android.content.Context
import android.content.Intent
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.service.LockedBootStartService

class ManualStopReceiver : AuthenticatedReceiver() {
    override fun onAuthenticated(context: Context, intent: Intent) {
        val applicationId = BuildConfig.APPLICATION_ID
        if (intent.action != "${applicationId}.STOP") return

        // Also cancel a start the direct-boot service may still be running (user stopped
        // before it finished); ported from Shizuku-Next c5eeafa.
        LockedBootStartService.cancelRun("stopped by the user")
        ShizukuReceiverStarter.stop()
    }
}
