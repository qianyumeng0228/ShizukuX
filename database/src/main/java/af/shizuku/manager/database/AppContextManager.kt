package af.shizuku.manager.database

import af.shizuku.common.ktx.loge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppEnhancement(
    val key: String,
    val title: String,
    val description: String
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
        val supportsShizukuNatively: Boolean = false
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

    private val ENH_SHELL = AppEnhancement("shell_interceptor", "Shell Acceleration", "Intercepts pm/am commands for native speed.")
    private val ENH_STORAGE = AppEnhancement("storage_proxy", "Storage Bridge", "Bypasses Android 16/17 storage restrictions.")
    private val ENH_DPM = AppEnhancement("dpm_plus", "Enhanced DPM", "Direct DevicePolicyManager access for better freezing.")
    private val ENH_NPU = AppEnhancement("npu_plus", "NPU Accelerator", "Prioritized Neural Processing Unit scheduling.")
    private val ENH_VM = AppEnhancement("vm_plus", "AVF Linux VM", "Spawns an isolated Microdroid VM for this task.")
    private val ENH_WIN = AppEnhancement("win_plus", "Window Tuner", "Forces free-form and advanced window control.")
    private val ENH_OVERLAY = AppEnhancement("overlay_manager_plus", "Overlay Bridge", "Installs and manages runtime overlays for theming.")
    private val ENH_NETWORK = AppEnhancement("network_governor_plus", "Network Governor", "DNS-based firewall and traffic control without raw iptables.")

    private val dynamicDatabase = mutableMapOf<String, AppMetadata>()
    private var settings: AppContextSettings? = null

    private val staticDatabase = mutableMapOf<String, AppMetadata>().apply {
        // --- Legacy Root Apps ---
        put("org.adaway", AppMetadata("AdAway: Open-source ad blocker. Use 'Network Governor' in ShizukuX for rootless blocking.", listOf(ENH_SHELL, ENH_NETWORK), true))
        put("dev.ukanth.ufirewall", AppMetadata("AFWall+: Firewall app. Network Governor enables DNS-based rules; raw iptables chains still need Shizuku with root.", listOf(ENH_SHELL, ENH_NETWORK), true, "Menu > Preferences > SU path", rootSupportLevel = RootSupportLevel.PARTIAL))
        put("com.samsung.android.hexinstall", AppMetadata("Hex Installer: Theming engine for Samsung. ShizukuX provides the necessary Overlay Bridge for OneUI 8+.", listOf(ENH_WIN, ENH_OVERLAY), true))
        put("com.samsung.android.themepark", AppMetadata("Theme Park: Official Samsung customization. Enhanced by ShizukuX Overlay API.", listOf(ENH_WIN, ENH_OVERLAY), true))
        put("com.keramidas.TitaniumBackup", AppMetadata("Titanium Backup: App data backup works via SU Bridge; system-level restores may still need Shizuku with root.", emptyList(), true, "Menu > More > Preferences > su executable path", rootSupportLevel = RootSupportLevel.PARTIAL))
        put("eu.darken.sdm", AppMetadata("SD Maid (Legacy): Most cleaning works via SU Bridge; deep system paths need Shizuku with root.", emptyList(), true, "Settings > Root > Binary path", rootSupportLevel = RootSupportLevel.PARTIAL))
        put("com.speedsoftware.explorer", AppMetadata("Root Explorer: File manager with elevated access. Basic browsing works via Storage Bridge; /system edits need Shizuku with root.", listOf(ENH_STORAGE), true, "Settings > Root > SU path", rootSupportLevel = RootSupportLevel.PARTIAL))
        put("com.jrummy.root.browserfree", AppMetadata("Root Browser: File manager with elevated access. Storage Bridge handles most paths; /system edits need Shizuku with root.", listOf(ENH_STORAGE), true, rootSupportLevel = RootSupportLevel.PARTIAL))
        put("com.machiav3lli.neo_backup", AppMetadata("Neo Backup: Modern open-source backup solution.", listOf(ENH_STORAGE), true, "Preferences > Advanced > Custom shell"))
        put("projekt.substratum.lite", AppMetadata("Substratum Lite: Theming engine for Android.", listOf(ENH_WIN), true))
        put("com.oasisfeng.greenify", AppMetadata("Greenify: Maximize battery savings by hibernating apps.", listOf(ENH_SHELL), true))
        put("com.franco.doze", AppMetadata("Naptime: Aggressive Doze for better battery life.", listOf(ENH_SHELL), true))
        put("com.uzumapps.wakelockdetector", AppMetadata("Wakelock Detector: Find apps draining your battery.", listOf(ENH_SHELL), true))
        put("com.asksven.betterbatterystats", AppMetadata("BetterBatteryStats: Deep dive into battery drain.", listOf(ENH_SHELL), true))
        put("com.jrummy.apps.build.prop.editor", AppMetadata("BuildProp Editor: Edit system properties. Writing /system requires Shizuku with root.", emptyList(), true, rootSupportLevel = RootSupportLevel.ROOT_REQUIRED))
        put("org.swiftapps.swiftbackup", AppMetadata("Swift Backup: Fast and reliable backup tool.", listOf(ENH_STORAGE, ENH_SHELL), true, "Settings > Storage > Custom shell"))

        // --- thejaustin's Apps ---
        put("thejaustin.hexodus", AppMetadata("Hexodus: Spiritual successor to Hex Installer for OneUI 8.", listOf(ENH_SHELL, ENH_WIN), true))
        put("thejaustin.afdroid", AppMetadata("afdroid: Expressive F-Droid client.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("thejaustin.pearity", AppMetadata("Pearity: iOS parity settings for OneUI.", listOf(ENH_SHELL), true))
        put("thejaustin.termux_ai", AppMetadata("Termux AI: AI-powered terminal with NPU integration.", listOf(ENH_NPU, ENH_VM, ENH_SHELL), true))
        put("thejaustin.appmanager", AppMetadata("AppManager: Advanced package manager with archiving.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("thejaustin.simweather", AppMetadata("SimWeather: Material You weather tool.", listOf(ENH_SHELL), true))
        put("thejaustin.contactsplus", AppMetadata("ContactsPlus: Fossify Contacts fork with M3 design.", listOf(ENH_SHELL), true))
        put("thejaustin.obtainiumplus", AppMetadata("ObtainiumPlus: AI-assisted Obtainium fork.", listOf(ENH_SHELL), true))
        put("thejaustin.snapback", AppMetadata("SnapBack: Secure Snapchat Backup Viewer.", listOf(ENH_STORAGE), true))

        // --- Software Management & Freezers ---
        put("com.aistra.hail", AppMetadata("Hail: Modern app freezer.", listOf(ENH_SHELL, ENH_DPM), true))
        put("samolego.canta", AppMetadata("Canta: Powerful system app debloater.", listOf(ENH_SHELL), true))
        put("rikka.appops", AppMetadata("App Ops: Manage hidden app permissions.", listOf(ENH_SHELL), true))
        put("com.catchingnow.icebox", AppMetadata("Ice Box: Freeze apps to save battery.", listOf(ENH_SHELL, ENH_DPM), true))
        put("com.zacharee.installwithoptions", AppMetadata("InstallWithOptions: Advanced APK installer.", listOf(ENH_SHELL), true))
        put("cf.playhi.freezeyou", AppMetadata("FreezeYou: Battery and speed optimizer.", listOf(ENH_SHELL, ENH_DPM), true))
        put("com.oasisfeng.island", AppMetadata("Island: App isolation and cloning.", listOf(ENH_DPM, ENH_SHELL), true))
        put("com.oasisfeng.island.fdroid", AppMetadata("Insular: Island fork for F-Droid.", listOf(ENH_DPM, ENH_SHELL), true))

        // --- File Management ---
        put("bin.mt.plus", AppMetadata("MT Manager: Sophisticated file manager.", listOf(ENH_STORAGE), true))
        put("pl.solidexplorer2", AppMetadata("Solid Explorer: Powerful file manager.", listOf(ENH_STORAGE), true))
        put("com.ghisler.android.TotalCommander", AppMetadata("Total Commander: Desktop-class file explorer.", listOf(ENH_STORAGE), true))
        put("com.lonelycatgames.Xplore", AppMetadata("X-Plore: Dual-pane file manager.", listOf(ENH_STORAGE), true))
        put("ru.zdevs.zarchiver", AppMetadata("ZArchiver: Comprehensive archive manager.", listOf(ENH_STORAGE), true))
        put("com.alphainventor.filemanager", AppMetadata("File Manager Plus: Cloud and local explorer.", listOf(ENH_STORAGE), true))

        // --- Automation ---
        put("net.dinglisch.android.taskerm", AppMetadata("Tasker: Advanced Android automation.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("com.arlosoft.macrodroid", AppMetadata("MacroDroid: User-friendly automation.", listOf(ENH_SHELL), true))
        put("henrichg.phoneprofilesplus", AppMetadata("PhoneProfilesPlus: Contextual device config.", listOf(ENH_SHELL), true))
        put("eu.toneiv.ubktouch", AppMetadata("UbikiTouch: Global swipe gestures.", listOf(ENH_SHELL, ENH_WIN), true))

        // --- Customization ---
        put("com.kieronquinn.ambientmusicmod", AppMetadata("Ambient Music Mod: Now Playing for everyone.", listOf(ENH_SHELL), true))
        put("com.kieronquinn.darq", AppMetadata("DarQ: Per-app force dark mode.", listOf(ENH_SHELL), true))
        put("com.zacharee.tweaker", AppMetadata("System UI Tuner: Hidden system settings.", listOf(ENH_SHELL), true))
        put("dev.lexip.hecate", AppMetadata("Adaptive-Theme: Smart dark mode.", listOf(ENH_SHELL), true))
        put("mahmud0808.colorblendr", AppMetadata("ColorBlendr: Material You color editor.", listOf(ENH_SHELL), true))
        put("com.kieronquinn.smartspacer", AppMetadata("Smartspacer: Enhanced 'At a Glance' widget.", listOf(ENH_SHELL, ENH_WIN), true))

        // --- Network & Privacy ---
        put("com.ysy.app.firewall", AppMetadata("NetWall: Rootless app firewall.", listOf(ENH_SHELL), true))
        put("com.deltazefiro.amarokhider", AppMetadata("Amarok: Hide private files and apps.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("ahmetcanarslan.shizuwall", AppMetadata("ShizuWall: Open-source app firewall.", listOf(ENH_SHELL), true))
        put("tk.zwander.wifilist", AppMetadata("WiFiList: View saved WiFi passwords.", listOf(ENH_SHELL), true))

        // --- Tools & Terminals ---
        put("p.shashank.ashellyou", AppMetadata("aShell You: Material local ADB shell.", listOf(ENH_SHELL), true))
        put("rohitkushvaha01.reterminal", AppMetadata("ReTerminal: Material 3 terminal emulator.", listOf(ENH_SHELL, ENH_VM), true))
        put("com.imranr98.obtainium", AppMetadata("Obtainium: App updates from source.", listOf(ENH_SHELL), true))
        put("com.aurora.store", AppMetadata("Aurora Store: Privacy Play Store client.", listOf(ENH_SHELL), true))
        put("com.looker.droidify", AppMetadata("Droid-ify: Material F-Droid client.", listOf(ENH_SHELL), true))
        put("eu.darken.sdmse", AppMetadata("SD Maid SE: System cleaning tool.", listOf(ENH_SHELL, ENH_STORAGE), true))
        put("com.paget96.chargemonitor", AppMetadata("Battery Charge Limit: Cap charge % to preserve battery health. Needs Shizuku with root to write charge limit sysfs.", emptyList(), true, rootSupportLevel = RootSupportLevel.ROOT_REQUIRED))
        put("com.mihonapp.mihon", AppMetadata("Mihon: Manga reader and extension manager.", listOf(ENH_SHELL), true))
    }

    fun initialize(settings: AppContextSettings) {
        this.settings = settings
        loadFromCache()
    }

    fun getMetadata(packageName: String): AppMetadata? {
        if (dynamicDatabase.isEmpty()) loadFromCache()
        return dynamicDatabase[packageName] ?: staticDatabase[packageName]
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
            loge("load app database from cache failed", e)
        }
    }

    fun updateDatabase(json: String) {
        settings?.setRemoteDbJson(json)
        settings?.setLastDbUpdate(System.currentTimeMillis())
        dynamicDatabase.clear()
        loadFromCache()
    }
}
