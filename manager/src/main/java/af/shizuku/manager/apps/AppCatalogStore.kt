package af.shizuku.manager.apps

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import af.shizuku.manager.settings.DeviceOwnerHelper
import timber.log.Timber

/**
 * One entry of the "应用" tab. Always resolved from the live package table, so a package that
 * disappeared shows up as 未安装 instead of stale data.
 */
data class CatalogApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val isSystem: Boolean,
    val installed: Boolean,
    val info: ApplicationInfo?,
    /** 图标已被「隐藏桌面图标」摘掉：包不在当前用户下，但数据保留，本页仍算已装。 */
    val iconHidden: Boolean = false
)

/**
 * 应用页的三份状态：
 *
 * - **常用**：用户自己钉住的 4 个位置，顺序即显示顺序，和安装状态解耦（应用卸了仍占位，显示未安装）。
 * - **全部应用**：星野scene 常驻 + 只收「从商店装的」和「用内置安装器装的」包，不再收手工挑的列表。
 * - **隐藏**：每个包一个布尔值，配合被禁用的 launcher 组件，做到桌面不显示图标但仍能从本页启动。
 */
object AppCatalogStore {

    private const val PREFS = "shizukux_apps_catalog"
    private const val KEY_MANAGED = "managed_packages"
    private const val KEY_FAVORITES = "favorite_packages"
    private const val KEY_HIDDEN_FLAG = "hidden_"
    private const val KEY_HIDDEN_MODE = "hidden_mode_"
    private const val KEY_HIDDEN_COMPONENTS = "hidden_components_"
    private const val KEY_HIDDEN_LABEL = "hidden_label_"
    private const val KEY_HIDDEN_VERSION = "hidden_version_"
    private const val MODE_COMPONENT = "component"
    private const val MODE_DEVICE_POLICY = "do"
    private const val MODE_PM = "pm"
    private const val PERM_CHANGE_COMPONENT = "android.permission.CHANGE_COMPONENT_ENABLED_STATE"
    private const val ROLE_DEVICE_POLICY_MANAGEMENT = "android.app.role.DEVICE_POLICY_MANAGEMENT"

    const val SCENE_PACKAGE = "com.omarea.vtools"
    const val SCENE_LABEL = "星野scene"
    const val MAX_FAVORITES = 4

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ------------------------------------------------------------------ 全部应用

    /** 全部应用 = 星野scene + 通过商店/内置安装器装过的包。 */
    fun managedPackages(context: Context): List<String> {
        val saved = prefs(context).getStringSet(KEY_MANAGED, emptySet()).orEmpty()
            .filterNot { it == SCENE_PACKAGE }
            .sorted()
        return listOf(SCENE_PACKAGE) + saved
    }

    fun isManaged(context: Context, packageName: String): Boolean =
        packageName == SCENE_PACKAGE ||
            prefs(context).getStringSet(KEY_MANAGED, emptySet()).orEmpty().contains(packageName)

