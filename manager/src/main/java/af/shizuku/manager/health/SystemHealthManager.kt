package af.shizuku.manager.health

import com.topjohnwu.superuser.Shell
import timber.log.Timber

data class SystemHealthData(
    val cpuTempCelsius: Float,
    val batteryTempCelsius: Float,
    val binderLatencyMs: Float // Placeholder for aggregated latency
)

/**
 * Scaffolding for a future system-health dashboard.
 *
 * Currently has **no callers** anywhere in the manager module (grep-verified 2026-10-25).
 * Kept rather than deleted because it is isolated (object singleton, not referenced from
 * Manifest/UI) and may be wired up later. **Warning**: [getSystemHealth] runs blocking
 * `Shell.cmd(...).exec()` calls — do NOT call it from the main thread; if it is ever wired
 * to a UI surface, wrap it in `Dispatchers.IO` first.
 */
object SystemHealthManager {
    fun getSystemHealth(): SystemHealthData {
        var cpuTemp = 0f
        var batteryTemp = 0f

        try {
            // Read CPU temp (commonly thermal_zone0 or cpu_thermal)
            val cpuResult = Shell.cmd("cat /sys/class/thermal/thermal_zone0/temp").exec()
            if (cpuResult.isSuccess && cpuResult.out.isNotEmpty()) {
                val raw = cpuResult.out.first().toFloatOrNull() ?: 0f
                cpuTemp = if (raw > 1000) raw / 1000f else raw
            }

            // Read Battery temp (dumpsys battery)
            val batteryResult = Shell.cmd("dumpsys battery | grep temperature").exec()
            if (batteryResult.isSuccess && batteryResult.out.isNotEmpty()) {
                val raw = batteryResult.out.first().substringAfter(":").trim().toFloatOrNull() ?: 0f
                batteryTemp = raw / 10f // Battery temp is usually in tenths of a degree
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to read system health metrics")
        }

        return SystemHealthData(cpuTemp, batteryTemp, 0f)
    }
}
