package af.shizuku.manager.adb

import android.app.Service
import android.content.Intent
import android.os.IBinder
import af.shizuku.manager.settings.SceneRelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Explicit entry point for apps that want ShizukuX to activate their ADB mode.
 *
 * Scene (com.omarea.vtools) is the first client: instead of waiting for its ADB-mode dialog to
 * appear (the accessibility relay path), the patched Scene sends
 * [ACTION_ACTIVATE_SCENE_ADB] here the moment the user picks ADB mode. startService wakes this
 * process deterministically, so HyperOS' Greezer broadcast interception (which kills the
 * rish REQUEST_BINDER path) is bypassed entirely.
 */
class SceneRelayService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val expected = packageName + ACTION_SUFFIX
        if (action != expected) {
            android.util.Log.w(TAG, "ignored action=$action expected=$expected")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        android.util.Log.w(TAG, "activation requested by uid=${intent.getIntExtra(EXTRA_CALLER_UID, -1)}")

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            SceneRelayManager.startSceneAdbActivation(
                applicationContext,
                scope,
                silent = intent.getBooleanExtra(EXTRA_SILENT, true)
            )
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "activation failed: $t")
        }

        stopSelf(startId)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "SceneRelayService"

        /** Action is derived from the flavor's applicationId so that both the dropin build
         *  (moe.shizuku.privileged.api) and the portable build (xyz.shizuku.extra.api) work.
         *  The receiver compares against `packageName + ACTION_SUFFIX` at runtime. */
        const val ACTION_SUFFIX = ".action.ACTIVATE_SCENE_ADB"
        const val EXTRA_SILENT = "silent"
        const val EXTRA_CALLER_UID = "caller_uid"
    }
}
