package af.shizuku.manager.database

import timber.log.Timber

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppEnhancement(
    val key: String,
    val title: String,
    val description: String,
    val titleZh: String? = null,
    val descriptionZh: String? = null
)

@Serializable
enum class RootSupportLevel {
    FULL,
    PARTIAL,
    ROOT_REQUIRED
}

interface AppContextSettings {
    fun getRemoteDbJson(): String?
    fun setRemoteDbJson(json: String)
    fun setLastDbUpdate(time: Long)
}

object AppContextManager {

    @Serializable
    data class AppMetadata(
        val description: String,
        val potentialEnhancements: List<AppEnhancement> = emptyList(),
        val isVerified: Boolean = false,
        val suPathSettingNav: String? = null,
        val rootSupportLevel: RootSupportLevel = RootSupportLevel.FULL,
        val supportsShizukuNatively: Boolean = false,
        val descriptionZh: String? = null
    )

    @Serializable
    private data class RemoteDbApps(
        val apps: Map<String, RemoteAppMetadata>
    )

    @Serializable
    private data class RemoteAppMetadata(
        val description: String,
        val enhancements: List<String> = emptyList(),
        val verified: Boolean = false,
        val root_support: String = "full",
        val shizuku_aware: Boolean = false
    )

    private val jsonConfig = Json { 
        ignoreUnknownKeys = true 
        coerceInputValues = true
    }

    private val ENH_SHELL = AppEnhancement("shell_interceptor", "Shell Acceleration", "Intercepts pm/am commands for native speed.", "Shell 加速", "拦截 pm/am 命令，实现原生速度。")
    private val ENH_STORAGE = AppEnhancement("storage_proxy", "Storage Bridge", "Bypasses Android 16/17 storage restrictions.", "存储桥接", "绕过 Android 16/17 的存储访问限制。")
    private val ENH_DPM = AppEnhancement("dpm_plus", "Enhanced DPM", "Direct DevicePolicyManager access for better freezing.", "增强 DPM", "直连 DevicePolicyManager，冻结/解冻更稳定。")
    private val ENH_NPU = AppEnhancement("npu_plus", "NPU Accelerator", "Prioritized Neural Processing Unit scheduling.", "NPU 加速", "优先调度神经处理单元。")
    private val ENH_VM = AppEnhancement("vm_plus", "AVF Linux VM", "Spawns an isolated Microdroid VM for this task.", "AVF Linux 虚拟机", "为任务启动隔离的 Microdroid 虚拟机。")
    private val ENH_WIN = AppEnhancement("win_plus", "Window Tuner", "Forces free-form and advanced window control.", "窗口调节", "强制自由窗口与高级窗口控制。")
    private val ENH_OVERLAY = AppEnhancement("overlay_manager_plus", "Overlay Bridge", "Installs and manages runtime overlays for theming.", "Overlay 桥接", "安装并管理运行时覆盖层，用于主题定制。")
    private val ENH_NETWORK = AppEnhancement("network_governor_plus", "Network Governor", "DNS-based firewall and traffic control without raw iptables.", "网络治理", "基于 DNS 的防火墙与流量控制，无需原生 iptables。")

    private val dynamicDatabase = mutableMapOf<String, AppMetadata>()
    private var settings: AppContextSettings? = null

