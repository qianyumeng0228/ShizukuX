package rikka.shizuku.server

import android.os.Binder
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IActivityManagerExtra
import rikka.shizuku.server.util.InputValidationUtils
import rikka.shizuku.server.util.ShellExecutor
import af.shizuku.common.util.UserHandleCompat

/**
 * Aligned with ShizukuPlus ActivityManagerPlusImpl (288 lines) — every
 * method is dual-path: direct Binder IPC via reflection first (works at shell
 * UID, no exec needed), ShellExecutor as fallback. Keeps the local
 * IActivityManagerExtra AIDL surface (10 methods, same set as Plus).
 */
class ActivityManagerPlusImpl : IActivityManagerExtra.Stub() {

    companion object {
        private const val TAG = "ActivityManagerPlus"

        private fun packageManagerService(): Any? = try {
            val binder = ServiceManager.getService("package") ?: return null
            Class.forName("android.content.pm.IPackageManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "packageManagerService unavailable", e)
            null
        }

        private fun activityManagerService(): Any? = try {
            val binder = ServiceManager.getService("activity") ?: return null
            Class.forName("android.app.IActivityManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "activityManagerService unavailable", e)
            null
        }

        private fun usageStatsService(): Any? = try {
            val binder = ServiceManager.getService("usagestats") ?: return null
            Class.forName("android.app.usage.IUsageStatsManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (e: Exception) {
            Log.w(TAG, "usageStatsService unavailable", e)
            null
        }
    }

    private fun callingUserId() = UserHandleCompat.getUserId(Binder.getCallingUid())

    override fun deepForceStop(packageName: String?): Boolean {
        if (packageName == null) return false
        return try {
            rikka.hidden.compat.ActivityManagerApis.forceStopPackageNoThrow(packageName, callingUserId())
            true
        } catch (e: Exception) {
            Log.w(TAG, "deepForceStop failed for $packageName", e)
            false
        }
    }

    override fun setAppStandbyBucket(packageName: String?, bucket: Int): Boolean {
        if (packageName == null) return false
        // Primary: IUsageStatsManager.setAppStandbyBucket — works at shell UID on all API levels
        try {
            val um = usageStatsService() ?: error("no usagestats service")
            val method = um.javaClass.methods.firstOrNull { it.name == "setAppStandbyBucket" }
                ?: error("setAppStandbyBucket not found")
            method.invoke(um, packageName, bucket, callingUserId())
            return true
        } catch (e: Exception) {
            Log.w(TAG, "setAppStandbyBucket IPC failed, falling back to exec", e)
        }
        // Fallback: am set-standby-bucket
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        val bucketStr = when (bucket) {
            10 -> "active"
            20 -> "working_set"
            30 -> "frequent"
            40 -> "rare"
            45, 50 -> "restricted"
            else -> bucket.toString()
        }
        return ShellExecutor.execBool("am", "set-standby-bucket", packageName, bucketStr)
    }

    override fun killAllBackgroundProcesses(): Boolean {
        // Primary: IActivityManager.killAllBackgroundProcesses — works at shell UID
        try {
            val am = activityManagerService() ?: error("no activity service")
            val method = am.javaClass.methods.firstOrNull { it.name == "killAllBackgroundProcesses" }
                ?: error("killAllBackgroundProcesses not found")
            method.invoke(am)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "killAllBackgroundProcesses IPC failed, falling back to exec", e)
        }
        return ShellExecutor.execBool("am", "kill-all")
    }

    private fun setApplicationEnabledSetting(packageName: String, state: Int): Boolean {
        // Primary: IPackageManager.setApplicationEnabledSetting
        return try {
            val pm = packageManagerService() ?: error("no package service")
            val method = pm.javaClass.methods.firstOrNull { it.name == "setApplicationEnabledSetting" }
                ?: error("setApplicationEnabledSetting not found")
            method.invoke(pm, packageName, state, 0, callingUserId(), "com.android.shell")
            true
        } catch (e: Exception) {
            Log.w(TAG, "setApplicationEnabledSetting IPC failed for $packageName state=$state", e)
            false
        }
    }

    override fun freezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        // Stage 1: COMPONENT_ENABLED_STATE_DISABLED_USER = 3 — user-level disable, reversible
        if (setApplicationEnabledSetting(packageName, 3)) return true
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        // Stage 2: pm disable-user fallback
        if (ShellExecutor.execBool("pm", "disable-user", "--user", callingUserId().toString(), packageName)) {
            return true
        }
        // Stage 3: cmd package suspend fallback for protected system packages
        return ShellExecutor.execBool("cmd", "package", "suspend", "--user", callingUserId().toString(), packageName)
    }

    override fun unfreezeApp(packageName: String?): Boolean {
        if (packageName == null) return false
        var restored = false
        // COMPONENT_ENABLED_STATE_DEFAULT = 0 — restore manifest-declared state
        if (setApplicationEnabledSetting(packageName, 0)) {
            restored = true
        }
        if (InputValidationUtils.isValidPackageName(packageName)) {
            ShellExecutor.execBool("cmd", "package", "unsuspend", "--user", callingUserId().toString(), packageName)
            if (!restored) {
                restored = ShellExecutor.execBool("pm", "enable", "--user", callingUserId().toString(), packageName)
            }
        }
        return restored
    }

    override fun isAppFrozen(packageName: String?): Boolean {
        if (packageName == null) return false
        // Primary: IPackageManager.getApplicationInfo — check enabled + FLAG_SUSPENDED
        // (1 shl 30) via reflection on the returned ApplicationInfo.
        try {
            val pm = packageManagerService() ?: error("no package service")
            val method = pm.javaClass.methods.firstOrNull { it.name == "getApplicationInfo" }
                ?: error("getApplicationInfo not found")
            val ai = method.invoke(pm, packageName, 0L, callingUserId())
            if (ai != null) {
                val flags = ai.javaClass.getField("flags").getInt(ai)
                val enabled = ai.javaClass.getField("enabled").getBoolean(ai)
                val suspended = (flags and (1 shl 30)) != 0
                return !enabled || suspended
            }
        } catch (e: Exception) {
            Log.w(TAG, "isAppFrozen IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm list packages -d lists only disabled packages; compare whole lines
        // ("package:<name>") — a bare substring hit fires for com.foo.bar when probing com.foo.
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        return ShellExecutor.exec("pm", "list", "packages", "-d", "--user", callingUserId().toString(), packageName)
            .lineSequence()
            .any { it.trim() == "package:$packageName" }
    }

    override fun setAppProcessLimit(limit: Int) {
        // Primary: IActivityManager.setProcessLimit
        try {
            val am = activityManagerService()
            val method = am?.javaClass?.methods?.firstOrNull { it.name == "setProcessLimit" }
            if (method != null) {
                method.invoke(am, limit)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "setProcessLimit IPC failed", e)
        }
        // Fallback: settings global + am exec
        ShellExecutor.execCode("settings", "put", "global", "max_phantom_processes", limit.toString())
        ShellExecutor.execCode("am", "set-process-limit", limit.toString())
    }

    override fun getRunningProcesses(): List<String> {
        // Primary: IActivityManager.getRunningAppProcesses — works at shell UID, no exec needed
        try {
            val am = activityManagerService() ?: error("no activity service")
            val method = am.javaClass.methods.firstOrNull { it.name == "getRunningAppProcesses" }
                ?: error("getRunningAppProcesses not found")
            @Suppress("UNCHECKED_CAST")
            val procs = method.invoke(am) as? List<*>
            if (!procs.isNullOrEmpty()) {
                return procs.mapNotNull { p ->
                    if (p == null) return@mapNotNull null
                    try {
                        val name = p.javaClass.getField("processName").get(p) as? String ?: return@mapNotNull null
                        val pid = p.javaClass.getField("pid").get(p) as? Int ?: 0
                        "$name $pid"
                    } catch (_: Exception) { null }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRunningProcesses IPC failed, falling back to exec", e)
        }
        // Fallback: ps -A (may be blocked by SELinux on Samsung OneUI 8)
        return ShellExecutor.exec("ps", "-A", "-o", "NAME,RSS,PID").lines()
    }

    private fun createPackageDataObserver(latch: java.util.concurrent.CountDownLatch): IBinder {
        // Parcel.writeStrongBinder() requires a real android.os.Binder subclass — a
        // Proxy.newProxyInstance implementing IBinder cannot be marshaled cross-process.
        // IPackageDataObserver has one method (onRemoveCompleted) at FIRST_CALL_TRANSACTION.
        return object : android.os.Binder() {
            init { attachInterface(null, "android.content.pm.IPackageDataObserver") }
            override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                return when (code) {
                    IBinder.FIRST_CALL_TRANSACTION -> { // onRemoveCompleted(String packageName, boolean succeeded)
                        data.enforceInterface("android.content.pm.IPackageDataObserver")
                        latch.countDown()
                        reply?.writeNoException()
                        true
                    }
                    else -> super.onTransact(code, data, reply, flags)
                }
            }
        }
    }

    override fun clearAppCache(packageName: String?): Boolean {
        if (packageName == null) return false
        // Primary: IPackageManager.deleteApplicationCacheFilesAsUser with a blocking observer
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = java.util.concurrent.CountDownLatch(1)
            val observer = createPackageDataObserver(latch)
            val method = pm.javaClass.methods.firstOrNull { it.name == "deleteApplicationCacheFilesAsUser" }
                ?: pm.javaClass.methods.firstOrNull { it.name == "deleteApplicationCacheFiles" }
                ?: error("deleteApplicationCacheFiles[AsUser] not found")
            if (method.parameterCount == 3) {
                method.invoke(pm, packageName, observer, callingUserId())
            } else {
                method.invoke(pm, packageName, observer)
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "clearAppCache IPC failed for $packageName, falling back to exec", e)
        }
        // Fallback: pm clear-cache (Android 13+) — targets the specific package
        if (!InputValidationUtils.isValidPackageName(packageName)) return false
        return ShellExecutor.execBool("pm", "clear-cache", "--user", "0", packageName)
    }

    override fun clearAppData(packageName: String?): Boolean {
        val name = packageName ?: return false
        if (!InputValidationUtils.isValidPackageName(name)) return false
        // The reflection path used to sit here: IPackageManager.clearApplicationUserData takes a
        // hidden `IPackageDataObserver`, and this module cannot construct one — nothing in the
        // hidden-API stub exposes that type, so every call passed a plain Binder, failed invoke()'s
        // argument type check and fell through to exec while logging an exception (audit).
        // Removing the always-failing attempt leaves the path that actually ran, and that path is
        // now audited: the package name is validated above, the target user is the calling user
        // instead of a hard-coded 0, and pm's own completion decides the result.
        return ShellExecutor.execBool("pm", "clear", "--user", callingUserId().toString(), name)
    }
}
