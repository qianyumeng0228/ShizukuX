package af.shizuku.manager.settings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import timber.log.Timber

/**
 * 非 ADB 直连激活设备所有者（移植自 AxManagerD 的 `DpDoDirectActivation`）。
 *
 * ## 为什么需要它
 *
 * 传统路径是用 shell（Shizuku / root）执行 `dpm set-device-owner`。shell 身份会命中
 * `DevicePolicyManagerService#setDeviceOwner` 的 `if (isAdb)` 分支，依次检查：
 *
 * ```
 * hasUserSetupCompleted()            ← 向导已完成 → 拒
 * nonTestNonPrecreatedUsersExist()   ← 存在第二用户 / 工作资料 / 应用分身 → 拒
 * hasIncompatibleAccountsOrNonAdb    ← 存在账户 → 拒
 * ```
 *
 * 厂商"应用分身"（如 ColorOS 的常驻 MultiApp user 999）、工作资料、访客用户都会让
 * 第二条恒为真，因此不删分身就永远无法用 `dpm` 设 DO。
 *
 * 而用**应用自身 uid** 直接调 binder（`isAdb=false`）时，系统走 else 分支：
 *
 * ```java
 * if (deviceOwnerUserId != UserHandle.USER_SYSTEM) return STATUS_NOT_SYSTEM_USER;
 * if (hasUserSetupCompleted())                   return STATUS_USER_SETUP_COMPLETED;
 * return STATUS_OK;                               // ★ 完全不读用户列表与账户
 * ```
 *
 * 也就是说：**分身和账户在这条路径上根本不是判据**，唯一硬闸是"开机向导已完成"。
 *
 * ## 完整链路（A13→A16 语义一致）
 *
 * ```
 * ① shell : cmd role set-bypassing-role-qualification true
 * ② shell : cmd role add-role-holder --user 0 android.app.role.DEVICE_POLICY_MANAGEMENT <pkg>
 *           → 应用获得 MANAGE_PROFILE_AND_DEVICE_OWNERS（manifest 必须声明，否则恒 DENIED）
 * ③ shell : settings put secure user_setup_complete 0        ← 仅当原本为 1 时
 * ④ 应用自身: 反射 forceUpdateUserSetupComplete(0)           ← 把上面的值同步进 DPM 内存态
 * ⑤ shell : dpm set-active-admin <component>                 ← 应用侧无 MANAGE_DEVICE_ADMINS，必须由 shell 做
 * ⑥ 应用自身: 反射 setDeviceOwner(...) → STATUS_OK
 * ⑦ shell : settings put secure user_setup_complete 1        ← finally 恢复
 * ```
 *
 * 第 ④ 步的参数是 **userId**，不是"完成状态"：它把 `Settings.Secure.USER_SETUP_COMPLETE`
 * 的当前值原样搬进内存。因此漏掉第 ③ 步必然在第 ⑥ 步抛异常（内存态仍是 true）。
 *
 * ## 副作用（必须在 UI 上如实告知）
 *
 * `user_setup_complete=0` 期间系统认为设备未完成向导，会启用防逃离向导：
 * 三键导航的 Home / 最近任务键失效（返回键可用，全屏手势导航不受影响）。
 * 恢复为 1 后立刻正常，所以恢复动作必须放在 finally 里。
 *
 * ## 隐藏 API
 *
 * `forceUpdateUserSetupComplete`（@TestApi）与 `setDeviceOwner`（@SystemApi）都在
 * hiddenapi 名单内。ShizukuApplication 启动时已执行
 * `HiddenApiBypass.setHiddenApiExemptions("")`（SDK≥28 全量豁免），故反射可靠。
 * 本文件所有调用点均为 try/catch 包裹的反射，编译期不引用隐藏成员。
 */
object DpDoDirectActivation {

    private const val TAG = "DpDoDirectActivation"

    /** `DEVICE_POLICY_MANAGEMENT` Role 全名（@SystemApi，公开 SDK 无常量）。 */
    private const val ROLE_DEVICE_POLICY_MANAGEMENT = "android.app.role.DEVICE_POLICY_MANAGEMENT"

    /**
     * 该权限 protectionLevel = `signature|role`：manifest 声明后，Role 授予时会自动置 granted。
     * manifest 漏声明时 `checkSelfPermission` 恒 DENIED（权限表里根本没有这一项）。
     */
    private const val PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS =
        "android.permission.MANAGE_PROFILE_AND_DEVICE_OWNERS"

    /** `Settings.Secure.USER_SETUP_COMPLETE`（@hide，公开 SDK 无此常量，值恒为该字面量）。 */
    private const val SETTING_USER_SETUP_COMPLETE = "user_setup_complete"

    private const val STATUS_OK = 0

    /**
     * 兜底错误码：调用被拒但未能从异常文案反查出具体 `STATUS_*`。
     * 取一个系统未使用的值，避免与真实码混淆。
     */
    private const val STATUS_REJECTED_GENERIC = -2

    private const val SETUP_READBACK_TAG = "SX_SETUP_NOW="

    /**
     * `Shizuku.newProcess` 不保证继承完整 shell 环境，显式给出 PATH / HOME 等，
     * 否则 `cmd`、`settings`、`dumpsys` 可能找不到。
     */
    private val SHELL_ENV = arrayOf(
        "PATH=/system/bin:/system/xbin:/vendor/bin",
        "HOME=/data/local/tmp",
        "ANDROID_ROOT=/system",
        "ANDROID_DATA=/data",
        "EXTERNAL_STORAGE=/sdcard",
        "TMPDIR=/data/local/tmp"
    )