    /** Called after a successful install through the store or the built-in installer. */
    fun addManaged(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName == SCENE_PACKAGE) return
        val current = prefs(context).getStringSet(KEY_MANAGED, emptySet()).orEmpty().toMutableSet()
        if (current.add(packageName)) {
            prefs(context).edit().putStringSet(KEY_MANAGED, current).apply()
        }
    }

    /** 卸载（连列表一起删）。星野scene 常驻，退化为只保留一行未安装。 */
    fun removeManaged(context: Context, packageName: String) {
        if (packageName == SCENE_PACKAGE) return
        val current = prefs(context).getStringSet(KEY_MANAGED, emptySet()).orEmpty().toMutableSet()
        current.remove(packageName)
        prefs(context).edit().putStringSet(KEY_MANAGED, current).apply()
    }

    // ------------------------------------------------------------------ 常用

    /** 常用位，顺序固定；卸载后的包仍然保留，渲染成未安装。 */
    fun favorites(context: Context): List<String> =
        prefs(context).getString(KEY_FAVORITES, "").orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .take(MAX_FAVORITES)

    private fun saveFavorites(context: Context, list: List<String>) {
        prefs(context).edit().putString(KEY_FAVORITES, list.take(MAX_FAVORITES).joinToString("\n")).apply()
    }

    fun addFavorite(context: Context, packageName: String): Boolean {
        val current = favorites(context)
        if (packageName.isBlank() || current.contains(packageName) || current.size >= MAX_FAVORITES) {
            return false
        }
        saveFavorites(context, current + packageName)
        return true
    }

    fun removeFavorite(context: Context, packageName: String) {
        saveFavorites(context, favorites(context).filterNot { it == packageName })
    }

    fun isFavorite(context: Context, packageName: String): Boolean =
        favorites(context).contains(packageName)

    /** 交换/移动常用位置，长按拖动落点用这个。 */
    fun moveFavorite(context: Context, from: Int, to: Int) {
        val current = favorites(context).toMutableList()
        if (from !in current.indices || to !in 0 until MAX_FAVORITES) return
        if (from == to) return
        if (to >= current.size) {
            // 拖到空格：视为挪到队尾
            val item = current.removeAt(from)
            current.add(item)
        } else {
            val item = current.removeAt(from)
            current.add(to, item)
        }
        saveFavorites(context, current)
    }

    // ------------------------------------------------------------------ 解析 / 启动

    fun displayLabel(context: Context, packageName: String, fallback: String): String =
        if (packageName == SCENE_PACKAGE) SCENE_LABEL else fallback

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
            if (isIconHidden(context, packageName)) {
                return CatalogApp(
                    packageName = packageName,
                    label = displayLabel(
                        context,
                        packageName,
                        prefs(context).getString(KEY_HIDDEN_LABEL + packageName, null)
                            ?: packageName
                    ),
                    versionName = prefs(context).getString(KEY_HIDDEN_VERSION + packageName, null),
                    versionCode = 0,
                    isSystem = false,
                    installed = true,
                    info = null,
                    iconHidden = true
                )
            }
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
            info = info,
            // 组件模式隐藏时包仍然安装，只能靠本地记录判断；卸载模式则由上面的 null 分支走到。
            iconHidden = isIconHidden(context, packageName)
        )
    }

    /** Every launchable-ish app on the device, for the 常用 picker. */
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

    /** Any launcher entry still enabled for the package? 图标是否还会出现。 */
    private fun hasEnabledLauncher(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val resolved: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(intent, 0)
            }
        } catch (e: Exception) {
            emptyList()
        }
        return resolved.any { it.activityInfo?.enabled == true }
    }

    /**
     * 组件启停权限是否到手。`CHANGE_COMPONENT_ENABLED_STATE` 是 `signature|privileged|role`，
     * 只有持有 `DEVICE_POLICY_MANAGEMENT` role 才会被下发；拿不到时用 Shizuku 的 shell 把自己加进
     * role 持有者（`cmd role add-role-holder`），系统随即按 manifest 声明补发权限。
     */
    fun ensureComponentPermission(context: Context): Boolean {
        fun granted() = context.checkSelfPermission(PERM_CHANGE_COMPONENT) == PackageManager.PERMISSION_GRANTED
        if (granted()) return true
        if (!PrivilegedShell.available()) return false
        PrivilegedShell.run(
            "cmd role add-role-holder --user 0 $ROLE_DEVICE_POLICY_MANAGEMENT '${context.packageName}'"
        )
        return granted()
    }

    private fun setLauncherComponentsEnabled(context: Context, components: List<String>, enabled: Boolean) {
        val pm = context.packageManager
        components.forEach { flat ->
            val cn = ComponentName.unflattenFromString(flat) ?: return@forEach
            runCatching {
                pm.setComponentEnabledSetting(
                    cn,
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    // DONT_KILL_APP：只摘掉桌面入口，进程与后台服务照常运行
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }

    /**
     * Launch an app. 图标隐藏期间包不在当前用户下，启动前先把它恢复回来（数据不动），
     * 恢复后开关会同步关闭，避免「看起来很隐藏、其实已经装回来了」的状态漂移。
     */
    fun launch(context: Context, packageName: String): Boolean {
        if (isIconHidden(context, packageName)) {
            val restored = setIconHidden(context, packageName, false)
            if (restored.isFailure) return false
        }
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

    // ------------------------------------------------------------------ 隐藏桌面图标

    fun isIconHidden(context: Context, packageName: String): Boolean =
        prefs(context).getBoolean(KEY_HIDDEN_FLAG + packageName, false)

    /** 上次隐藏用的机制：component / do / pm，未隐藏时为 null。UI 文案据此区分。 */
    fun hiddenMode(context: Context, packageName: String): String? =
        if (!isIconHidden(context, packageName)) null
        else prefs(context).getString(KEY_HIDDEN_MODE + packageName, null)

    fun isInstalledForUser0(packageName: String): Boolean =
        PrivilegedShell.run("pm list packages --user 0 '$packageName'")
            ?.contains("package:$packageName") == true

    /** Launcher components of one package, including ones already disabled. */
    fun launcherComponents(context: Context, packageName: String): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
        val resolved: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            }
        } catch (e: Exception) {
            emptyList()
        }
        val list = resolved.mapNotNull { info ->
            info.activityInfo?.let { "${it.packageName}/${it.name}" }
        }.distinct()
        if (list.isEmpty()) {
            pm.getLaunchIntentForPackage(packageName)?.component?.let {
                return listOf("${it.packageName}/${it.className}")
            }
        }
        return list
    }

    /** Package visible to the calling app for its user (hidden / uninstalled-for-user both return false). */
    private fun isVisible(context: Context, packageName: String): Boolean =
        try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }

    /**
     * 隐藏 / 恢复桌面图标。
     *
     * 实现优先级：
     * 1. 拿到 `CHANGE_COMPONENT_ENABLED_STATE`（signature|privileged|role，靠 `DEVICE_POLICY_MANAGEMENT`
     *    role 下发）→ 只禁用目标应用的 launcher 组件（DONT_KILL_APP）。图标消失、包仍安装、
     *    进程与后台服务继续运行；这是唯一满足「藏图标但照常跑」的路径。
     * 2. 是 Device Owner → `DevicePolicyManager.setApplicationHidden`。图标消失、数据不动，但系统把包
     *    视为该用户未安装，进程会停，隐藏期间不可启动。
     * 3. 都没有 → 「从当前用户卸载、`-k` 保留数据」：关闭时 `install-existing`，其次用留存 APK 重装。
     *
     * 机制记录在本地，恢复时按上次用的那条还原，避免拿错方法去还原。
     */
    fun setIconHidden(context: Context, packageName: String, hidden: Boolean): Result<Unit> {
        val deviceOwner = DeviceOwnerHelper.isDeviceOwner(context)
        val mode = prefs(context).getString(KEY_HIDDEN_MODE + packageName, null)

        if (!hidden) {
            // 1) 上次是组件禁用 → 原地启用回来（应用一直没停过）
            if (mode == MODE_COMPONENT) {
                val stored = prefs(context).getString(KEY_HIDDEN_COMPONENTS + packageName, null)
                    ?.split("\n")?.filter { it.isNotBlank() }.orEmpty()
                val targets = stored.ifEmpty { launcherComponents(context, packageName) }
                setLauncherComponentsEnabled(context, targets, true)
                if (hasEnabledLauncher(context, packageName) || isVisible(context, packageName)) {
                    markHidden(context, packageName, false)
                    return Result.success(Unit)
                }
            }
            // 2) 上次是 DO 隐藏（或不知道），先让 DO 解除隐藏
            if (deviceOwner && mode != MODE_PM && mode != MODE_COMPONENT) {
                DeviceOwnerHelper.setApplicationHidden(context, packageName, false)
                if (isVisible(context, packageName)) {
                    markHidden(context, packageName, false)
                    return Result.success(Unit)
                }
            }
            // 3) 已可见则无需再装
            if (isVisible(context, packageName)) {
                markHidden(context, packageName, false)
                return Result.success(Unit)
            }
            // 3) 走 shell 恢复（`-k` 卸载场景）
            if (!PrivilegedShell.available()) {
                return Result.failure(IllegalStateException("Shizuku 服务未运行，也没有 root"))
            }
            val useRoot = PrivilegedShell.hasRoot()
            fun sh(command: String): String? =
                PrivilegedShell.run(if (useRoot) "su -c \"$command\"" else command)

            var output = sh("cmd package install-existing --user 0 '$packageName'")
            if (!isVisible(context, packageName)) {
                val apk = ApkInstaller.storedApk(context, packageName)
                    ?: return Result.failure(
                        IllegalStateException(
                            "没有留存的安装包，无法恢复显示；请用右上角 + 重新安装，或到应用商店装回来。"
                        )
                    )
                val remote = "/data/local/tmp/sx_restore_${System.currentTimeMillis()}.apk"
                if (!PrivilegedShell.push(apk, remote) { _ -> }) {
                    return Result.failure(IllegalStateException("无法把留存的安装包交给 shell"))
                }
                output = sh("pm install -r -d '$remote'; rm -f '$remote'")
            }
            if (!isVisible(context, packageName)) {
                return Result.failure(
                    IllegalStateException("恢复失败：${output?.trim()?.takeLast(160)}")
                )
            }
            markHidden(context, packageName, false)
            return Result.success(Unit)
        }

        val resolved = resolve(context, packageName)

        // 1) 只禁用 launcher 组件：应用继续运行（首选）
        if (ensureComponentPermission(context)) {
            val components = launcherComponents(context, packageName)
            if (components.isNotEmpty()) {
                setLauncherComponentsEnabled(context, components, false)
                if (!hasEnabledLauncher(context, packageName)) {
                    prefs(context).edit()
                        .putBoolean(KEY_HIDDEN_FLAG + packageName, true)
                        .putString(KEY_HIDDEN_MODE + packageName, MODE_COMPONENT)
                        .putString(KEY_HIDDEN_COMPONENTS + packageName, components.joinToString("\n"))
                        .putString(KEY_HIDDEN_LABEL + packageName, resolved?.label ?: packageName)
                        .putString(KEY_HIDDEN_VERSION + packageName, resolved?.versionName)
                        .apply()
                    return Result.success(Unit)
                }
            }
        }

        // 2) Device Owner：直接隐藏包
        if (deviceOwner) {
            val ok = DeviceOwnerHelper.setApplicationHidden(context, packageName, true)
            if (ok && !isVisible(context, packageName)) {
                prefs(context).edit()
                    .putBoolean(KEY_HIDDEN_FLAG + packageName, true)
                    .putString(KEY_HIDDEN_MODE + packageName, MODE_DEVICE_POLICY)
                    .putString(KEY_HIDDEN_LABEL + packageName, resolved?.label ?: packageName)
                    .putString(KEY_HIDDEN_VERSION + packageName, resolved?.versionName)
                    .apply()
                return Result.success(Unit)
            }
        }

        // 2) 无 DO（或 DO 隐藏失败）：从当前用户卸载、保留数据
        if (!PrivilegedShell.available()) {
            return Result.failure(
                IllegalStateException(
                    if (deviceOwner) "设备管理员隐藏未生效，且 Shizuku 未运行"
                    else "Shizuku 服务未运行，也没有 root；开启设备管理员（DO）后可直接隐藏"
                )
            )
        }
        val useRoot = PrivilegedShell.hasRoot()
        fun sh(command: String): String? =
            PrivilegedShell.run(if (useRoot) "su -c \"$command\"" else command)

        if (!isVisible(context, packageName)) {
            prefs(context).edit()
                .putBoolean(KEY_HIDDEN_FLAG + packageName, true)
                .putString(KEY_HIDDEN_MODE + packageName, MODE_PM)
                .putString(KEY_HIDDEN_LABEL + packageName, resolved?.label ?: packageName)
                .putString(KEY_HIDDEN_VERSION + packageName, resolved?.versionName)
                .apply()
            return Result.success(Unit)
        }
        val output = sh("pm uninstall --user 0 -k '$packageName'")
        if (isVisible(context, packageName)) {
            return Result.failure(
                IllegalStateException("无法隐藏：${output?.trim()?.takeLast(160)}")
            )
        }
        prefs(context).edit()
            .putBoolean(KEY_HIDDEN_FLAG + packageName, true)
            .putString(KEY_HIDDEN_MODE + packageName, MODE_PM)
            .putString(KEY_HIDDEN_LABEL + packageName, resolved?.label ?: packageName)
            .putString(KEY_HIDDEN_VERSION + packageName, resolved?.versionName)
            .apply()
        return Result.success(Unit)
    }

    /** 只清本地隐藏标记，不动包（列表移除、恢复失败等场景用）。 */
    fun clearIconHidden(context: Context, packageName: String) =
        markHidden(context, packageName, false)

    private fun markHidden(context: Context, packageName: String, hidden: Boolean) {
        prefs(context).edit().apply {
            if (hidden) {
                putBoolean(KEY_HIDDEN_FLAG + packageName, true)
            } else {
                putBoolean(KEY_HIDDEN_FLAG + packageName, false)
                remove(KEY_HIDDEN_MODE + packageName)
                remove(KEY_HIDDEN_COMPONENTS + packageName)
                remove(KEY_HIDDEN_LABEL + packageName)
                remove(KEY_HIDDEN_VERSION + packageName)
            }
        }.apply()
    }
}

/**
 * 「常用」的候选来源：设备上所有已安装应用；排序仍按使用频次，方便找最近用的。
 */
object AppUsageRanker {

    private const val LOOKBACK_DAYS = 7L

    fun hasAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return try {
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

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