    private val staticDatabase = mutableMapOf<String, AppMetadata>().apply {
        // --- Core Root & Modding ---
        put("com.topjohnwu.magisk", AppMetadata("Magisk: The systemless root solution. ShizukuX spoofs its presence to other apps.", emptyList(), true, rootSupportLevel = RootSupportLevel.PARTIAL, descriptionZh = "Magisk：免改系统的 Root 方案。ShizukuX 会向其他应用伪装其存在。"))
        put("eu.chainfire.supersu", AppMetadata("SuperSU: Legacy root solution. ShizukuX spoofs its presence to other apps for maximum legacy compatibility.", emptyList(), true, rootSupportLevel = RootSupportLevel.PARTIAL, descriptionZh = "SuperSU：老牌 Root 方案。ShizukuX 向其他应用伪装其存在，最大化旧版兼容。"))
        put("org.lsposed.manager", AppMetadata("LSPosed: Xposed framework modern implementation. ShizukuX successfully masks its presence.", emptyList(), true, rootSupportLevel = RootSupportLevel.PARTIAL, descriptionZh = "LSPosed：Xposed 框架的现代实现。ShizukuX 可成功隐藏其存在。"))
        put("com.vipercn.viper4android_v2", AppMetadata("ViPER4Android FX: Audio effects engine. Driver installation requires real root, but basic setup is mockable.", listOf(ENH_SHELL), true, rootSupportLevel = RootSupportLevel.ROOT_REQUIRED, descriptionZh = "ViPER4Android FX：音效引擎。驱动安装需要真实 Root，基础配置可被模拟。"))

        // --- Legacy Root Apps ---
        put("org.adaway", AppMetadata("AdAway: Open-source ad blocker. Use 'Network Governor' in ShizukuX for rootless blocking.", listOf(ENH_SHELL, ENH_NETWORK), true, descriptionZh = "AdAway：开源广告拦截器。可用 ShizukuX 的「网络治理」实现免 Root 拦截。"))
        put("dev.ukanth.ufirewall", AppMetadata("AFWall+: Firewall app. Fully functional rootless under ShizukuX via automatic local iptables fallback mocking.", listOf(ENH_SHELL, ENH_NETWORK), true, "Menu > Preferences > SU path", rootSupportLevel = RootSupportLevel.FULL, descriptionZh = "AFWall+：防火墙应用。ShizukuX 下通过本地 iptables 自动模拟即可免 Root 完整运行。"))
        put("com.samsung.android.hexinstall", AppMetadata("Hex Installer: Theming engine for Samsung. ShizukuX provides the necessary Overlay Bridge for OneUI 8+.", listOf(ENH_WIN, ENH_OVERLAY), true, descriptionZh = "Hex Installer：三星主题引擎。ShizukuX 为 OneUI 8+ 提供所需的 Overlay 桥接。"))
        put("com.samsung.android.themepark", AppMetadata("Theme Park: Official Samsung customization. Enhanced by ShizukuX Overlay API.", listOf(ENH_WIN, ENH_OVERLAY), true, descriptionZh = "Theme Park：三星官方个性化工具。由 ShizukuX Overlay API 增强。"))
        put("com.keramidas.TitaniumBackup", AppMetadata("Titanium Backup: App data backup and restore fully works via SU Bridge using native 'bu' mapping.", emptyList(), true, "Menu > More > Preferences > su executable path", rootSupportLevel = RootSupportLevel.FULL, descriptionZh = "Titanium Backup：应用数据备份与恢复，通过 SU 桥接的原生 bu 映射即可完整运行。"))
        put("eu.darken.sdm", AppMetadata("SD Maid (Legacy): Fully functional via SU Bridge; deep system paths and shell execution are safely routed.", emptyList(), true, "Settings > Root > Binary path", rootSupportLevel = RootSupportLevel.FULL, descriptionZh = "SD Maid（旧版）：通过 SU 桥接完整运行；深层系统路径与 shell 执行均被安全转发。"))
        put("com.speedsoftware.explorer", AppMetadata("Root Explorer: File manager with elevated access. Storage Bridge handles browsing, and SU Bridge proxy allows deep file viewing.", listOf(ENH_STORAGE), true, "Settings > Root > SU path", rootSupportLevel = RootSupportLevel.FULL, descriptionZh = "Root Explorer：高权限文件管理器。存储桥接负责浏览，SU 桥接代理支持深层查看。"))
        put("com.jrummy.root.browserfree", AppMetadata("Root Browser: File manager with elevated access. Storage Bridge and SU Bridge interceptor handle system browsing.", listOf(ENH_STORAGE), true, rootSupportLevel = RootSupportLevel.FULL, descriptionZh = "Root Browser：高权限文件管理器。存储桥接与 SU 桥接拦截器处理系统浏览。"))
        put("com.jrummy.apps.build.prop.editor", AppMetadata("BuildProp Editor: Edit system properties. Fully functional rootless under ShizukuX via build.prop shadow-copy redirection.", emptyList(), true, rootSupportLevel = RootSupportLevel.FULL, descriptionZh = "BuildProp Editor：编辑系统属性。通过 build.prop 影子副本重定向，免 Root 完整运行。"))
        put("com.machiav3lli.neo_backup", AppMetadata("Neo Backup: Modern open-source backup solution.", listOf(ENH_STORAGE), true, "Preferences > Advanced > Custom shell", descriptionZh = "Neo Backup：现代开源备份方案。"))
        put("projekt.substratum.lite", AppMetadata("Substratum Lite: Theming engine for Android.", listOf(ENH_WIN), true, descriptionZh = "Substratum Lite：Android 主题引擎。"))
        put("com.oasisfeng.greenify", AppMetadata("Greenify: Maximize battery savings by hibernating apps.", listOf(ENH_SHELL), true, descriptionZh = "Greenify：通过休眠应用最大化省电。"))
        put("com.franco.doze", AppMetadata("Naptime: Aggressive Doze for better battery life.", listOf(ENH_SHELL), true, descriptionZh = "Naptime：激进 Doze 模式，续航更久。"))
        put("com.uzumapps.wakelockdetector", AppMetadata("Wakelock Detector: Find apps draining your battery.", listOf(ENH_SHELL), true, descriptionZh = "Wakelock Detector：找出耗电的应用。"))
        put("com.asksven.betterbatterystats", AppMetadata("BetterBatteryStats: Deep dive into battery drain.", listOf(ENH_SHELL), true, descriptionZh = "BetterBatteryStats：深入分析耗电情况。"))
        put("org.swiftapps.swiftbackup", AppMetadata("Swift Backup: Fast and reliable backup tool.", listOf(ENH_STORAGE, ENH_SHELL), true, "Settings > Storage > Custom shell", descriptionZh = "Swift Backup：快速可靠的备份工具。"))

        // --- thejaustin's Apps ---
        put("thejaustin.hexodus", AppMetadata("Hexodus: Spiritual successor to Hex Installer for OneUI 8.", listOf(ENH_SHELL, ENH_WIN), true, descriptionZh = "Hexodus：Hex Installer 在 OneUI 8 上的精神续作。"))
        put("thejaustin.afdroid", AppMetadata("afdroid: Expressive F-Droid client.", listOf(ENH_SHELL, ENH_STORAGE), true, descriptionZh = "afdroid：富有表现力的 F-Droid 客户端。"))
        put("thejaustin.pearity", AppMetadata("Pearity: iOS parity settings for OneUI.", listOf(ENH_SHELL), true, descriptionZh = "Pearity：OneUI 的 iOS 风格设置。"))
        put("thejaustin.termux_ai", AppMetadata("Termux AI: AI-powered terminal with NPU integration.", listOf(ENH_NPU, ENH_VM, ENH_SHELL), true, descriptionZh = "Termux AI：集成 NPU 的 AI 终端。"))
        put("thejaustin.appmanager", AppMetadata("AppManager: Advanced package manager with archiving.", listOf(ENH_SHELL, ENH_STORAGE), true, descriptionZh = "AppManager：支持归档的高级包管理器。"))
        put("thejaustin.simweather", AppMetadata("SimWeather: Material You weather tool.", listOf(ENH_SHELL), true, descriptionZh = "SimWeather：Material You 天气工具。"))
        put("thejaustin.contactsplus", AppMetadata("ContactsPlus: Fossify Contacts fork with M3 design.", listOf(ENH_SHELL), true, descriptionZh = "ContactsPlus：M3 设计的 Fossify 联系人分支。"))
        put("thejaustin.obtainiumplus", AppMetadata("ObtainiumPlus: AI-assisted Obtainium fork.", listOf(ENH_SHELL), true, descriptionZh = "ObtainiumPlus：AI 辅助的 Obtainium 分支。"))
        put("thejaustin.snapback", AppMetadata("SnapBack: Secure Snapchat Backup Viewer.", listOf(ENH_STORAGE), true, descriptionZh = "SnapBack：安全的 Snapchat 备份查看器。"))

        // --- Software Management & Freezers ---
        put("com.aistra.hail", AppMetadata("Hail: Modern app freezer.", listOf(ENH_SHELL, ENH_DPM), true, descriptionZh = "雹：现代化应用冻结工具。"))
        put("com.rosan.dhizuku", AppMetadata("Dhizuku: Device Owner sharing bridge.", listOf(ENH_DPM), true, descriptionZh = "Dhizuku：设备所有者权限共享桥。"))
        put("samolego.canta", AppMetadata("Canta: Powerful system app debloater.", listOf(ENH_SHELL), true, descriptionZh = "Canta：强大的系统应用卸载工具。"))
        put("rikka.appops", AppMetadata("App Ops: Manage hidden app permissions.", listOf(ENH_SHELL), true, descriptionZh = "App Ops：管理隐藏的应用权限。"))
        put("com.catchingnow.icebox", AppMetadata("Ice Box: Freeze apps to save battery.", listOf(ENH_SHELL, ENH_DPM), true, descriptionZh = "冰箱：冻结应用以省电。"))
        put("com.zacharee.installwithoptions", AppMetadata("InstallWithOptions: Advanced APK installer.", listOf(ENH_SHELL), true, descriptionZh = "InstallWithOptions：高级 APK 安装器。"))
        put("cf.playhi.freezeyou", AppMetadata("FreezeYou: Battery and speed optimizer.", listOf(ENH_SHELL, ENH_DPM), true, descriptionZh = "FreezeYou：省电与提速优化工具。"))
        put("com.oasisfeng.island", AppMetadata("Island: App isolation and cloning.", listOf(ENH_DPM, ENH_SHELL), true, descriptionZh = "Island：应用隔离与分身。"))
        put("com.oasisfeng.island.fdroid", AppMetadata("Insular: Island fork for F-Droid.", listOf(ENH_DPM, ENH_SHELL), true, descriptionZh = "Insular：面向 F-Droid 的 Island 分支。"))

        // --- File Management ---
        put("bin.mt.plus", AppMetadata("MT Manager: Sophisticated file manager.", listOf(ENH_STORAGE), true, descriptionZh = "MT 管理器：功能强大的文件管理器。"))
        put("pl.solidexplorer2", AppMetadata("Solid Explorer: Powerful file manager.", listOf(ENH_STORAGE), true, descriptionZh = "Solid Explorer：强大的文件管理器。"))
        put("com.ghisler.android.TotalCommander", AppMetadata("Total Commander: Desktop-class file explorer.", listOf(ENH_STORAGE), true, descriptionZh = "Total Commander：桌面级文件管理器。"))
        put("com.lonelycatgames.Xplore", AppMetadata("X-Plore: Dual-pane file manager.", listOf(ENH_STORAGE), true, descriptionZh = "X-Plore：双栏文件管理器。"))
        put("ru.zdevs.zarchiver", AppMetadata("ZArchiver: Comprehensive archive manager.", listOf(ENH_STORAGE), true, descriptionZh = "ZArchiver：全能压缩包管理器。"))
        put("com.alphainventor.filemanager", AppMetadata("File Manager Plus: Cloud and local explorer.", listOf(ENH_STORAGE), true, descriptionZh = "File Manager Plus：云端与本地文件管理器。"))

        // --- Automation ---
        put("net.dinglisch.android.taskerm", AppMetadata("Tasker: Advanced Android automation.", listOf(ENH_SHELL, ENH_STORAGE), true, descriptionZh = "Tasker：高级 Android 自动化工具。"))
        put("com.arlosoft.macrodroid", AppMetadata("MacroDroid: User-friendly automation.", listOf(ENH_SHELL), true, descriptionZh = "MacroDroid：易用的自动化工具。"))
        put("henrichg.phoneprofilesplus", AppMetadata("PhoneProfilesPlus: Contextual device config.", listOf(ENH_SHELL), true, descriptionZh = "PhoneProfilesPlus：情境自动配置。"))
        put("eu.toneiv.ubktouch", AppMetadata("UbikiTouch: Global swipe gestures.", listOf(ENH_SHELL, ENH_WIN), true, descriptionZh = "UbikiTouch：全局滑动手势。"))

        // --- Customization ---
        put("com.kieronquinn.ambientmusicmod", AppMetadata("Ambient Music Mod: Now Playing for everyone.", listOf(ENH_SHELL), true, descriptionZh = "Ambient Music Mod：人人可用的 Now Playing。"))
        put("com.kieronquinn.darq", AppMetadata("DarQ: Per-app force dark mode.", listOf(ENH_SHELL), true, descriptionZh = "DarQ：按应用强制深色模式。"))
        put("com.zacharee.tweaker", AppMetadata("System UI Tuner: Hidden system settings.", listOf(ENH_SHELL), true, descriptionZh = "System UI Tuner：隐藏的系统设置。"))
        put("dev.lexip.hecate", AppMetadata("Adaptive-Theme: Smart dark mode.", listOf(ENH_SHELL), true, descriptionZh = "Adaptive-Theme：智能深色模式。"))
        put("mahmud0808.colorblendr", AppMetadata("ColorBlendr: Material You color editor.", listOf(ENH_SHELL), true, descriptionZh = "ColorBlendr：Material You 配色编辑器。"))
        put("com.kieronquinn.smartspacer", AppMetadata("Smartspacer: Enhanced 'At a Glance' widget.", listOf(ENH_SHELL, ENH_WIN), true, descriptionZh = "Smartspacer：增强版「一览」小部件。"))

        // --- Network & Privacy ---
        put("com.ysy.app.firewall", AppMetadata("NetWall: Rootless app firewall.", listOf(ENH_SHELL), true, descriptionZh = "NetWall：免 Root 应用防火墙。"))
        put("com.deltazefiro.amarokhider", AppMetadata("Amarok: Hide private files and apps.", listOf(ENH_SHELL, ENH_STORAGE), true, descriptionZh = "Amarok：隐藏隐私文件与应用。"))
        put("ahmetcanarslan.shizuwall", AppMetadata("ShizuWall: Open-source app firewall.", listOf(ENH_SHELL), true, descriptionZh = "ShizuWall：开源应用防火墙。"))
        put("tk.zwander.wifilist", AppMetadata("WiFiList: View saved WiFi passwords.", listOf(ENH_SHELL), true, descriptionZh = "WiFiList：查看已保存的 WiFi 密码。"))

        // --- Tools & Terminals ---
        put("p.shashank.ashellyou", AppMetadata("aShell You: Material local ADB shell.", listOf(ENH_SHELL), true, descriptionZh = "aShell You：Material 风格本地 ADB Shell。"))
        put("rohitkushvaha01.reterminal", AppMetadata("ReTerminal: Material 3 terminal emulator.", listOf(ENH_SHELL, ENH_VM), true, descriptionZh = "ReTerminal：Material 3 终端模拟器。"))
        put("com.imranr98.obtainium", AppMetadata("Obtainium: App updates from source.", listOf(ENH_SHELL), true, descriptionZh = "Obtainium：从源站更新应用。"))
        put("com.aurora.store", AppMetadata("Aurora Store: Privacy Play Store client.", listOf(ENH_SHELL), true, descriptionZh = "Aurora Store：隐私优先的 Play 商店客户端。"))
        put("com.looker.droidify", AppMetadata("Droid-ify: Material F-Droid client.", listOf(ENH_SHELL), true, descriptionZh = "Droid-ify：Material 风格 F-Droid 客户端。"))
        put("eu.darken.sdmse", AppMetadata("SD Maid SE: System cleaning tool.", listOf(ENH_SHELL, ENH_STORAGE), true, descriptionZh = "SD Maid SE：系统清理工具。"))
        put("com.paget96.chargemonitor", AppMetadata("Battery Charge Limit: Cap charge % to preserve battery health. Needs Shizuku with root to write charge limit sysfs.", emptyList(), true, rootSupportLevel = RootSupportLevel.ROOT_REQUIRED, descriptionZh = "Battery Charge Limit：限制充电百分比以保护电池健康。需 Root 模式 Shizuku 写入充电限制。"))
        put("com.mihonapp.mihon", AppMetadata("Mihon: Manga reader and extension manager.", listOf(ENH_SHELL), true, descriptionZh = "Mihon：漫画阅读器与扩展管理器。"))
    }

