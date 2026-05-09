package af.shizuku.manager.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import af.shizuku.manager.utils.LiveActivityNotificationManager
import af.shizuku.manager.utils.ShizukuStateMachine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class ShizukuLiveService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        
        serviceScope.launch {
            ShizukuStateMachine.asFlow().collect { state ->
                val isRunning = state == ShizukuStateMachine.State.RUNNING
                if (isRunning) {
                    LiveActivityNotificationManager.show(this@ShizukuLiveService, "System Bridge Active")
                } else {
                    LiveActivityNotificationManager.dismiss(this@ShizukuLiveService)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Run as foreground service to ensure system persistence
        val state = ShizukuStateMachine.get()
        if (state == ShizukuStateMachine.State.RUNNING) {
            LiveActivityNotificationManager.show(this, "System Bridge Active")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        LiveActivityNotificationManager.dismiss(this)
        super.onDestroy()
    }
}
