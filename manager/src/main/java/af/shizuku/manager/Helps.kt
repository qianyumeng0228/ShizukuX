package af.shizuku.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import af.shizuku.manager.utils.MultiLocaleEntity

object Helps {
    val ADB = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuX/wiki/Setup")
        put("zh-TW", "https://github.com/thejaustin/ShizukuX/wiki/Setup")
        put("en", "https://github.com/thejaustin/ShizukuX/wiki/Setup")
    }

    val ADB_ANDROID11 = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuX/wiki/Setup")
        put("zh-TW", "https://github.com/thejaustin/ShizukuX/wiki/Setup")
        put("en", "https://github.com/thejaustin/ShizukuX/wiki/Setup")
    }

    val APPS = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuX/wiki/Supported-apps")
        put("zh-TW", "https://github.com/thejaustin/ShizukuX/wiki/Supported-apps")
        put("en", "https://github.com/thejaustin/ShizukuX/wiki/Supported-apps")
    }

    val HOME = MultiLocaleEntity().apply {
        put("en", "https://github.com/thejaustin/ShizukuX/tree/master/README.md#developer-guide")
    }

    val DOWNLOAD = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuX/releases")
        put("zh-TW", "https://github.com/thejaustin/ShizukuX/releases")
        put("en", "https://github.com/thejaustin/ShizukuX/releases")
    }

    val ADB_PERMISSION = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuX/wiki/Setup#troubleshooting")
        put("zh-TW", "https://github.com/thejaustin/ShizukuX/wiki/Setup#troubleshooting")
        put("en", "https://github.com/thejaustin/ShizukuX/wiki/Setup#troubleshooting")
    }

    val SUI = MultiLocaleEntity().apply {
        put("en", "https://github.com/RikkaApps/Sui")
    }

    val RISH = MultiLocaleEntity().apply {
        put("en", "https://github.com/thejaustin/ShizukuX-API/tree/master/rish")
    }

    /**
     * Get help URL for the given locale
     */
    fun getHelpUrl(locale: String?): String {
        return HOME.get(locale) ?: HOME.get("en") ?: "https://github.com/thejaustin/ShizukuX/wiki"
    }

    /**
     * Open URL in browser
     */
    fun openUrl(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
