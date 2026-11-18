package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import af.shizuku.manager.worker.AdbStartWorker

class NotifAttemptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // The notification's retry button: the user is watching this attempt.
        AdbStartWorker.enqueue(context, immediate = true)
    }
}
