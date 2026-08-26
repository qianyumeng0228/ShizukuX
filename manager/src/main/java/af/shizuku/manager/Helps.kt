package af.shizuku.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import af.shizuku.manager.utils.MultiLocaleEntity

object Helps {
    // Points at Service-Connection (the ADB/root start-flow page) rather than a "Setup" page,
    // which doesn't exist in the wiki — these used to be dead 404 links.
    val ADB = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection")
        put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection")
    }

    val ADB_ANDROID11 = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-wireless-adb")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-wireless-adb")
        put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#starting-via-wireless-adb")
    }

    // "Supported-apps" doesn't exist as its own page either — Knowledgebase is the closest
    // real landing page until a dedicated compatibility list is written.
    val APPS = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/ShizukuPlus-Knowledgebase")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/ShizukuPlus-Knowledgebase")
        put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/ShizukuPlus-Knowledgebase")
    }

    val HOME = MultiLocaleEntity().apply {
        put("en", "https://github.com/thejaustin/ShizukuPlus/tree/master/README.md#developer-guide")
    }

    val DOWNLOAD = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/releases")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/releases")
        put("en", "https://github.com/thejaustin/ShizukuPlus/releases")
    }

    val ADB_PERMISSION = MultiLocaleEntity().apply {
        put("zh-CN", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#error-reference")
        put("zh-TW", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#error-reference")
        put("en", "https://github.com/thejaustin/ShizukuPlus/wiki/Service-Connection#error-reference")
    }

    val SUI = MultiLocaleEntity().apply {
        put("en", "https://github.com/RikkaApps/Sui")
    }

    val RISH = MultiLocaleEntity().apply {
        put("en", "https://github.com/thejaustin/ShizukuPlus-API/tree/master/rish")
    }

    /**
     * Get help URL for the given locale
     */
    fun getHelpUrl(locale: String?): String {
        return HOME.get(locale) ?: HOME.get("en") ?: "https://github.com/thejaustin/ShizukuPlus/wiki"
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
