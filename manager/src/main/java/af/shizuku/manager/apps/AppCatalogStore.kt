package af.shizuku.manager.apps

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import timber.log.Timber

/**
 * One entry of the "应用" tab: what the user added to this page, rendered from the live package
 * table so an uninstalled or updated app is reflected without touching the saved list.
 */
data class CatalogApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val installed: Boolean,
    val info: ApplicationInfo?
)

/**
 * The curated package list behind the Apps tab.
 *
 * ShizukuX 的「应用」页只列用户自己加进来的软件，不再是内置工具目录：内置工具都能从首页卡片直达，
 * 在这里再放一份只会让"应用"这个词指代两件事。列表本身很小，用 SharedPreferences 存包名即可，
 * 标签和版本每次从 PackageManager 现读，避免缓存过期的名字。
 *
 * `com.omarea.vtools`（星野scene）是常驻第一条：它是这个分支的配套包，删掉列表项也删不掉它，
 * 只能取消"显示在首位"以外的顺序——见 [orderedPackages]。
 */
object AppCatalogStore {

    private const val PREFS = "shizukux_apps_catalog"
    private const val KEY_PACKAGES = "packages"

    const val SCENE_PACKAGE = "com.omarea.vtools"
    const val SCENE_LABEL = "星野scene"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Saved packages, Scene always first. */
    fun orderedPackages(context: Context): List<String> {
        val saved = prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
            .filterNot { it == SCENE_PACKAGE }
            .sorted()
        return listOf(SCENE_PACKAGE) + saved
    }

    fun isSaved(context: Context, packageName: String): Boolean =
        packageName == SCENE_PACKAGE ||
            prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty().contains(packageName)

    fun add(context: Context, packages: Collection<String>) {
        val current = prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        current.addAll(packages.filter { it != SCENE_PACKAGE })
        prefs(context).edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    /** Scene is pinned, so removing it from this list is a no-op rather than a hidden deletion. */
    fun remove(context: Context, packageName: String) {
        if (packageName == SCENE_PACKAGE) return
        val current = prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty().toMutableSet()
        current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_PACKAGES, current).apply()
    }

    fun displayLabel(context: Context, packageName: String, fallback: String): String =
        if (packageName == SCENE_PACKAGE) SCENE_LABEL else fallback

    /** Resolves one package to a row; returns null when the package is gone and never installed. */
    fun resolve(context: Context, packageName: String): CatalogApp? {
        val pm = context.packageManager
        val info = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (info == null) {
            return CatalogApp(
                packageName = packageName,
                label = displayLabel(context, packageName, packageName),
                versionName = null,
                versionCode = 0,
                isSystem = false,
                installed = false,
                info = null
            )
        }
        val pkgInfo = try {
            pm.getPackageInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val versionCode = when {
            pkgInfo == null -> 0L
            Build.VERSION.SDK_INT >= 28 -> pkgInfo.longVersionCode
            else -> @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
        }
        val rawLabel = info.loadLabel(pm).toString().ifBlank { packageName }
        return CatalogApp(
            packageName = packageName,
            label = displayLabel(context, packageName, rawLabel),
            versionName = pkgInfo?.versionName,
            versionCode = versionCode,
            isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            installed = true,
            info = info
        )
    }

    /**
     * Every launchable app on the device, for the "添加到应用页" picker.
     * System apps are included: the user may legitimately want Settings or the camera on this page.
     */
    fun installedApps(context: Context): List<CatalogApp> {
        val pm = context.packageManager
        val apps = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (e: Exception) {
            Timber.tag("AppCatalogStore").w(e, "getInstalledApplications failed")
            emptyList()
        }
        return apps.mapNotNull { info ->
            val pkg = info.packageName
            val pkgInfo = try {
                pm.getPackageInfo(pkg, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
            val versionCode = when {
                pkgInfo == null -> 0L
                Build.VERSION.SDK_INT >= 28 -> pkgInfo.longVersionCode
                else -> @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
            }
            val label = info.loadLabel(pm).toString().ifBlank { pkg }
            CatalogApp(
                packageName = pkg,
                label = displayLabel(context, pkg, label),
                versionName = pkgInfo?.versionName,
                versionCode = versionCode,
                isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                installed = true,
                info = info
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Timber.tag("AppCatalogStore").w(e, "launch $packageName failed")
            false
        }
    }

    fun openAppInfo(context: Context, packageName: String) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /** Which component the launcher would open — used to ask "最近使用" about a concrete app. */
    fun launchComponent(context: Context, packageName: String): ComponentName? =
        context.packageManager.getLaunchIntentForPackage(packageName)?.component
}

/**
 * "常用" ranking.
 *
 * 频次来自系统的 UsageStats（前台累计时长），不是从本页跳转次数——本页只统计"从这里点开"的次数，
 * 用户从桌面启动的软件会被完全漏掉，排行就失真了。
 *
 * 需要「使用情况访问」特殊权限；未授权时 [hasAccess] 返回 false，页面退化为"按加入顺序显示"并给出入口，
 * 而不是假装排行有效。
 */
object AppUsageRanker {

    private const val LOOKBACK_DAYS = 7L

    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    /** Top-N packages by foreground time in the last [LOOKBACK_DAYS] days. */
    fun topPackages(context: Context, limit: Int): List<String> {
        if (!hasAccess(context)) return emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val start = now - LOOKBACK_DAYS * 24 * 60 * 60 * 1000
        val stats = try {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        } catch (e: Exception) {
            Timber.tag("AppUsageRanker").w(e, "queryUsageStats failed")
            return emptyList()
        }.orEmpty()

        val foreground = HashMap<String, Long>()
        stats.forEach { stat ->
            if (stat.totalTimeInForeground > 0) {
                foreground[stat.packageName] =
                    (foreground[stat.packageName] ?: 0L) + stat.totalTimeInForeground
            }
        }
        return foreground.entries
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .map { it.key }
            .filterNot { it == context.packageName }
            .take(limit)
    }

    fun openAccessSettings(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
    }
}
