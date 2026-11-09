package af.shizuku.manager.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuWait
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One `label: value` row. */
data class InfoItem(val label: String, val value: String)

/** A titled group of rows. */
data class InfoSection(val title: String, val rows: List<InfoItem>)

/**
 * The device facts a user cannot otherwise see.
 *
 * Everything is read either from the framework (which needs no privilege) or from one batched root
 * shell: `getprop`, `/proc/version`, `/proc/cpuinfo`, `df` and `dumpsys battery` in a single call,
 * so the page costs one round trip instead of a dozen.
 */
object DeviceInfoManager {

    fun isAvailable(): Boolean = ShizukuWait.ping()

    fun collect(context: Context): List<InfoSection> {
        val props = shell(batchScript())?.let { parseBatch(it) } ?: Parsed()
        return listOf(
            InfoSection("设备", deviceRows(context, props)),
            InfoSection("系统", systemRows(context, props)),
            InfoSection("屏幕", screenRows(context)),
            InfoSection("处理器", cpuRows(context, props)),
            InfoSection("内存", memoryRows(context)),
            InfoSection("存储", storageRows(context)),
            InfoSection("电池", batteryRows(props))
        )
    }

    private fun deviceRows(context: Context, props: Parsed): List<InfoItem> = listOf(
        InfoItem("型号", Build.MODEL),
        InfoItem("品牌", Build.BRAND),
        InfoItem("制造商", Build.MANUFACTURER),
        InfoItem("设备代号", Build.DEVICE + " / " + Build.PRODUCT),
        InfoItem("主板", Build.BOARD + " / " + Build.HARDWARE),
        InfoItem("序列号", props.props["ro.serialno"] ?: props.props["ro.boot.serialno"] ?: "-"),
        InfoItem("指纹", Build.FINGERPRINT)
    )

    private fun systemRows(context: Context, props: Parsed): List<InfoItem> = listOf(
        InfoItem("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"),
        InfoItem("安全补丁", Build.VERSION.SECURITY_PATCH.ifEmpty { props.props["ro.build.version.security_patch"] ?: "-" }),
        InfoItem("版本号", props.props["ro.build.display.id"] ?: Build.DISPLAY),
        InfoItem("内核", props.kernel.ifEmpty { System.getProperty("os.version") ?: "-" }),
        InfoItem("基带", props.props["gsm.version.baseband"]?.takeIf { it.isNotBlank() } ?: radioVersion()),
        InfoItem("系统启动", bootTime(context))
    )

    private fun screenRows(context: Context): List<InfoItem> {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        return listOf(
            InfoItem("分辨率", metrics.widthPixels.toString() + " × " + metrics.heightPixels),
            InfoItem("密度", metrics.densityDpi.toString() + " dpi (" + metrics.density + "x)"),
            InfoItem("刷新率", String.format(Locale.ROOT, "%.0f Hz", manager.defaultDisplay.refreshRate)),
            InfoItem("字体缩放", String.format(Locale.ROOT, "%.2f", metrics.scaledDensity))
        )
    }

    private fun cpuRows(context: Context, props: Parsed): List<InfoItem> = listOf(
        InfoItem("ABI", Build.SUPPORTED_ABIS.joinToString(", ")),
        InfoItem("核心数", Runtime.getRuntime().availableProcessors().toString()),
        InfoItem("SoC 厂商", socVendor(props))
    )

    /**
     * The SoC vendor — the company that made the chip, never the one that assembled the phone.
     *
     * `ro.soc.manufacturer` answers the platform's own vendor code ("QTI" for Qualcomm), and
     * Build.SOC_MANUFACTURER (API 31+) answers the same code without needing a shell. The old
     * fallback here was Build.MANUFACTURER, which is why a Xiaomi phone with a Qualcomm SoC
     * claimed "Xiaomi": the brand is not the SoC vendor, so an unreadable property now yields a
     * dash instead. Codes are translated to the vendor's name because "QTI" alone means nothing.
     */
    private fun socVendor(props: Parsed): String {
        var raw = props.props["ro.soc.manufacturer"]?.trim().orEmpty()
        if (raw.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            raw = Build.SOC_MANUFACTURER.trim()
        }
        if (raw.isEmpty()) return "-"
        return SOC_VENDORS[raw.lowercase(Locale.ROOT)] ?: raw
    }

