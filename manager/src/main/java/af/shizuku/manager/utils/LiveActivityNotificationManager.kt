package af.shizuku.manager.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
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
            context, 0, Intent(context, MainActivity::class.java), 0, false
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_server_ok_24)
            .setContentTitle("ShizukuX Active")
            .setContentText(status)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