    fun initialize(settings: AppContextSettings) {
        this.settings = settings
        loadFromCache()
    }

    fun getMetadata(packageName: String): AppMetadata? {
        if (dynamicDatabase.isEmpty()) loadFromCache()
        val dynamic = dynamicDatabase[packageName]
        val static = staticDatabase[packageName]
        return when {
            dynamic == null -> static
            static == null -> dynamic
            else -> {
                // 合并：增强功能取并集（远程库缺项时由内置库兜底），其余字段以远程库为准；中文文案缺项时从内置库补
                val enhancements = (static.potentialEnhancements + dynamic.potentialEnhancements)
                    .distinctBy { it.key }
                dynamic.copy(
                    potentialEnhancements = enhancements,
                    descriptionZh = dynamic.descriptionZh ?: static.descriptionZh
                )
            }
        }
    }

    fun getRootLegacyPackages(): Map<String, List<String>> {
        return mapOf(
            "Backup & Cleaning" to listOf(
                "com.keramidas.TitaniumBackup",
                "eu.darken.sdm",
                "org.swiftapps.swiftbackup",
                "com.machiav3lli.neo_backup"
            ),
            "System Customization" to listOf(
                "com.speedsoftware.explorer",
                "com.jrummy.root.browserfree",
                "projekt.substratum.lite",
                "com.zacharee.tweaker",
                "com.samsung.android.themepark",
                "com.samsung.android.hexinstall"
            ),
            "Battery & Optimization" to listOf(
                "com.oasisfeng.greenify",
                "com.franco.doze",
                "com.paget96.chargemonitor"
            ),
            "Privacy & Security" to listOf(
                "org.adaway",
                "dev.ukanth.ufirewall",
                "com.uzumapps.wakelockdetector"
            ),
            "Advanced Tools" to listOf(
                "com.asksven.betterbatterystats",
                "com.jrummy.apps.build.prop.editor"
            )
        )
    }
    
