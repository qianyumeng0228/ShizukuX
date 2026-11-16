package af.shizuku.manager.settings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import rikka.shizuku.Shizuku
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
    fun activate(
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
            // 部分 ROM 忽略 bypass 开关：资格校验永远不通，继续走只会得到误导性的「权限缺失」结论
            note("x Role 授予被系统拒绝：本机 ROM 未采纳 set-bypassing-role-qualification")
            return ActivationResult(
                Stage.ROLE_GRANT_FAILED, false,
                "系统拒绝了 DEVICE_POLICY_MANAGEMENT Role 授予（cmd role add-role-holder 失败）。\n\n" +
                        "这类 ROM 会校验 RoleHolder 组件（ROLE_HOLDER_PROVISION_* 一组 action）与 " +
                        "LAUNCH_DEVICE_MANAGER_SETUP 权限，而该权限本身只能由这个 Role 授予，" +
                        "两者互为前提形成死锁；cmd role set-bypassing-role-qualification 在这类 ROM 上不生效。\n\n" +
                        "可行的替代：\n" +
                        "① 关闭「手机分身 / 第二用户 / 工作资料」后，改用 ADB 方式设置设备所有者；\n" +
                        "② 在具备 root 的设备上走这条直连激活。",
                log
            )
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
            note("x 未持有 $PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS")
            note("  application 侧判据为 DENIED；shell 侧证据：${shellEvidence?.ifBlank { "(空)" } ?: "(未取到)"}")
            note("  常见原因：AndroidManifest 未声明该权限，或 Role 授予未生效（可稍等数秒重试）")
            return ActivationResult(
                Stage.PERMISSION_MISSING, false,
                "本应用尚未获得设备策略管理角色（DP Role）带来的 MANAGE_PROFILE_AND_DEVICE_OWNERS，" +
                        "无法使用非 ADB 直连路径。",
                log
            )
        }
        note("v 已持有 $PERM_MANAGE_PROFILE_AND_DEVICE_OWNERS（DP Role 生效）")

        // ---------- 第 3 步：临时打开向导闸 ----------
        note("")
        note("> 第 3 步：检查并临时打开向导闸（user_setup_complete）")
        val currentSetup = shell("settings get secure $SETTING_USER_SETUP_COMPLETE 2>/dev/null")
        val setupValue = currentSetup?.output?.trim()
        note("  当前 USER_SETUP_COMPLETE=${setupValue ?: "(未取到)"}")

        var needRestore = false
        if (setupValue == "1") {
            note("  设备已完成开机向导 → 需要临时置 0（副作用：三键导航 Home / 最近任务短暂失灵）")
            val write = shell(
                "settings put secure $SETTING_USER_SETUP_COMPLETE 0; sleep 1; " +
                        "echo $SETUP_READBACK_TAG\$(settings get secure $SETTING_USER_SETUP_COMPLETE 2>/dev/null)"
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
                note("x 无法把 user_setup_complete 置 0（部分 ROM 限制 shell 写入）")
                return ActivationResult(
                    Stage.SETUP_COMPLETED, false,
                    "无法临时关闭开机向导闸（user_setup_complete 置 0 失败）。" +
                            "本流程在这类 ROM 上不可用，请改用其它激活入口。",
                    log
                )
            }
            needRestore = true
        }

        try {
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

            // ---------- 第 6 步：setDeviceOwner ----------
            note("")
            note("> 第 6 步：反射调用 setDeviceOwner(self, …, 0)")
            val ownerCode = invokeSetDeviceOwner(dpm, admin, 0, note)

            val nowOwner = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)
            note("复查 isDeviceOwnerApp($pkg) = $nowOwner")

            return when {
                nowOwner -> ActivationResult(
                    Stage.OK, true,
                    "已成功激活设备所有者（非 ADB 直连路径，未删除账户、未关闭应用分身）。",
                    log
                )

                ownerCode != null -> ActivationResult(
                    Stage.SET_OWNER_REJECTED, false, explainStatusCode(ownerCode), log
                )

                else -> ActivationResult(
                    Stage.SET_OWNER_THREW, false,
                    "setDeviceOwner 调用抛出异常且未能识别原因，激活未完成。\n\n" +
                            "常见原因：\n" +
                            "① 设备已完成开机向导，且临时置 0 未生效；\n" +
                            "② DP Role 权限尚未异步落地（可稍等数秒重试）；\n" +
                            "③ 本应用尚未成为 active admin；\n" +
                            "④ 厂商 ROM 封锁了该 API。",
                    log
                )
            }
        } finally {
            // ---------- 第 7 步：恢复向导状态 ----------
            if (needRestore) {
                note("")
                note("> 第 7 步：恢复 USER_SETUP_COMPLETE=1（shell，finally）")
                val restore = shell("settings put secure $SETTING_USER_SETUP_COMPLETE 1")
                note("  exit=${restore?.exitCode ?: "(失败)"}${if (restore != null && restore.output.isNotBlank()) " out=${restore.output}" else ""}")
                note("  三键导航的 Home / 最近任务键应已恢复")
            }
        }
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
    ): Boolean = try {
        val method = DevicePolicyManager::class.java.getMethod(
            "forceUpdateUserSetupComplete", Integer.TYPE
        )
        method.invoke(dpm, userId)
        note("v forceUpdateUserSetupComplete($userId) 调用成功")
        true
    } catch (t: Throwable) {
        val real = unwrap(t)
        note("  forceUpdateUserSetupComplete 失败：${real?.javaClass?.simpleName}: ${real?.message}")
        Timber.tag(TAG).w(real ?: t, "forceUpdateUserSetupComplete failed")
        false
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
                val method = DevicePolicyManager::class.java.getMethod("setActiveAdmin", *types)
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
     * | Android 14+ | `setDeviceOwner(ComponentName, int userId)` |
     *
     * @return STATUS_OK / 其它错误码；抛异常时返回 null。
     */
    private fun invokeSetDeviceOwner(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        userId: Int,
        note: (String) -> Unit
    ): Int? {
        val twoArgsFirst = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE // API 34
        val order = if (twoArgsFirst) listOf(false, true) else listOf(true, false)
        var lastError: Throwable? = null

        for (useThreeArgs in order) {
            try {
                val method = if (useThreeArgs) {
                    DevicePolicyManager::class.java.getMethod(
                        "setDeviceOwner",
                        ComponentName::class.java,
                        String::class.java,
                        Integer.TYPE
                    )
                } else {
                    DevicePolicyManager::class.java.getMethod(
                        "setDeviceOwner",
                        ComponentName::class.java,
                        Integer.TYPE
                    )
                }
                val result = if (useThreeArgs) {
                    method.invoke(dpm, admin, null, userId)
                } else {
                    method.invoke(dpm, admin, userId)
                }
                val ok = (result as? Boolean) ?: false
                note("  setDeviceOwner 调用成功（${if (useThreeArgs) "三参 / Android 13" else "两参 / Android 14+"}），返回：$ok")
                return if (ok) STATUS_OK else STATUS_REJECTED_GENERIC
            } catch (t: Throwable) {
                lastError = t
                val real = unwrap(t)
                note("  setDeviceOwner(${if (useThreeArgs) "三参" else "两参"}) 失败：${real?.javaClass?.simpleName}: ${real?.message}")
            }
        }

        val real = unwrap(lastError)
        if (real == null) return null

        // Android 14+ 的 provisioning 失败可能是 ServiceSpecificException（hidden API，
        // 不直接引用类型，按类名匹配并反射 getErrorCode()）。
        val realClassName = real.javaClass.name
        if (realClassName.endsWith("ServiceSpecificException")) {
            val code = runCatching {
                real.javaClass.getMethod("getErrorCode").invoke(real) as? Int
            }.getOrNull()
            note("  识别为 ServiceSpecificException（Android 14+ 路径），errorCode=$code")
            note("  系统原文：${real.message}")
            return code?.takeIf { it != 0 } ?: errorCodeFromMessage(real.message) ?: STATUS_REJECTED_GENERIC
        }

        if (real is IllegalStateException || real is IllegalArgumentException) {
            note("  系统原文：${real.message}")
            return errorCodeFromMessage(real.message) ?: STATUS_REJECTED_GENERIC
        }

        Timber.tag(TAG).w(real, "setDeviceOwner threw")
        return null
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
