package af.shizuku.manager.appops

import af.shizuku.manager.utils.Logger.LOGGER
import af.shizuku.manager.utils.ShizukuWait
import rikka.shizuku.Shizuku

/**
 * One op as the platform reports it for a package.
 *
 * [name] is the platform's own op name (`CAMERA`, `RUN_IN_BACKGROUND`, ...), kept as-is because
 * that is what `cmd appops set` takes back; [label] is only what the screen shows.
 */
data class AppOp(
    val name: String,
    val mode: String,
    val time: String?
)

/**
 * Read and write the platform's app-op records.
 *
 * Two properties drive the shape of this object:
 *
 * - The platform is the only record. Nothing is cached here, so a value another tool wrote shows
 *   up on the next read, and every write is read back before it is reported - `cmd appops set`
 *   exits 0 for a package it did not touch, which is how a screen ends up claiming a change that
 *   never happened.
 * - `cmd appops get` prints every op the package has a record for, including ops this screen has
 *   no label for, so the list is parsed generically instead of from a fixed table. A fixed table
 *   is what makes the screen silently drop the ops the ROM added.
 */
object AppOpsManager {

    /** The modes this screen can write. Anything else the platform reports is shown as-is. */
    val MODES = listOf("allow", "ignore", "deny", "default")

    /**
     * Op names with a human label, most consequential first.
     *
     * The order matters twice: it is the order of the detail list, and it decides which names
     * survive [RESTRICTED_SAMPLE] as the cheap "has restrictions" probe. Only names in this table
     * get a label; every other op is still listed under its raw name.
     */
    private val LABELS: Map<String, String> = linkedMapOf(
        "CAMERA" to "相机",
        "RECORD_AUDIO" to "麦克风",
        "FINE_LOCATION" to "精确位置",
        "COARSE_LOCATION" to "大致位置",
        "READ_CONTACTS" to "读取通讯录",
        "WRITE_CONTACTS" to "修改通讯录",
        "READ_CALL_LOG" to "读取通话记录",
        "WRITE_CALL_LOG" to "修改通话记录",
        "READ_SMS" to "读取短信",
        "SEND_SMS" to "发送短信",
        "READ_PHONE_STATE" to "读取设备标识",
        "CALL_PHONE" to "拨打电话",
        "READ_CALENDAR" to "读取日历",
        "WRITE_CALENDAR" to "修改日历",
        "READ_EXTERNAL_STORAGE" to "读取存储",
        "WRITE_EXTERNAL_STORAGE" to "修改存储",
        "READ_MEDIA_IMAGES" to "读取图片",
        "READ_MEDIA_VIDEO" to "读取视频",
        "READ_MEDIA_AUDIO" to "读取音频",
        "BODY_SENSORS" to "身体传感器",
        "ACTIVITY_RECOGNITION" to "运动识别",
        "POST_NOTIFICATION" to "发送通知",
        "READ_CLIPBOARD" to "读取剪贴板",
        "WRITE_CLIPBOARD" to "写入剪贴板",
        "RUN_IN_BACKGROUND" to "后台运行",
        "RUN_ANY_IN_BACKGROUND" to "后台自启",
        "START_FOREGROUND" to "启动前台服务",
        "WAKE_LOCK" to "保持唤醒",
        "VIBRATE" to "震动",
        "SYSTEM_ALERT_WINDOW" to "悬浮窗",
        "ACCESS_NOTIFICATIONS" to "读取通知",
        "PROJECT_MEDIA" to "媒体投屏",
        "PLAY_AUDIO" to "播放音频",
        "TAKE_AUDIO_FOCUS" to "占用音频焦点",
        "BLUETOOTH_CONNECT" to "蓝牙连接",
        "BLUETOOTH_SCAN" to "蓝牙扫描",
        "USE_BIOMETRIC" to "生物识别",
        "REQUEST_INSTALL_PACKAGES" to "安装应用",
        "MANAGE_EXTERNAL_STORAGE" to "所有文件访问",
        "SCHEDULE_EXACT_ALARM" to "精确闹钟",
        "USE_FULL_SCREEN_INTENT" to "全屏通知",
        "SHOW_WHEN_LOCKED" to "锁屏显示",
        "TURN_SCREEN_ON" to "点亮屏幕",
        "GET_USAGE_STATS" to "使用情况访问",
        "MONITOR_LOCATION" to "后台定位",
        "MONITOR_HIGH_POWER_LOCATION" to "高功耗定位"
    )