    fun getDescription(packageName: String): String? = getMetadata(packageName)?.description

    private fun loadFromCache() {
        val json = settings?.getRemoteDbJson() ?: return
        try {
            val remoteDb = jsonConfig.decodeFromString<RemoteDbApps>(json)
            remoteDb.apps.forEach { (pkg, remoteApp) ->
                val enhancements = remoteApp.enhancements.mapNotNull { key ->
                    when(key) {
                        "shell_interceptor" -> ENH_SHELL
                        "storage_proxy" -> ENH_STORAGE
                        "dpm_plus" -> ENH_DPM
                        "npu_plus" -> ENH_NPU
                        "vm_plus" -> ENH_VM
                        "win_plus" -> ENH_WIN
                        "overlay_manager_plus" -> ENH_OVERLAY
                        "network_governor_plus" -> ENH_NETWORK
                        else -> null
                    }
                }
                val rootLevel = when (remoteApp.root_support.lowercase()) {
                    "partial" -> RootSupportLevel.PARTIAL
                    "required" -> RootSupportLevel.ROOT_REQUIRED
                    else -> RootSupportLevel.FULL
                }
                dynamicDatabase[pkg] = AppMetadata(
                    description = remoteApp.description,
                    potentialEnhancements = enhancements,
                    isVerified = remoteApp.verified,
                    rootSupportLevel = rootLevel,
                    supportsShizukuNatively = remoteApp.shizuku_aware
                )
            }
        } catch (e: Exception) {
            Timber.e("load app database from cache failed", e)
        }
    }

    fun updateDatabase(json: String) {
        settings?.setRemoteDbJson(json)
        settings?.setLastDbUpdate(System.currentTimeMillis())
        dynamicDatabase.clear()
        loadFromCache()
    }
}