    /** 激活结果细分，供 UI 给出精确提示。 */
    enum class Stage {
        /** Shizuku 服务未运行，拿不到 shell 身份。 */
        SHELL_UNAVAILABLE,

        /**
         * `cmd role add-role-holder` 被系统拒绝：设备所在 ROM 未采纳
         * `set-bypassing-role-qualification`，资格校验未通过。
         */
        ROLE_GRANT_FAILED,

        /** DP Role 授予后 MAPDO 仍未落地。 */
        PERMISSION_MISSING,

        /** 设备已完成开机向导，且临时置 0 失败（无法打开唯一硬闸）。 */
        SETUP_COMPLETED,

        /** 第 ⑤ 步被拒：注册设备管理员失败。 */
        SET_ADMIN_FAILED,

        /** 第 ⑥ 步返回非 STATUS_OK 的 provisioning 错误码。 */
        SET_OWNER_REJECTED,

        /** 第 ⑥ 步抛异常（SecurityException / 隐藏 API 拦截等）。 */
        SET_OWNER_THREW,

        /** 全部成功。 */
        OK,

        /** 本应用已经是设备所有者，无需重复激活。 */
        ALREADY_OWNER
    }

    data class ActivationResult(
        val stage: Stage,
        val success: Boolean,
        val message: String,
        val log: List<String>
    )

    data class ShellResult(
        val exitCode: Int,
        val output: String
    )

    // ---------------------------------------------------------------------
    // shell 通道
    // ---------------------------------------------------------------------