    /**
     * The ops probed for the "has restrictions" filter.
     *
     * Kept as the documented sample of what "restricted" means; the scan itself reads every op of
     * each package, so an app limited only on an op outside this list is still reported.
     */
    private val RESTRICTED_SAMPLE = listOf(
        "CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION", "READ_CONTACTS",
        "READ_SMS", "READ_CALL_LOG", "READ_CLIPBOARD", "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND", "START_FOREGROUND"
    )

    /**
     * The ops that make a package count as restricted.
     *
     * The whole dump cannot be used: the platform records an `ignore` for every op an app has no
     * permission for, so counting all of them turns almost every installed app into "restricted".
     * These are the consequential ones a user actually blocks in App Ops.
     */
    private val CORE_OPS = setOf(
        "CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION", "READ_CONTACTS",
        "WRITE_CONTACTS", "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_SMS", "SEND_SMS",
        "READ_PHONE_STATE", "CALL_PHONE", "READ_CALENDAR", "WRITE_CALENDAR",
        "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES",
        "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_CLIPBOARD", "RUN_IN_BACKGROUND",
        "RUN_ANY_IN_BACKGROUND", "START_FOREGROUND", "POST_NOTIFICATION",
        "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE",
        "SCHEDULE_EXACT_ALARM", "ACTIVITY_RECOGNITION", "BODY_SENSORS"
    )

    /**
     * The one dump that carries every package's records.
     *
     * `dumpsys appops` prints one `Uid ...:` section holding the uid-wide op defaults followed by
     * a `Package ...:` section per package; only the package rows are needed here, and `sed` lifts
     * them out on the device so the answer is a few hundred lines instead of a five-megabyte dump.
     * The line shape is `      CAMERA (allow):`, while a uid-wide row reads `CAMERA: mode=ignore`
     * and is deliberately left out: [ops] shows those on the detail screen.
     */
    private const val SCAN =
        "dumpsys appops 2>/dev/null | sed -n -e '/^    Package /p' -e '/^      [A-Z][A-Z_0-9]* (/p'"

    private const val UID_PREFIX = "Uid mode: "

    /** False when the Shizuku service is not running: every call below needs it. */
    fun isAvailable(): Boolean = ShizukuWait.ping()

    /** A label for [op], or the bare platform name when this build has none. */
    fun label(op: String): String = LABELS[bareName(op)] ?: bareName(op)

    /**
     * Whether [op] is one this screen knows a label for.
     *
     * A UID-level record is marked with this prefix: the platform prints `Uid mode: CAMERA: ignore`
     * before the per-package list, and that record applies to every package sharing the UID, not
     * only the one being inspected - which is exactly why it is worth showing separately instead
     * of being folded into the package's own CAMERA row.
     */
    fun isUidLevel(op: String): Boolean = op.startsWith(UID_PREFIX)

    /** The op name without the [UID_PREFIX] marker, i.e. what the platform accepts in `set`. */
    fun bareName(op: String): String = op.removePrefix(UID_PREFIX)

    /** Whether [op] is one this screen knows a label for, i.e. belongs in the leading group. */
    fun isKnown(op: String): Boolean = LABELS.containsKey(bareName(op))

