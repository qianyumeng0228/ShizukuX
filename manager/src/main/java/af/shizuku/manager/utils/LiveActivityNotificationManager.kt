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
        
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.NotificationCompat.OngoingActivity
import androidx.core.app.NotificationCompat.StatusLine
import af.shizuku.manager.R
import af.shizuku.manager.MainActivity

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
        
        val pendingIntent = PendingIntentCompat.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            0, false
        )

        val ongoingActivity = OngoingActivity.Builder(
            context, CHANNEL_ID, 
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_server_ok_24)
        )
        .setAnimatedIcon(R.drawable.ic_server_ok_24)
        .setStaticIcon(R.drawable.ic_server_ok_24)
        .setTouchIntent(pendingIntent)
        .build()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_ok_24)
            .setContentTitle("ShizukuX Active")
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setChronometerCountDown(false)
            .setWhen(System.currentTimeMillis())
            .setOngoingActivity(ongoingActivity) // Attach the 'Now Bar' metadata
        
        manager.notify(NOTIFICATION_ID, builder.build())
    }
    
    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