    private val SOC_VENDORS = mapOf(
        "qti" to "Qualcomm（高通）",
        "qualcomm" to "Qualcomm（高通）",
        "mediatek" to "MediaTek（联发科）",
        "mtk" to "MediaTek（联发科）",
        "samsung" to "Samsung（三星）",
        "exynos" to "Samsung（三星）",
        "hisilicon" to "HiSilicon（海思）",
        "kirin" to "HiSilicon（海思）",
        "google" to "Google（Tensor）",
        "unisoc" to "Unisoc（紫光展锐）",
        "spreadtrum" to "Unisoc（紫光展锐）"
    )

    /**
     * `Build.getRadioVersion()` answers the baseband twice ("MPSS…,MPSS…") on most devices: the
     * two halves are the same firmware built for two slots. One copy is the fact, two is noise.
     */
    private fun radioVersion(): String {
        val raw = Build.getRadioVersion()?.trim().orEmpty()
        if (raw.isEmpty() || raw == "unknown") return "-"
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return parts.joinToString(",").ifEmpty { "-" }
    }

    private fun memoryRows(context: Context): List<InfoItem> {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return listOf(
            InfoItem("总内存", formatBytes(info.totalMem)),
            InfoItem("可用内存", formatBytes(info.availMem)),
            InfoItem("低内存阈值", formatBytes(info.threshold))
        )
    }

    private fun storageRows(context: Context): List<InfoItem> {
        val data = StatFs(context.dataDir.path)
        val rows = mutableListOf(
            InfoItem("/data 总容量", formatBytes(data.totalBytes)),
            InfoItem("/data 可用", formatBytes(data.availableBytes))
        )
        // On a single-partition device the external files dir lives on the very same volume; two
        // identical rows would only look like two storages.
        val external = context.getExternalFilesDir(null)
        if (external != null) {
            val fs = StatFs(external.path)
            if (fs.totalBytes != data.totalBytes) {
                rows.add(InfoItem("外部存储总容量", formatBytes(fs.totalBytes)))
                rows.add(InfoItem("外部存储可用", formatBytes(fs.availableBytes)))
            }
        }
        return rows
    }

    private fun batteryRows(props: Parsed): List<InfoItem> {
        val rows = mutableListOf<InfoItem>()
        // `dumpsys battery` reports level and scale separately; a device whose scale is not 100
        // (some ROMs report 0-255) would otherwise show a percentage that never reaches 100.
        val level = props.battery["电量"]?.trim()?.toIntOrNull()
        val scale = props.battery["满值"]?.trim()?.toIntOrNull()
        props.battery.forEach { (label, value) ->
            when (label) {
                // The scale is only interesting as the denominator of 电量, so fold it in.
                "满值" -> Unit
                "电量" -> rows.add(InfoItem(label, chargePercent(level, scale)))
                else -> rows.add(InfoItem(label, formatBattery(label, value)))
            }
        }
        if (rows.isEmpty()) rows.add(InfoItem("电池", "读取失败"))
        return rows
    }

    private fun chargePercent(level: Int?, scale: Int?): String {
        if (level == null) return "-"
        val max = scale?.takeIf { it > 0 } ?: 100
        return "${(level * 100 / max)}%"
    }

    /** `dumpsys battery` prints raw codes; every row is translated to what the number means. */
    private fun formatBattery(label: String, raw: String): String {
        val value = raw.trim()
        return when (label) {
            "AC 供电", "USB 供电", "无线供电", "已安装" -> if (value == "true") "是" else "否"
            "状态" -> when (value) {
                "1" -> "未知"
                "2" -> "充电中"
                "3" -> "放电中"
                "4" -> "未充电"
                "5" -> "已充满"
                else -> value
            }
            "健康度" -> when (value) {
                "1" -> "未知"
                "2" -> "良好"
                "3" -> "过热"
                "4" -> "已损坏"
                "5" -> "过压"
                "6" -> "失效"
                "7" -> "过冷"
                else -> value
            }
            "电量" -> chargePercent(value.toIntOrNull(), null)
            "温度" -> value.toIntOrNull()
                ?.let { String.format(Locale.ROOT, "%.1f °C", it / 10.0) } ?: value
            "电压" -> value.toIntOrNull()
                ?.let { String.format(Locale.ROOT, "%.2f V (%d mV)", it / 1000.0, it) } ?: value
            "最大充电电流" -> value.toIntOrNull()
                ?.let { String.format(Locale.ROOT, "%d mA", it / 1000) } ?: value
            "最大充电电压" -> value.toIntOrNull()
                ?.let { String.format(Locale.ROOT, "%.2f V", it / 1_000_000.0) } ?: value
            else -> value
        }
    }

