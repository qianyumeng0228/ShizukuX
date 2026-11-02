package af.shizuku.manager.module

/**
 * Update status of a single module. Ported from Nightzuku's update model;
 * the actual update lookup is a local concern in ShizukuX (no NightDog backend).
 */
data class UpdateResult(
    val hasUpdate: Boolean = false,
    val latestVersion: String? = null
)

/**
 * Aggregate report of a background module health check.
 * Kept structurally identical to Nightzuku's NightDogClient.ModuleCheckReport so
 * the TV/Wear screens can render without a NightDog backend.
 */
data class ModuleCheckReport(
    val healthyModules: Int = 0,
    val totalModules: Int = 0,
    val updatesAvailableCount: Int = 0,
    val issuesCount: Int = 0,
    val healthByModule: Map<String, ModuleHealthStatus> = emptyMap(),
    val updatesByModule: Map<String, UpdateResult> = emptyMap()
)
