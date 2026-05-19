package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.AppContextSettings

class AppContextSettingsImpl : AppContextSettings {
    override fun getRemoteDbJson(): String? = ShizukuSettings.getRemoteDbJson()

    override fun setRemoteDbJson(json: String) {
        ShizukuSettings.setRemoteDbJson(json)
    }

    override fun setLastDbUpdate(time: Long) {
        ShizukuSettings.setLastDbUpdate(time)
    }
}
