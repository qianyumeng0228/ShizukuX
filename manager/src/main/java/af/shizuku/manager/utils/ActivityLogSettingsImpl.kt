package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.ActivityLogSettings

class ActivityLogSettingsImpl : ActivityLogSettings {
    override fun isActivityLogEnabled(): Boolean = ShizukuSettings.isActivityLogEnabled()

    override fun getWatchdog(): Boolean = ShizukuSettings.getWatchdog()

    override fun getActivityLogRetention(): Int = ShizukuSettings.getActivityLogRetention()

    override fun setActivityLogRetention(count: Int) {
        ShizukuSettings.setActivityLogRetention(count)
    }

    override fun showNotification(appName: String, action: String) {
        if (!ShizukuSettings.isActivityLogEnabled()) return
        LiveActivityNotificationManager.show(af.shizuku.manager.ShizukuApplication.appContext, "$appName: $action")
    }
}
