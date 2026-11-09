package af.shizuku.manager.module.store

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Remote module index served from the official GitHub repository
 * (`qianyumeng0228/shizukux-modules`, `modules.json`).
 *
 * The schema is intentionally a superset of the ReShizukuX catalog: every new field is
 * nullable/defaulted so an older or third-party index still parses.
 */
@Serializable
data class ModuleStoreIndex(
    val name: String = "",
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val modules: List<ModuleStoreEntry> = emptyList()
)

@Serializable
data class ModuleStoreEntry(
    val id: String,
    val name: String = "",
    val version: String? = null,
    val versionCode: Long? = null,
    val author: String? = null,
    val description: String? = null,
    /**
     * Chinese rendition of [name] / [description]. Third-party catalogs publish English only,
     * so the index carries a pre-translated copy and the client prefers it when the UI is
     * Chinese; it is never translated at runtime.
     */
    val nameZh: String? = null,
    val descriptionZh: String? = null,
    val downloadUrl: String = "",
    val fallbackUrl: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val minSdk: Int? = null,
    val hasAction: Boolean = false,
    val hasService: Boolean = false,
    val usesWebUI: Boolean = false,
    /**
     * `module.prop` calls this `usesShellBridge`; the shevery catalog spells the same fact
     * `declaresShellBridge`, so the index field is mapped explicitly.
     */
    @SerialName("declaresShellBridge")
    val usesShellBridge: Boolean = false,
    val tags: List<String> = emptyList(),
    val lastUpdated: Long? = null,
    /** Upstream repository an entry was harvested from, when the index knows it. */
    val sourceRepo: String? = null,
    /**
     * Which catalog this entry came from: `official` for the repository the project owns,
     * `community` for the GitHub `shevery-modules` topic index. Set by the client, never
     * serialized into the index itself.
     */
    val origin: String = ""
) {
    val isCommunity: Boolean
        get() = origin == "community"
    val displayName: String
        get() = name.takeIf { it.isNotBlank() } ?: id

    /** Name shown in the UI: the Chinese name when the UI language is Chinese and one exists. */
    fun localizedName(preferChinese: Boolean): String {
        if (preferChinese) nameZh?.takeIf { it.isNotBlank() }?.let { return it }
        return displayName
    }

    /** Description shown in the UI, with the same Chinese-first rule as [localizedName]. */
    fun localizedDescription(preferChinese: Boolean): String? {
        if (preferChinese) descriptionZh?.takeIf { it.isNotBlank() }?.let { return it }
        return description?.takeIf { it.isNotBlank() }
    }

    val formattedSize: String?
        get() = sizeBytes?.takeIf { it > 0 }?.let {
            val kb = it / 1024.0
            if (kb < 1024.0) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
        }
}

/**
 * UI + controller state for the store screen.
 *
 * [installedVersionCodes] is rebuilt from `AdbModuleManager.listModules` on every refresh,
 * so a module installed from a ZIP outside the store still shows up as installed.
 */
data class ModuleStoreUiState(
    val loading: Boolean = false,
    val entries: List<ModuleStoreEntry> = emptyList(),
    val installedVersionCodes: Map<String, Long?> = emptyMap(),
    val installingId: String? = null,
    val installProgress: Float? = null,
    val error: String? = null,
    val sourceName: String = "",
    val sourceHost: String = "",
    val lastUpdated: Long? = null,
    val loaded: Boolean = false
) {
    fun installedVersionCode(id: String): Long? = installedVersionCodes[id]

    fun isInstalled(entry: ModuleStoreEntry): Boolean = installedVersionCodes.containsKey(entry.id)

    fun hasUpdate(entry: ModuleStoreEntry): Boolean {
        val local = installedVersionCode(entry.id) ?: return false
        val remote = entry.versionCode ?: return false
        return remote > local
    }
}