    /**
     * 以 Shizuku 服务的 shell(uid 2000) 身份执行一条命令。
     *
     * @return null 表示进程创建失败（服务未运行 / 调用异常）。
     */
    fun shell(command: String): ShellResult? {
        return try {
            val process = Shizuku.newProcess(
                arrayOf("/system/bin/sh", "-c", command),
                SHELL_ENV,
                "/data/local/tmp"
            )
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            ShellResult(exitCode, (stdout + stderr).trim())
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "shell failed: $command")
            null
        }
    }

    // ---------------------------------------------------------------------
    // 前置判据
    // ---------------------------------------------------------------------

    /**
     * 本应用是否已实际持有 `MANAGE_PROFILE_AND_DEVICE_OWNERS`。
     *
     * 判据 ①：应用侧权限表（manifest 已声明时可靠）；
     * 判据 ②：shell 侧 `dumpsys package` 文本（ROM 权限表刷新延迟时的并列证据）。
     */
    fun hasManageProfileAndDeviceOwners(
        context: Context,
        shellEvidence: String? = null
    ): Boolean {
        return try {
            if (context.checkSelfPermission(PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                return true
            }
            shellEvidence != null &&
                shellEvidence.contains(PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS) &&
                shellEvidence.contains("granted=true")
        } catch (t: Throwable) {
            false
        }
    }

    /** 设备所有者管理组件（与 manifest 注册的 DhizukuAdminReceiver 对应）。 */
    fun adminComponent(context: Context): ComponentName = DeviceOwnerHelper.adminComponent(context)

    /**
     * 本应用当前签名的类别人话，用于激活日志。
     *
     * AOSP 的 platform testkey 证书主题固定带 `android@android.com`，因此按它区分即可：
     * Dhizuku 发布的 `-testkey` 包与 MT 管理器"默认"签名都属于这一支，也正是 OPPO 放行的
     * 那一支（Dhizuku 讨论 #16）。
     */
    private fun signingKind(context: Context): String = runCatching {
        val certs = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo
            ?.apkContentsSigners
            .orEmpty()
        if (certs.isEmpty()) return@runCatching "未知（读不到签名）"
        val factory = java.security.cert.CertificateFactory.getInstance("X.509")
        val testKey = certs.any { cert ->
            // Signature carries DER bytes, not the certificate itself: parse it before reading
            // the subject, which is where the testkey identity lives.
            val subject = runCatching {
                val parsed = factory.generateCertificate(
                    java.io.ByteArrayInputStream(cert.toByteArray())
                ) as java.security.cert.X509Certificate
                parsed.subjectX500Principal.name
            }.getOrDefault("")
            subject.contains("android@android.com") || subject.contains("CN=Android")
        }
        if (testKey) "testkey（AOSP 测试签名，OPPO 放行）" else "自建签名（OPPO 不放行设备所有者）"
    }.getOrDefault("未知（读取签名失败）")

    /**
     * 是否为 OPPO 系 ROM（OPPO / realme / 一加，含 ColorOS）。
     *
     * 三家共用同一套设备所有者签名白名单，判定合并处理；只用于给失败结果补一条可执行的
     * 解释，不改变任何激活行为。
     */
    private fun oppoFamily(): Boolean = runCatching {
        val props = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
        val marks = listOf(
            "ro.build.version.opporom",
            "ro.build.version.oplusrom",
            "ro.product.system.brand",
            "ro.product.brand",
            "ro.vendor.product.brand"
        ).mapNotNull { key ->
            runCatching { props.invoke(null, key) as? String }.getOrNull()
        }
        val all = (marks + Build.MANUFACTURER + Build.BRAND).joinToString(" ").lowercase()
        all.contains("oppo") || all.contains("realme") ||
            all.contains("oneplus") || all.contains("coloros")
    }.getOrDefault(false)

    /**
     * 失败结果里追加的签名说明，OPPO 系且本包非 testkey 时才返回内容。
     *
     * 这一层校验发生在 `DevicePolicyManagerService` 入口，早于账号、分身、向导闸的全部判据，
     * 所以调用链再对也一定被拒；不写清原因，用户只会看到"别人能行我不行"。
     */
    private fun signingHint(context: Context): String {
        if (!oppoFamily()) return ""
        if (signingKind(context).startsWith("testkey")) return ""
        return "\n\n★ 本机是 OPPO 系 ROM，系统只允许白名单应用或 testkey 签名应用激活设备所有者" +
                "（Dhizuku 讨论 #16 原文）。本应用是自建签名，这一层校验在进入激活流程之前就拒绝了，" +
                "与上面列出的任何原因都无关——需要用 testkey 签名重新打包安装后才能通过。"
    }

    // ---------------------------------------------------------------------
    // 主流程
    // ---------------------------------------------------------------------

    /**
     * 以**应用自身身份（非 ADB）**完成 Device Owner 激活。
     *
     * ⚠️ 会阻塞 binder 调用，必须在非主线程调用。
     *
     * @param onLog 可选实时日志回调（UI 展示用）。
     */
    /**
     * 并发闸门。
     *
     * 设置页可以反复进入再点确定，两次 activate() 交错时，后一次会把「已经被替换成
     * ShizukuBinderWrapper 的 proxy」当成 original 记下来，还原时写回的还是 proxy——
     * 进程内 mService 就永久停在 shell 身份上，后续所有 DPM 调用都会以 shell 执行。
     * 这种情况静默发生，只会表现为「某些功能突然行为异常」，所以必须在入口处挡掉。
     */
    private val running = AtomicBoolean(false)

    fun activate(
        context: Context,
        onLog: ((String) -> Unit)? = null
    ): ActivationResult {
        if (!running.compareAndSet(false, true)) {
            return ActivationResult(
                Stage.SET_OWNER_THREW, false,
                "另一次激活流程还在运行，本次没有执行。请等它结束后再看结果。",
                listOf("activate() 并发调用，已直接返回")
            )
        }
        try {
            return activateInternal(context, onLog)
        } finally {
            running.set(false)
        }
    }

    private fun activateInternal(
        context: Context,
        onLog: ((String) -> Unit)? = null
    ): ActivationResult {
        val log = mutableListOf<String>()
        val note: (String) -> Unit = { line ->
            log += line
            Timber.tag(TAG).i(line)
            onLog?.invoke(line)
        }

        val pkg = context.packageName
        val admin = adminComponent(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return ActivationResult(
                Stage.SET_OWNER_THREW, false,
                "无法获取 DevicePolicyManager（系统异常）", log
            )

        note("包名：$pkg")
        note("管理组件：${admin.flattenToShortString()}")
        note("调用身份：应用自身 uid=${Process.myUid()}（isAdb=false → 非 ADB 分支）")
        note("SDK=${Build.VERSION.SDK_INT}（setDeviceOwner 签名按此选择）")

        // 应用自身身份路径是否可用。Role / MAPDO 任一失败只关掉这条回退路径，
        // 不再终止整个流程——shell 身份路径与它们无关，上游 Dhizuku 正是这么做的。
        var selfPathAllowed = true
        // The signing identity is part of the answer on OPPO: the platform only lets
        // whitelisted or testkey-signed packages become owner (Dhizuku discussion #16), so a
        // fully correct call path still ends in a refusal on a self-signed build. Recording it
        // up front turns "it just does not work" into a reason.
        note("签名：${signingKind(context)}")
        note("ROM：${Build.MANUFACTURER} / ${Build.BRAND}${if (oppoFamily()) "（OPPO 系，设备所有者受签名白名单限制）" else ""}")

        if (runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)) {
            note("v 本应用已是设备所有者，无需重复激活")
            return ActivationResult(Stage.ALREADY_OWNER, true, "本应用已是设备所有者。", log)
        }

        if (!Shizuku.pingBinder()) {
            note("x ShizukuX 服务未运行，拿不到 shell 身份")
            return ActivationResult(
                Stage.SHELL_UNAVAILABLE, false,
                "ShizukuX 服务未运行：本流程需要 shell 身份来授予 DP Role 与临时打开向导闸。" +
                        "请先启动 ShizukuX 再重试。",
                log
            )
        }

        // ---------- 第 1 步：开启 Role 资格校验绕过 ----------
        note("")
        note("> 第 1 步：开启 Role 资格校验绕过（shell）")
        val bypass = shell("cmd role set-bypassing-role-qualification true")
        if (bypass == null) {
            return ActivationResult(Stage.SHELL_UNAVAILABLE, false, "无法执行 shell 命令。", log)
        }
        note("  exit=${bypass.exitCode}${if (bypass.output.isNotBlank()) " out=${bypass.output}" else ""}")
        if (bypass.exitCode != 0) {
            note("  ! 该步失败通常会连带第 2 步失败；继续尝试")
        }

        // ---------- 第 2 步：授予 DEVICE_POLICY_MANAGEMENT Role ----------
        note("")
        note("> 第 2 步：授予 DEVICE_POLICY_MANAGEMENT Role（shell）")
        val grant = shell(
            "cmd role add-role-holder --user 0 $ROLE_DEVICE_POLICY_MANAGEMENT $pkg"
        )
        if (grant == null) {
            return ActivationResult(Stage.SHELL_UNAVAILABLE, false, "无法执行 shell 命令。", log)
        }
        note("  exit=${grant.exitCode}${if (grant.output.isNotBlank()) " out=${grant.output}" else ""}")
        if (grant.exitCode != 0) {
            // 与上游 Dhizuku 对照后的修正：它的 activateAsDeviceOwnerByShizuku 全程在
            // requireBinderWrapperDevicePolicyManager 里以 shell 身份跑 setActiveAdmin +
            // setDeviceOwner，从不申请 DEVICE_POLICY_MANAGEMENT Role。所以 Role 失败只意味着
            // “应用自身身份这条路走不通”，不该让整个流程在这里终止。
            note("x Role 授予被系统拒绝：应用自身身份路径不可用（shell 身份路径不依赖它，继续）")
            selfPathAllowed = false
        }

        // 记录当前 Role 持有者，便于失败排查
        val holders = shell(
            "cmd role get-role-holders --user 0 $ROLE_DEVICE_POLICY_MANAGEMENT 2>/dev/null"
        )
        if (holders != null && holders.output.isNotBlank()) {
            note("  role holders: ${holders.output}")
        }

        // ---------- 校验 MAPDO 是否真正落地 ----------
        val dumpsys = shell(
            "dumpsys package $pkg 2>/dev/null | grep -A 2 '$PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS'"
        )
        val shellEvidence = dumpsys?.output
        if (!hasManageProfileAndDeviceOwners(context, shellEvidence)) {
            note("x 未持有 $PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS（应用自身身份路径不可用）")
            note("  application 侧判据为 DENIED；shell 侧证据：${shellEvidence?.ifBlank { "(空)" } ?: "(未取到)"}")
            note("  shell 身份路径不受影响，继续")
            selfPathAllowed = false
        } else {
            note("v 已持有 $PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS（DP Role 生效）")
        }

        // ---------- 第 3 步：临时打开向导闸 ----------
        note("")
        note("> 第 3 步：检查并临时打开向导闸（user_setup_complete）")
        val currentSetup = shell("settings --user 0 get secure $SETTING_USER_SETUP_COMPLETE 2>/dev/null")
        val setupValue = currentSetup?.output?.trim()
        note("  当前 USER_SETUP_COMPLETE=${setupValue ?: "(未取到)"}")

        var needRestore = false
        if (setupValue == "1") {
            note("  设备已完成开机向导 → 需要临时置 0（副作用：三键导航 Home / 最近任务短暂失灵）")
            val write = shell(
                "settings --user 0 put secure $SETTING_USER_SETUP_COMPLETE 0; sleep 1; " +
                        "echo $SETUP_READBACK_TAG\$(settings --user 0 get secure $SETTING_USER_SETUP_COMPLETE 2>/dev/null)"
            )
            if (write == null) {
                return ActivationResult(Stage.SHELL_UNAVAILABLE, false, "无法执行 shell 命令。", log)
            }
            val readBack = write.output.lineSequence()
                .firstOrNull { it.startsWith(SETUP_READBACK_TAG) }
                ?.substringAfter(SETUP_READBACK_TAG)
                ?.trim()
            note("  回读：USER_SETUP_COMPLETE=${readBack ?: "(无标记)"}")
            if (readBack != "0") {
                // 上游 Dhizuku 的 bypassUserSetupComplete 在 put 失败时只是「不 bypass」并继续，
                // 不中止流程；这里对齐它——最终是否放行由 setDeviceOwner 的返回决定。
                note("  ! 没能把 user_setup_complete 置 0；继续尝试（以 setDeviceOwner 的结果为准）")
            }
            needRestore = readBack == "0"
        }

        try {
            // ---------- 第 3.5 步（首选）：以 shell 身份完整激活（对齐上游 Dhizuku） ----------
            //
            // Dhizuku 的 activateAsDeviceOwnerByShizuku 是：
            //   delay(1500)                      // 等账户缓存刷新
            //   bypassUserSetupComplete(userId)  // settings put 0 + forceUpdate
            //   wrapper { setActiveAdmin(who, true, userId); setDeviceOwner(who, null, userId) }
            //   restore
            // 全程 shell 身份，不申请 Role。既然形状一致，就把这条路径提到首选，
            // 应用自身身份只作回退——否则 Role 一旦被严格 ROM 拒绝，流程在第 2 步就断了。
            note("")
            note("> 第 3.5 步（首选）：以 shell 身份完整激活（对齐 Dhizuku 路径）")
            note("  等待 1.5s 让账户缓存刷新（Dhizuku 在同一位置 delay(1500)）")
            runCatching { Thread.sleep(1500) }
            val shellFirst = activateAsShellIdentity(dpm, admin, 0, note)
            if (shellFirst.attempt?.code == STATUS_OK) {
                val nowOwner = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)
                note("v shell 身份激活成功，isDeviceOwnerApp=$nowOwner")
                return ActivationResult(
                    Stage.OK, true,
                    if (nowOwner) "已激活设备所有者（shell 身份路径，对齐 Dhizuku）。"
                    else "系统接受了 setDeviceOwner，但复核未通过，请重启后确认。",
                    log
                )
            }
            note("  ! shell 身份未通过：${explainAttempt(shellFirst.attempt)}")
            if (!selfPathAllowed) {
                note("  应用自身身份路径也不可用（Role / MAPDO 未落地），到此为止")
                return ActivationResult(
                    Stage.SET_OWNER_REJECTED, false,
                    "shell 身份被拒：${explainAttempt(shellFirst.attempt)}\n\n" +
                            "本机同时拒绝了 DEVICE_POLICY_MANAGEMENT Role，应用自身身份路径也不可用，" +
                            "没有其它可试的路径。",
                    log
                )
            }
            note("  回退到应用自身身份路径重试")

            // ---------- 第 4 步：把 Settings 值同步进 DPM 内存态 ----------
            note("")
            note("> 第 4 步：反射调用 forceUpdateUserSetupComplete(0)（同步内存态）")
            val setupSynced = invokeForceUpdateUserSetupComplete(dpm, 0, note)
            if (!setupSynced) {
                // 不中断：部分 ROM 裁剪了该 API，最终以 setDeviceOwner 的返回为准
                note("  ! 该调用失败（不中断：最终是否放行以 setDeviceOwner 的结果为准）")
            }

            // ---------- 第 5 步：shell 注册 active admin ----------
            // setActiveAdmin 需要 MANAGE_DEVICE_ADMINS（signature，应用自身拿不到），
            // 因此这一步必须由 shell 的 dpm 完成，应用侧反射只作兜底。
            note("")
            note("> 第 5 步：shell 注册设备管理员（dpm set-active-admin）")
            val setAdmin = shell("dpm set-active-admin ${admin.flattenToShortString()}")
            if (setAdmin == null) {
                return ActivationResult(Stage.SHELL_UNAVAILABLE, false, "无法执行 shell 命令。", log)
            }
            note("  exit=${setAdmin.exitCode}${if (setAdmin.output.isNotBlank()) " out=${setAdmin.output}" else ""}")
            val adminActive = runCatching { dpm.isAdminActive(admin) }.getOrDefault(false)
            if (!adminActive) {
                note("  shell 方式未生效，退回应用侧反射 setActiveAdmin")
                if (!invokeSetActiveAdmin(dpm, admin, note)) {
                    return ActivationResult(
                        Stage.SET_ADMIN_FAILED, false,
                        "无法将本应用注册为设备管理员（shell dpm 与应用侧反射均失败）。" +
                                "可能原因：管理组件未在 manifest 正确注册，或厂商 ROM 封锁了该调用。",
                        log
                    )
                }
            }
            note("v 已成为设备管理员")

            // ---------- 第 6 步：setDeviceOwner（应用自身身份） ----------
            note("")
            note("> 第 6 步：反射调用 setDeviceOwner(self, …, 0)")
            val selfAttempt = invokeSetDeviceOwner(dpm, admin, 0, note)

            var nowOwner = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)
            note("复查 isDeviceOwnerApp($pkg) = $nowOwner")
            if (nowOwner) {
                return ActivationResult(
                    Stage.OK, true,
                    "已成功激活设备所有者（非 ADB 直连路径，未删除账户、未关闭应用分身）。",
                    log
                )
            }

            // ---------- 第 6b 步：改用 shell 身份重试 ----------
            // Dhizuku 的做法是把这个 dpm 实例的私有字段 mService 换成 ShizukuBinderWrapper，
            // 之后照常调用 dpm 的方法，Binder 调用者就变成 shell(2000)。两者的差别正是
            // DPMS 里的 `isAdb(caller)`：shell 走 if 分支（查账户 / 分身 / 向导闸），
            // 应用自身走 else 分支（只查向导闸，但要求 MANAGE_PROFILE_AND_DEVICE_OWNERS）。
            // 厂商 ROM 可能只放行其中一条，所以两条都试，日志分别留证。
            note("")
            note("> 第 6b 步：换 shell(2000) 身份重试 setDeviceOwner（Dhizuku 同款 mService 替换）")
            note("  与第 6 步的唯一差别是 Binder 调用者身份；此分支会受账户 / 分身 / 向导闸约束")
            val userList = shell("cmd user list 2>/dev/null")?.output
                ?.lineSequence()?.filter { it.isNotBlank() }?.joinToString(" ; ")
                ?: "(未取到)"
            note("  cmd user list：$userList")

            val shellOutcome = activateAsShellIdentity(dpm, admin, 0, note)
            nowOwner = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)
            note("复查 isDeviceOwnerApp($pkg) = $nowOwner")
            if (nowOwner) {
                return ActivationResult(
                    Stage.OK, true,
                    "已成功激活设备所有者（以 shell 身份完成，未删除账户、未关闭应用分身）。",
                    log
                )
            }

            // 两条路径都被拒：分别给出判决，避免只看到一个笼统的「被拒绝」。
            val primary = if (selfAttempt.code == null && selfAttempt.systemMessage == null) {
                "调用抛出异常且没能读出原因（见上方日志的系统原文）"
            } else {
                explainAttempt(selfAttempt)
            }
            val secondary = shellOutcome.error ?: explainAttempt(shellOutcome.attempt)
            val bothMessage = buildString {
                append("两条身份路径都被系统拒绝，激活未完成。\n\n")
                append("① 应用自身身份（isAdb=false）：").append(primary).append("\n\n")
                append("② shell 身份（isAdb=true）：").append(secondary)
                append(signingHint(context))
            }

            val rejectedBySystem = selfAttempt.code != null || selfAttempt.systemMessage != null
            return ActivationResult(
                if (rejectedBySystem) Stage.SET_OWNER_REJECTED else Stage.SET_OWNER_THREW,
                false, bothMessage, log
            )
        } finally {
            // ---------- 第 7 步：恢复向导状态 ----------
            // 这一步失败会留下「设备未完成向导」的状态：三键导航的 Home / 最近任务失灵。
            // 所以必须回读确认，失败再试一次，并把人工恢复方式写进日志——否则用户只看到
            // 「激活失败」和一台按键失灵的机器，不知道该做什么。
            if (needRestore) {
                note("")
                note("> 第 7 步：恢复 USER_SETUP_COMPLETE=1（shell，finally）")
                var restored = false
                repeat(2) { index ->
                    if (!restored) {
                        val put = shell("settings put secure $SETTING_USER_SETUP_COMPLETE 1")
                        val now = shell("settings get secure $SETTING_USER_SETUP_COMPLETE 2>/dev/null")
                            ?.output?.trim()
                        note("  第 ${index + 1} 次恢复：exit=${put?.exitCode ?: "(失败)"} 回读=${now ?: "(未取到)"}")
                        restored = now == "1"
                    }
                }
                if (restored) {
                    note("  三键导航的 Home / 最近任务键应已恢复")
                } else {
                    note("x 恢复失败：设备会停留在「未完成向导」状态，Home / 最近任务键失灵。")
                    note("  手动恢复（任选其一）：")
                    note("    adb shell settings put secure user_setup_complete 1")
                    note("    在终端里执行 settings put secure user_setup_complete 1")
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // shell 身份通道（Dhizuku 的 mService 替换法）
    // ---------------------------------------------------------------------

    /** shell 身份路径的判决：`attempt` 是 DPMS 的回应，`error` 是通道或授权异常摘要。 */
    private data class ShellOwnerOutcome(
        val attempt: OwnerAttempt?,
        val error: String?
    )

    /**
     * 把本进程 `DevicePolicyManager` 实例的私有字段 `mService` 临时替换为
     * `ShizukuBinderWrapper(原 binder)`，让后续 `dpm.xxx()` 的 Binder 调用以 shell(2000)
     * 身份抵达 DPMS，结束后无条件还原。
     *
     * 与 Dhizuku 的 `requireBinderWrapperDevicePolicyManager` 同一手法：不改接口面，只换底层
     * Binder，因此 `DevicePolicyManager` 的 Java 层封装（含 A14+ 的两参 `setDeviceOwner`）
     * 全部照旧可用。
     *
     * @return null 表示通道不可用（服务未运行 / ROM 裁剪了 mService 字段 / 包装失败）。
     */
    private fun <T> withShellService(
        dpm: DevicePolicyManager,
        note: (String) -> Unit,
        onCallError: ((String) -> Unit)? = null,
        block: () -> T
    ): T? {
        if (!Shizuku.pingBinder()) {
            note("  ! ShizukuX 服务未运行，无法切换到 shell 身份")
            return null
        }
        val field = runCatching {
            DevicePolicyManager::class.java.getDeclaredField("mService").apply { isAccessible = true }
        }.getOrNull()
        if (field == null) {
            note("  ! 反射 DevicePolicyManager#mService 失败（该 ROM 裁剪了此字段）")
            return null
        }
        val original = runCatching { field.get(dpm) as? IInterface }.getOrNull()
        val originalBinder = original?.asBinder()
        if (originalBinder == null) {
            note("  ! mService 不是可用的 IInterface，无法包装")
            return null
        }
        val asInterface = runCatching {
            Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
        }.getOrNull()
        if (asInterface == null) {
            note("  ! 找不到 IDevicePolicyManager\$Stub.asInterface")
            return null
        }
        val wrapped = runCatching {
            asInterface.invoke(null, ShizukuBinderWrapper(originalBinder))
        }.getOrNull()
        if (wrapped == null) {
            note("  ! ShizukuBinderWrapper 包装失败")
            return null
        }
        return try {
            field.set(dpm, wrapped)
            note("  v mService 已替换为 ShizukuBinderWrapper（Binder 调用者 = shell）")
            block()
        } catch (t: Throwable) {
            val real = unwrap(t)
            val desc = "${real?.javaClass?.simpleName}: ${real?.message}"
            note("  x shell 身份调用抛出：$desc")
            // 与「通道不可用」区分开：这里 Binder 已经换成 shell 了，失败是系统拒绝，
            // 不是替换失败，混在一起会得出错误结论。
            onCallError?.invoke(desc)
            null
        } finally {
            val restored = runCatching { field.set(dpm, original) }.isSuccess
            if (restored) {
                note("  · mService 已还原为进程原始 binder")
            } else {
                // 还原失败会让本进程后续所有 DPM 调用继续以 shell 执行，必须说清楚。
                note("  x mService 还原失败：本进程后续 DPM 调用可能仍以 shell 身份执行，重启应用即可恢复")
            }
        }
    }

    /**
     * 以 shell 身份完整走一遍 forceUpdate + setActiveAdmin + setDeviceOwner。
     *
     * 各步复用应用侧的反射封装——`mService` 已被替换，所以这些调用此刻就是 shell 身份。
     */
    private fun activateAsShellIdentity(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        userId: Int,
        note: (String) -> Unit
    ): ShellOwnerOutcome {
        var attempt: OwnerAttempt? = null
        var callError: String? = null
        val switched = withShellService(dpm, note, onCallError = { callError = it }) {
            note("  · 同步向导闸内存态（shell 身份）")
            invokeForceUpdateUserSetupComplete(dpm, userId, note)
            note("  · 注册设备管理员（shell 身份，refreshing=true）")
            invokeSetActiveAdmin(dpm, admin, note)
            note("  · 以 shell 身份调用 setDeviceOwner")
            attempt = invokeSetDeviceOwner(dpm, admin, userId, note)
            true
        } ?: false
        val error = when {
            callError != null -> "shell 身份调用被系统拒绝 —— $callError"
            !switched -> "shell 身份通道不可用（服务未运行或 mService 不可替换）"
            else -> null
        }
        note("  shell 身份路径结果：code=${attempt?.code ?: "(无)"} error=${error ?: "(无)"}")
        return ShellOwnerOutcome(attempt, error)
    }

    // ---------------------------------------------------------------------
    // 反射封装（全部 try/catch，绝不向外抛出）
    // ---------------------------------------------------------------------

    /**
     * 反射 `DevicePolicyManager#forceUpdateUserSetupComplete(int)`（@TestApi）。
     *
     * 系统实现（A13→A16 一致）：
     * ```java
     * boolean isUserCompleted = settingsSecureGetIntForUser(USER_SETUP_COMPLETE, 0, userId) != 0;
     * policy.mUserSetupComplete = isUserCompleted;   // ← 双向赋值
     * ```
     * 参数是 userId，不是"完成状态"。
     */
    private fun invokeForceUpdateUserSetupComplete(
        dpm: DevicePolicyManager,
        userId: Int,
        note: (String) -> Unit
    ): Boolean {
        try {
            val method = DevicePolicyManager::class.java.getDeclaredMethod(
                "forceUpdateUserSetupComplete", Integer.TYPE
            ).apply { isAccessible = true }
            method.invoke(dpm, userId)
            note("v forceUpdateUserSetupComplete($userId) 调用成功")
            return true
        } catch (t: Throwable) {
            val real = unwrap(t)
            note("  单参 forceUpdateUserSetupComplete 失败：${real?.javaClass?.simpleName}: ${real?.message}")
        }
        // 部分分支只保留无参版本（Dhizuku 也做了同样的回退），再试一次。
        return try {
            val noArg = DevicePolicyManager::class.java.getDeclaredMethod("forceUpdateUserSetupComplete")
                .apply { isAccessible = true }
            noArg.invoke(dpm)
            note("v forceUpdateUserSetupComplete()（无参版本）调用成功")
            true
        } catch (t2: Throwable) {
            val real2 = unwrap(t2)
            note("  无参 forceUpdateUserSetupComplete 也失败：${real2?.javaClass?.simpleName}: ${real2?.message}")
            Timber.tag(TAG).w(real2 ?: t2, "forceUpdateUserSetupComplete failed")
            false
        }
    }

    /**
     * 反射 `DevicePolicyManager#setActiveAdmin(ComponentName, boolean, int)`。
     *
     * 优先三参（A13+ 均存在）；个别 ROM 只暴露两参，失败后回退。
     */
    private fun invokeSetActiveAdmin(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        note: (String) -> Unit
    ): Boolean {
        val attempts: List<Array<Any?>> = listOf(
            arrayOf(admin, true, 0),
            arrayOf(admin, true)
        )
        for (args in attempts) {
            try {
                val types = args.map {
                    when (it) {
                        is Boolean -> java.lang.Boolean.TYPE
                        is Int -> java.lang.Integer.TYPE
                        else -> it!!.javaClass
                    }
                }.toTypedArray()
                val method = DevicePolicyManager::class.java
                    .getDeclaredMethod("setActiveAdmin", *types)
                    .apply { isAccessible = true }
                method.invoke(dpm, *args)
                note("v setActiveAdmin(${args.size} 参) 调用成功")
                return true
            } catch (t: Throwable) {
                val real = unwrap(t)
                note("  setActiveAdmin(${args.size} 参) 失败：${real?.javaClass?.simpleName}: ${real?.message}")
            }
        }
        return false
    }

    /**
     * 反射 `DevicePolicyManager#setDeviceOwner(...)`（@TestApi / @SystemApi）。
     *
     * 签名在 Android 14 发生断裂：
     * | 版本 | 签名 |
     * | Android 13 | `setDeviceOwner(ComponentName, String ownerName, int userId)` |
     * | Android 14+ | `setDeviceOwner(ComponentName, int userId)`（内部转 `mService.setDeviceOwner(who, userId, true)`） |
     *
     * 返回值里带回系统原文：AOSP 的 `STATUS_*` 数值不在公开 SDK 中，按文案反查出来的数字
     * 在不同分支上未必对得上，所以有原文时一律以原文为准解释。
     */
    private fun invokeSetDeviceOwner(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        userId: Int,
        note: (String) -> Unit
    ): OwnerAttempt {
        val twoArgsFirst = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE // API 34
        val order = if (twoArgsFirst) listOf(false, true) else listOf(true, false)

        for (useThreeArgs in order) {
            try {
                val method = if (useThreeArgs) {
                    DevicePolicyManager::class.java.getDeclaredMethod(
                        "setDeviceOwner",
                        ComponentName::class.java,
                        String::class.java,
                        Integer.TYPE
                    ).apply { isAccessible = true }
                } else {
                    DevicePolicyManager::class.java.getDeclaredMethod(
                        "setDeviceOwner",
                        ComponentName::class.java,
                        Integer.TYPE
                    ).apply { isAccessible = true }
                }
                val result = if (useThreeArgs) {
                    method.invoke(dpm, admin, null, userId)
                } else {
                    method.invoke(dpm, admin, userId)
                }
                val ok = (result as? Boolean) ?: false
                note("  setDeviceOwner 调用成功（${if (useThreeArgs) "三参 / Android 13" else "两参 / Android 14+"}），返回：$ok")
                return if (ok) OwnerAttempt(STATUS_OK, null) else OwnerAttempt(STATUS_REJECTED_GENERIC, null)
            } catch (t: Throwable) {
                val real = unwrap(t)
                note("  setDeviceOwner(${if (useThreeArgs) "三参" else "两参"}) 抛出：${real?.javaClass?.simpleName}: ${real?.message}")
                if (real == null) return OwnerAttempt(null, null)
                if (real is NoSuchMethodException) {
                    // 当前版本没有这个重载，继续试另一种签名。
                    continue
                }
                // 方法存在、但系统拒绝：这就是真实判决，不能再回退去试另一种重载。
                // 否则 A16 上三参已不存在，后一次尝试的 NoSuchMethodException 会盖掉
                // 第一次的真实原因（比如 SecurityException / 账户非空），日志就骗人了。
                val message = real.message
                note("  系统原文：${message ?: "(无)"}")
                if (real.javaClass.name.endsWith("ServiceSpecificException")) {
                    val code = runCatching {
                        real.javaClass.getMethod("getErrorCode").invoke(real) as? Int
                    }.getOrNull()
                    note("  识别为 ServiceSpecificException（Android 14+ 路径），errorCode=$code")
                    return OwnerAttempt(code, message)
                }
                // 任何异常都保留原文：SecurityException（缺 MANAGE_PROFILE_AND_DEVICE_OWNERS）
                // 与厂商自定义异常的真实理由只在这一行里能看见。
                Timber.tag(TAG).w(real, "setDeviceOwner threw")
                return OwnerAttempt(errorCodeFromMessage(message), message)
            }
        }

        // 两种签名都不存在（极不寻常）：没有可用的判决信息。
        return OwnerAttempt(null, null)
    }

    /** 剥掉 `InvocationTargetException` 包装，取出真实异常。 */
    private fun unwrap(t: Throwable?): Throwable? {
        var cur = t
        var guard = 0
        while (cur is java.lang.reflect.InvocationTargetException && cur.cause != null && guard < 8) {
            cur = cur.cause
            guard++
        }
        return cur
    }

    /**
     * 把 DPM 抛出的文案反查成 `STATUS_*` 数值。
     * 依据 `computeProvisioningErrorString` 原文（A13→A16 一致），用文案匹配避免引用隐藏常量。
     */
    private fun errorCodeFromMessage(msg: String?): Int? {
        val m = msg ?: return null
        return when {
            m.contains("device owner is already set", true) -> 5      // STATUS_HAS_DEVICE_OWNER
            m.contains("already has a profile owner", true) -> 6      // STATUS_USER_HAS_PROFILE_OWNER
            m.contains("not running", true) -> 2                      // STATUS_USER_NOT_RUNNING
            m.contains("is not system user", true) -> 4               // STATUS_NOT_SYSTEM_USER
            m.contains("already set-up", true) -> 1                   // STATUS_USER_SETUP_COMPLETED
            m.contains("already several users", true) -> 2            // STATUS_NONSYSTEM_USER_EXISTS
            m.contains("already some accounts", true) -> 3            // STATUS_ACCOUNTS_NOT_EMPTY
            m.contains("already paired", true) -> 7                   // STATUS_HAS_PAIRED
            else -> null
        }
    }

    /** 一次 `setDeviceOwner` 尝试的判决：`code` 是反查出来的 `STATUS_*`，`systemMessage` 是系统原文。 */
    private data class OwnerAttempt(
        val code: Int?,
        val systemMessage: String?
    )

    /**
     * 把一次尝试翻译成人话。
     *
     * 有系统原文时按原文判读：`STATUS_*` 的真实数值不在公开 SDK 里，`errorCodeFromMessage`
     * 的映射一旦与某分支的实际常量对不上，纯按数字解释就会张冠李戴（把账户问题说成分身问题）。
     */
    private fun explainAttempt(attempt: OwnerAttempt?): String {
        if (attempt == null) return "调用抛出异常且没能读出原因（见日志「系统原文」一行）"
        val msg = attempt.systemMessage?.trim()
        if (!msg.isNullOrEmpty()) {
            return when {
                msg.contains("already set-up", true) ->
                    "设备已完成开机向导，且临时置 0 没生效（系统原文：$msg）"

                msg.contains("already some accounts", true) ->
                    "设备上存在账户（系统原文：$msg）"

                msg.contains("already several users", true) ->
                    "存在第二用户 / 工作资料 / 应用分身（系统原文：$msg）"

                msg.contains("already paired", true) ->
                    "设备已与其它设备配对（系统原文：$msg）"

                msg.contains("device owner is already set", true) ->
                    "设备已有设备所有者（系统原文：$msg）"

                msg.contains("already has a profile owner", true) ->
                    "当前用户已有资料所有者（系统原文：$msg）"

                msg.contains("is not system user", true) ->
                    "目标用户不是机主（系统原文：$msg）"

                msg.contains("not running", true) ->
                    "目标用户未运行（系统原文：$msg）"

                msg.contains("unsupported DPC", true) ->
                    "该 DPC 不支持无头设备所有者模式（系统原文：$msg）"

                else -> "系统拒绝（系统原文：$msg）"
            }
        }
        if (attempt.code == null) return "调用抛出异常且没能读出原因（见日志「系统原文」一行）"
        return explainStatusCode(attempt.code)
    }

    /** 把 provisioning 错误码翻译成可读原因。 */
    private fun explainStatusCode(code: Int): String = when (code) {
        1 -> "设备已完成开机向导（STATUS_USER_SETUP_COMPLETED）：非 ADB 分支的唯一硬闸未打开。" +
                "本流程已尝试临时置 0，若仍失败说明该 ROM 拒绝了 shell 写入。"

        2 -> "设备上存在第二用户 / 工作资料 / 应用分身（STATUS_NONSYSTEM_USER_EXISTS）。" +
                "非 ADB 路径理论上不检查用户列表；出现该错误说明该 ROM 改写了系统逻辑。"

        3 -> "设备上存在账户（STATUS_ACCOUNTS_NOT_EMPTY）。" +
                "非 ADB 路径理论上不检查账户；出现该错误说明该 ROM 改写了系统逻辑。"

        4 -> "目标用户不是机主（STATUS_NOT_SYSTEM_USER）。设备所有者只能设在 User 0。"

        5 -> "设备已存在设备所有者（STATUS_DEVICE_OWNER_ALREADY_SET）。" +
                "请先在原所有者应用中清除，或换用转移所有权入口。"

        6 -> "当前用户已被设为资料所有者（STATUS_USER_HAS_PROFILE_OWNER）。"

        7 -> "设备已与其它设备配对完成（STATUS_HAS_PAIRED）。"

        8 -> "设备已经设置过（STATUS_UNKNOWN）。"

        STATUS_REJECTED_GENERIC ->
            "系统拒绝了设备所有者设置（未能从系统返回中解析出具体原因）。请查看日志中的「系统原文」一行。"

        else -> "系统拒绝了设备所有者设置（错误码 $code）。"
    }
}
