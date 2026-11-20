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
    // Gitee API v5 — second-choice version probe (anonymous works on public repos).
    // /releases/latest returns the newest non-prerelease, /releases lists all releases
    // ordered by id (NOT by created_at), so callers must sort themselves.
    const val API_RELEASES_GITEE = "https://gitee.com/api/v5/repos/qianyumeng0228/ShizukuX/releases"

    /**
     * Multi-source download mirrors for APK updates. Templates differ per source:
     *
     *   COS   — flat file name:            $MIRROR_COS/<assetName>
     *   CF    — GitHub reverse proxy, tag required:
     *           $MIRROR_CF/<tagName>/<assetName>
     *           Dropping the tag makes GitHub answer 404 (with GitHub's own HTML body),
     *           which is what made the mirror look broken. With the tag it returns the APK,
     *           ~2 s from a warm edge cache; cold cache is ~27 s.
     *
     * UpdateChecker builds the download list in this fixed order, and UpdateManager walks it
     * one source at a time: Gitee → CNB → JihuLab → GitHub → CF → COS. The next entry
     * is tried only after the previous one actually failed (enqueue rejection, HTTP error,
     * truncated body, or a same-named file whose size differs from the GitHub asset by more
     * than 2%).
     */
    const val MIRROR_CF = "https://fd.shizukux.xyz/qianyumeng0228/ShizukuX/releases/download"
    // Tencent COS (Guangzhou) — domestic China direct connect. Must go through the bound
    // custom origin domain: COS rejects APK/IPA downloads on its default
    // *.cos.<region>.myqcloud.com host (403 DownloadForbidden), so cos.shizukux.xyz
    // (CNAME → bucket domain, TLS via the cert bound in the COS console) is the only
    // usable entry point.
    const val MIRROR_COS = "https://cos.shizukux.xyz"
    /**
     * Gitee mirror — same asset layout as GitHub releases, no auth for public repos.
     * The redirect chain is gitee.com/releases/download/<tag>/<asset> → attach_files/...
     * → foruda.gitee.com (signed, short-lived). DownloadManager follows both hops.
     * Assets that Gitee's scanner rejected upstream are stored there as <name>.apk.zip, so
     * a lookup by the GitHub asset name returns 404 for those tags and the relay skips it.
     */
    const val MIRROR_GITEE = "https://gitee.com/qianyumeng0228/ShizukuX/releases/download"

    /**
     * 国内镜像源，均为 GitHub release 资产的同步副本（assets 由备份库原样上传，匿名可下载）：
     *
     *   CNB (cnb.cool) — 路径含 `/-/`：/$tag/$assetName（必须用 cnb.cool 主域，
     *                    api.cnb.cool 对匿名下载返回 401）
     *   JihuLab      — 极狐 GitLab 没有 release 二进制托管，APK 存在 Generic Package Registry，
     *                  下载 URL 形如 /projects/<用 %2F 编码的路径>/packages/generic/<pkg>/<tag>/<asset>
     *                  实测公库匿名 200，无需令牌。
     */
    const val MIRROR_CNB = "https://cnb.cool/ShizukuX/ShizukuX/-/releases/download"
    const val MIRROR_JIHULAB = "https://jihulab.com/api/v4/projects/qianyumeng0228%2FShizukuX/packages/generic/ShizukuX"

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