    /** Uptime, which the framework answers without any shell. */
    private fun bootTime(context: Context): String {
        val uptime = android.os.SystemClock.elapsedRealtime()
        val days = TimeUnit.MILLISECONDS.toDays(uptime)
        val hours = TimeUnit.MILLISECONDS.toHours(uptime) % 24
        val minutes = TimeUnit.MILLISECONDS.toMinutes(uptime) % 60
        return if (days > 0) {
            "${days}天 ${hours}小时 ${minutes}分"
        } else if (hours > 0) {
            "${hours}小时 ${minutes}分"
        } else {
            "${minutes}分"
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        return String.format(Locale.ROOT, "%.2f GB (%d B)", gb, bytes)
    }

    /** The single shell call: every block is tagged so the output can be split again. */
    private fun batchScript(): String = listOf(
        "echo @@PROP", "getprop",
        "echo @@KERNEL", "cat /proc/version 2>/dev/null",
        "echo @@CPU", "cat /proc/cpuinfo 2>/dev/null | head -60",
        "echo @@BATTERY", "dumpsys battery 2>/dev/null | head -30"
    ).joinToString("; ")

    private class Parsed {
        val props = mutableMapOf<String, String>()
        var kernel: String = ""
        var cpuModel: String = ""
        val battery = mutableMapOf<String, String>()
    }

    private fun parseBatch(out: String): Parsed {
        val parsed = Parsed()
        var block = ""
        out.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("@@") -> block = line.removePrefix("@@")
                line.isEmpty() -> Unit
                block == "PROP" -> {
                    val cut = line.indexOf(':')
                    if (cut > 0 && line.startsWith("[")) {
                        val key = line.substring(1, cut).trim()
                        val value = line.substring(cut + 1).trim().removeSurrounding("[", "]")
                        if (value.isNotEmpty() && value != "null") parsed.props[key] = value
                    }
                }
                block == "KERNEL" -> if (parsed.kernel.isEmpty()) parsed.kernel = line
                block == "CPU" -> if (parsed.cpuModel.isEmpty()) {
                    val cut = line.indexOf(':')
                    if (cut > 0) {
                        val key = line.substring(0, cut).trim().lowercase()
                        if (key == "hardware" || key == "model name" || key == "processor") {
                            val value = line.substring(cut + 1).trim()
                            if (value.isNotEmpty() && value != "0") parsed.cpuModel = value
                        }
                    }
                }
                block == "BATTERY" -> {
                    val cut = line.indexOf(':')
                    if (cut > 0) {
                        val key = batteryLabel(line.substring(0, cut).trim())
                        if (key != null) parsed.battery[key] = line.substring(cut + 1).trim()
                    }
                }
            }
        }
        return parsed
    }

    private fun batteryLabel(key: String): String? = when (key) {
        "level" -> "电量"
        "scale" -> "满值"
        "status" -> "状态"
        "health" -> "健康度"
        "present" -> "已安装"
        "voltage" -> "电压"
        "temperature" -> "温度"
        "technology" -> "技术"
        "AC powered" -> "AC 供电"
        "USB powered" -> "USB 供电"
        "Wireless powered" -> "无线供电"
        "Max charging current" -> "最大充电电流"
        "Max charging voltage" -> "最大充电电压"
        "charge counter" -> "电量计数"
        else -> null
    }

    private fun shell(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", "{ $cmd ; echo ; } 2>/dev/null"), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0 && out.isNotEmpty()) out else null
        } catch (t: Throwable) {
            LOGGER.w("device shell failed: ${t.message}")
            null
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
                // A dead binder makes destroy() throw as well; nothing left to clean up then.
            }
        }
    }
}