    /**
     * Every op the platform holds a record for on [packageName], labelled ones first.
     *
     * The list is read from `dumpsys appops` rather than `cmd appops get`, because `get` prints the
     * UID-wide block and the per-package block as one flat list with the `Uid mode: ` marker only on
     * the first row (see `UID_PREFIX`): the 47 rows after it are indistinguishable from package rows
     * and used to be shown - and written - as package-level records. Here the two blocks are read
     * from their own headers (`  Uid u0aXX:` and `    Package <pkg>:`), so each op carries the level
     * it actually lives at.
     *
     * An empty list means the platform answered with nothing, which is also what it answers for a
     * package that has no records at all; null means it could not be asked.
     */
    fun ops(packageName: String): List<AppOp>? {
        val out = shell("dumpsys appops") ?: return null

        // Ops of the Uid block currently being read; package blocks are nested under their uid, so
        // this snapshot is what the package inherits as "uid-wide" records.
        val uidOps = LinkedHashMap<String, String>()
        var targetUidOps: Map<String, String>? = null
        var targetPkgOps: MutableMap<String, String>? = null
        var inUid = false
        var owner: String? = null

        out.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.isEmpty()) return@forEach
            val trimmed = line.trim()
            val indent = line.length - line.trimStart().length

            if (indent <= 2 && trimmed.startsWith("Uid ") && trimmed.endsWith(":")) {
                inUid = true
                owner = null
                uidOps.clear()
                return@forEach
            }
            if (indent <= 4 && trimmed.startsWith("Package ") && trimmed.endsWith(":")) {
                owner = trimmed.removePrefix("Package ").removeSuffix(":").trim()
                inUid = false
                if (owner == packageName) {
                    targetUidOps = LinkedHashMap(uidOps)
                    targetPkgOps = LinkedHashMap()
                }
                return@forEach
            }
            val isTarget = owner == packageName && targetPkgOps != null
            if (inUid && indent >= 6) {
                // `CAMERA: mode=ignore`
                val colon = trimmed.indexOf(':')
                if (colon <= 0) return@forEach
                val name = trimmed.substring(0, colon).trim()
                val rest = trimmed.substring(colon + 1).trim()
                if (!rest.startsWith("mode=")) return@forEach
                val mode = rest.removePrefix("mode=").trim().substringBefore(' ').trim()
                if (name.isEmpty() || mode.isEmpty() || name.contains(' ')) return@forEach
                uidOps[name] = mode
                return@forEach
            }
            if (isTarget && indent >= 6 && trimmed.endsWith(":")) {
                // `CAMERA (allow):` - the mode sits in parentheses, and MIUIOP names carry their
                // own parentheses (`MIUIOP(10001) (ask):`), so match the last pair.
                val close = trimmed.lastIndexOf(')')
                if (close <= 0) return@forEach
                val open = trimmed.lastIndexOf('(', close - 1)
                if (open <= 0) return@forEach
                val name = trimmed.substring(0, open).trim()
                val mode = trimmed.substring(open + 1, close).trim().substringBefore(' ').trim()
                if (name.isEmpty() || mode.isEmpty()) return@forEach
                targetPkgOps!![name] = mode
            }
        }

        val uidLevel = (targetUidOps ?: emptyMap()).map { (name, mode) ->
            AppOp(name = UID_PREFIX + name, mode = mode, time = null)
        }
        val pkgLevel = (targetPkgOps ?: emptyMap()).map { (name, mode) ->
            AppOp(name = name, mode = mode, time = null)
        }
        // One row per op. The package record wins when both levels hold the same op: it is the
        // record this screen can actually change, and showing the uid row next to it made a
        // successful write look like it had been ignored (the uid row kept its old value).
        val byName = LinkedHashMap<String, AppOp>()
        pkgLevel.forEach { byName[it.name] = it }
        uidLevel.forEach { op -> if (byName[bareName(op.name)] == null) byName[op.name] = op }
        val parsed = byName.values.toList()
        // Labelled ops first, in table order; the rest alphabetically, so a ROM's extra ops are
        // grouped instead of scattered through the list the user reads.
        val known = parsed.filter { isKnown(it.name) }
            .sortedBy { LABELS.keys.indexOf(bareName(it.name)) }
        val rest = parsed.filterNot { isKnown(it.name) }.sortedBy { bareName(it.name) }
        return known + rest
    }

    /**
     * Writes [mode] for one op and answers whether it took.
     *
     * The read-back is the answer: a ROM can accept the verb and leave the record alone.
     */
    fun setOp(packageName: String, op: String, mode: String): Boolean {
        if (mode !in MODES) return false
        val name = bareName(op)
        // Every write lands on the package record, including the rows the device reports as
        // uid-wide. A uid write needs the numeric uid and is dropped on this ROM even when it is
        // addressed correctly (measured: `set --uid 10420 CAMERA deny` leaves
        // `Uid mode: CAMERA: ignore`), while the package record is what changes one app's access -
        // which is what this screen offers. The uid row therefore only explains where the current
        // value comes from; editing it writes the app's own record.
        shell("cmd appops set $packageName $name $mode")
        val after = readMode(packageName, name)
        // A reset drops the record entirely instead of storing `default`, so "no record" is a
        // success for that mode rather than an unreadable one.
        if (mode == "default") return after == null || after == "default"
        return after == mode
    }

    /** Resets every op of [packageName] to the platform default, answering how many were reset. */
    fun resetAll(packageName: String): Boolean? {
        shell("cmd appops reset $packageName")
        return ops(packageName)?.count { it.mode != "default" && it.mode != "allow" } == 0
    }

    /**
     * The packages among [candidates] that have at least one core op denied or ignored.
     *
     * `query-op` is the obvious way to ask this and it is useless on this device: it answers
     * nothing at all, for an op that visibly holds `ignore` records, so a list built from it would
     * report every app as unrestricted. Asking each package with `get` instead costs one process
     * launch per package, which is why the whole state is read once from `dumpsys appops`.
     */
    fun restricted(candidates: Collection<String>): Set<String> {
        if (candidates.isEmpty()) return emptySet()
        val wanted = candidates.toHashSet()
        val out = shell(SCAN) ?: return emptySet()
        val limited = mutableSetOf<String>()
        var current: String? = null
        out.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.startsWith("    Package ")) {
                current = line.removePrefix("    Package ").trim().removeSuffix(":")
                return@forEach
            }
            val owner = current ?: return@forEach
            if (owner !in wanted) return@forEach
            val open = line.indexOf('(')
            val close = line.indexOf(')', open + 1)
            if (open < 0 || close < 0) return@forEach
            val name = line.substring(0, open).trim()
            if (name !in CORE_OPS) return@forEach
            // `(allow / switch COARSE_LOCATION=allow)` carries a modifier: only the first word is
            // the effective mode of the op itself.
            val mode = line.substring(open + 1, close).trim().substringBefore(' ').trim()
            if (mode == "ignore" || mode == "deny") limited.add(owner)
        }
        return limited
    }

    /**
     * The mode [op] holds for [packageName], or null when it has no record.
     *
     * Read back through [ops] instead of `cmd appops get <pkg> <op>`: a targeted `get` answers with
     * whichever record the platform prefers (usually the UID one), so it cannot confirm that a
     * package-level write landed.
     */
    private fun readMode(packageName: String, op: String): String? =
        ops(packageName)?.firstOrNull { it.name == op }?.mode

    /**
     * Runs one shell command as the Shizuku user and answers its stdout, or null when it did not
     * run or did not succeed.
     */
    private fun shell(cmd: String): String? {
        if (!isAvailable()) return null
        var process: Process? = null
        return try {
            process = Shizuku.newProcess(arrayOf("sh", "-c", "{ $cmd ; } 2>/dev/null"), null, null)
            val out = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() == 0) out else null
        } catch (t: Throwable) {
            LOGGER.w("appops shell failed: ${t.message}")
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

/**
 * Mode names as Chinese, because the platform's words are the ones the user has to act on.
 */
fun modeLabel(mode: String): String = when (mode) {
    "allow" -> "允许"
    "ignore" -> "忽略"
    "deny" -> "拒绝"
    "default" -> "默认"
    "foreground" -> "仅前台"
    "ask" -> "询问"
    else -> mode
}
