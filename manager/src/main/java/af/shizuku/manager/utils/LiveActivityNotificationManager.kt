package af.shizuku.manager.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import af.shizuku.manager.R

object LiveActivityNotificationManager {
    private const val CHANNEL_ID = "live_activity_channel"
    private const val NOTIFICATION_ID = 9999
    
    fun show(context: Context, status: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ShizukuX Live Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live Shizuku activity"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_ok_24)
            .setContentTitle("ShizukuX Live")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        
        manager.notify(NOTIFICATION_ID, builder.build())
    }
    
    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
