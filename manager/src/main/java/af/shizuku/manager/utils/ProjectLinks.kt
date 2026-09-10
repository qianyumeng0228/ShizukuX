package af.shizuku.manager.utils

object ProjectLinks {
    const val REPOSITORY = "https://github.com/qianyumeng0228/ShizukuX"
    const val WEBSITE = "https://shizukux.xyz"
    const val QQ_GROUP_NUMBER = "712125973"
    const val README_DEVELOPER_GUIDE = "https://github.com/qianyumeng0228/ShizukuX/tree/master/README.md#developer-guide"
    const val OPEN_SOURCE_LICENSES = "https://github.com/qianyumeng0228/ShizukuX/blob/master/OPEN_SOURCE_LICENSES.md"

    const val RELEASES = "https://github.com/qianyumeng0228/ShizukuX/releases"
    const val LATEST_RELEASE = "https://github.com/qianyumeng0228/ShizukuX/releases/latest"
    const val RELEASES_ATOM = "https://github.com/qianyumeng0228/ShizukuX/releases.atom"

    const val ISSUES = "https://github.com/qianyumeng0228/ShizukuX/issues"
    const val NEW_ISSUE = "https://github.com/qianyumeng0228/ShizukuX/issues/new/choose"
    const val NEW_PREFILLED_ISSUE = "https://github.com/qianyumeng0228/ShizukuX/issues/new"

    const val API_RELEASES = "https://api.github.com/repos/qianyumeng0228/ShizukuX/releases"
    const val API_LATEST_RELEASE = "https://api.github.com/repos/qianyumeng0228/ShizukuX/releases/latest"

    /**
     * Multi-source download mirrors for APK updates.
     * Ordered by priority — first source that responds wins.
     * Each entry is a URL template: $MIRROR/<assetFileName>
     */
    // 1. Cloudflare CDN (fd.shizukux.xyz) — fast global, cached from GitHub releases
    const val MIRROR_CF = "https://fd.shizukux.xyz/qianyumeng0228/ShizukuX/releases/download"
    // 2. Tencent COS (Guangzhou) — domestic China direct connect, last resort
    const val MIRROR_COS = "https://shizukux-updates-1442128143.cos.ap-guangzhou.myqcloud.com"

    const val APP_CONTEXT_DB = "https://raw.githubusercontent.com/qianyumeng0228/ShizukuX/master/database/apps.json"
    const val APPS_DB = "https://raw.githubusercontent.com/qianyumeng0228/ShizukuX/master/database/apps.json"

    const val HELP_HOME = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/index.md"
    const val SERVICE_CONNECTION = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/service-connection.md"
    const val KNOWLEDGEBASE = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/knowledgebase.md"
    const val AUTOMATION_APPS = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/automation-apps.md"
    const val HELP_PC_ADB = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/service-connection.md#通过电脑-adb-启动"
    const val HELP_WIRELESS_ADB = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/service-connection.md#通过无线-adb-启动"
    const val HELP_ERROR_REFERENCE = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/service-connection.md#错误参考"
    const val HELP_WATCHDOG = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/knowledgebase.md#shizuku-一直随机停止"
    const val HELP_BOOT = "https://github.com/qianyumeng0228/ShizukuX/blob/master/docs/zh-CN/knowledgebase.md#shizuku-无法开机自启动"
    const val RISH = "https://github.com/qianyumeng0228/ShizukuX-API/tree/master/rish"

    fun releaseTag(tagName: String): String = "$RELEASES/tag/$tagName"
}
