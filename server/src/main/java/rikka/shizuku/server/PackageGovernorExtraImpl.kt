package rikka.shizuku.server

import android.os.Binder
import android.os.IBinder
import android.os.ServiceManager
import android.util.Log
import af.shizuku.server.IPackageGovernorExtra
import rikka.shizuku.server.util.InputValidationUtils
import rikka.shizuku.server.util.ShellExecutor
import af.shizuku.common.util.UserHandleCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * P2-1: aligned with ShizukuPlus PackageGovernorPlusImpl (275 lines) — Binder IPC
 * via reflection first (works at shell UID), ShellExecutor fallback. Keeps the
 * local IActivityManagerExtra-style AIDL surface and the stricter installApk
 * path check (P0 audit: isSafePath + .apk suffix, stronger than Plus).
 */
class PackageGovernorExtraImpl : IPackageGovernorExtra.Stub() {

    companion object {
        private const val TAG = "PackageGovernorPlus"
        // PackageManager.REQUESTED_PERMISSION_GRANTED = 2
        private const val REQUESTED_PERMISSION_GRANTED = 2
        // ApplicationInfo.FLAG_SUSPENDED = 1 shl 30
        private const val FLAG_SUSPENDED = 1 shl 30
        // ApplicationInfo.FLAG_DEBUGGABLE = 1 shl 2
        private const val FLAG_DEBUGGABLE = 1 shl 2
        // ApplicationInfo.FLAG_ALLOW_BACKUP = 1 shl 15
        private const val FLAG_ALLOW_BACKUP = 1 shl 15

        private fun packageManagerService(): Any? = try {
            val binder = ServiceManager.getService("package") ?: return null
            Class.forName("android.content.pm.IPackageManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        } catch (_: Exception) { null }

        private fun getApplicationInfoReflect(packageName: String, userId: Int): Any? = try {
            val pm = packageManagerService() ?: return null
            val method = pm.javaClass.methods.firstOrNull { it.name == "getApplicationInfo" }
                ?: return null
            method.invoke(pm, packageName, 0L, userId)
        } catch (e: Exception) {
            Log.w(TAG, "getApplicationInfo IPC failed for $packageName", e)
            null
        }
    }

    private fun callingUserId() = UserHandleCompat.getUserId(Binder.getCallingUid())

    private fun grantRuntimePermissionReflect(packageName: String, permission: String, userId: Int): Boolean = try {
        val pm = packageManagerService() ?: error("no package service")
        val method = pm.javaClass.methods.firstOrNull { it.name == "grantRuntimePermission" }
            ?: error("grantRuntimePermission not found")
        method.invoke(pm, packageName, permission, userId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "grantPermission IPC failed for $packageName/$permission", e)
        false
    }

    private fun revokeRuntimePermissionReflect(packageName: String, permission: String, userId: Int): Boolean = try {
        val pm = packageManagerService() ?: error("no package service")
        val method = pm.javaClass.methods.firstOrNull { it.name == "revokeRuntimePermission" }
            ?: error("revokeRuntimePermission not found")
        method.invoke(pm, packageName, permission, userId)
        true
    } catch (e: Exception) {
        Log.w(TAG, "revokePermission IPC failed for $packageName/$permission", e)
        false
    }

    override fun grantPermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        if (grantRuntimePermissionReflect(packageName, permission, callingUserId())) return true
        return ShellExecutor.execBool("pm", "grant", "--user", "0", packageName, permission)
    }

    override fun revokePermission(packageName: String?, permission: String?): Boolean {
        if (packageName.isNullOrBlank() || permission.isNullOrBlank()) return false
        if (revokeRuntimePermissionReflect(packageName, permission, callingUserId())) return true
        return ShellExecutor.execBool("pm", "revoke", "--user", "0", packageName, permission)
    }

    override fun getGrantedPermissions(packageName: String?): List<String> {
        if (packageName.isNullOrBlank()) return emptyList()
        // Primary: PackageInfo.requestedPermissionsFlags filtered by REQUESTED_PERMISSION_GRANTED
        try {
            val pm = packageManagerService() ?: error("no package service")
            val method = pm.javaClass.methods.firstOrNull { it.name == "getPackageInfo" }
                ?: error("getPackageInfo not found")
            val info = method.invoke(pm, packageName, android.content.pm.PackageManager.GET_PERMISSIONS.toLong(), callingUserId())
            if (info != null) {
                val perms = info.javaClass.getField("requestedPermissions").get(info) as? Array<*> ?: return emptyList()
                val flags = (info.javaClass.getField("requestedPermissionsFlags").get(info) as? IntArray)
                    ?: IntArray(perms.size)
                return perms.mapNotNull { it as? String }.filterIndexed { i, _ ->
                    flags.getOrElse(i) { 0 } and REQUESTED_PERMISSION_GRANTED != 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getGrantedPermissions IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return try {
            val output = ShellExecutor.exec("pm", "dump", packageName)
            val granted = mutableListOf<String>()
            var inGrantedSection = false
            for (line in output.lines()) {
                val trimmed = line.trim()
                when {
                    trimmed == "granted permissions:" -> inGrantedSection = true
                    inGrantedSection && trimmed.startsWith("android.permission.") -> granted.add(trimmed)
                    inGrantedSection && !trimmed.startsWith("android.") && trimmed.isNotEmpty() -> inGrantedSection = false
                }
            }
            granted
        } catch (_: Exception) { emptyList() }
    }

    override fun uninstallForUser(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.deletePackageAsUser — shell UID has DELETE_PACKAGES
        try {
            val pm = packageManagerService() ?: error("no package service")
            val latch = CountDownLatch(1)
            var deleteResult = -1
            // Parcel.writeStrongBinder() requires a real android.os.Binder — a Proxy implementing
            // IBinder can't be marshaled cross-process. IPackageDeleteObserver has one method
            // (packageDeleted) at FIRST_CALL_TRANSACTION.
            val observer = object : android.os.Binder() {
                init { attachInterface(null, "android.content.pm.IPackageDeleteObserver") }
                override fun onTransact(code: Int, data: android.os.Parcel, reply: android.os.Parcel?, flags: Int): Boolean {
                    return when (code) {
                        IBinder.FIRST_CALL_TRANSACTION -> { // packageDeleted(String packageName, int returnCode)
                            data.enforceInterface("android.content.pm.IPackageDeleteObserver")
                            data.readString() // packageName (unused)
                            deleteResult = data.readInt()
                            latch.countDown()
                            reply?.writeNoException()
                            true
                        }
                        else -> super.onTransact(code, data, reply, flags)
                    }
                }
            }
            val invoked = pm.javaClass.methods
                .filter { it.name == "deletePackageAsUser" }
                .any { m ->
                    runCatching {
                        when (m.parameterTypes.size) {
                            4 -> m.invoke(pm, packageName, observer, userId, 0)
                            5 -> m.invoke(pm, packageName, null, observer, userId, 0)
                            else -> return@any false
                        }
                        true
                    }.getOrDefault(false)
                }
            if (invoked) {
                latch.await(15, TimeUnit.SECONDS)
                if (deleteResult == 1) return true // DELETE_SUCCEEDED = 1
            }
        } catch (e: Exception) {
            Log.w(TAG, "uninstallForUser IPC failed for $packageName, falling back", e)
        }
        return ShellExecutor.execBool("pm", "uninstall", "--user", "0", packageName)
    }

    override fun restoreSystemApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.installExistingPackageAsUser — no callback needed
        try {
            val pm = packageManagerService() ?: error("no package service")
            val methods = pm.javaClass.methods.filter { it.name == "installExistingPackageAsUser" }
            for (m in methods) {
                val result = runCatching {
                    when (m.parameterTypes.size) {
                        2 -> m.invoke(pm, packageName, userId) as? Int
                        3 -> m.invoke(pm, packageName, userId, 0) as? Int
                        4 -> m.invoke(pm, packageName, userId, 0, 1) as? Int
                        else -> null
                    }
                }.getOrNull()
                if (result == 1) return true // INSTALL_SUCCEEDED
            }
        } catch (e: Exception) {
            Log.w(TAG, "restoreSystemApp IPC failed for $packageName, falling back", e)
        }
        return ShellExecutor.execBool("pm", "install-existing", "--user", "0", packageName)
    }

    override fun suspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.setPackagesSuspendedAsUser — shell UID has MANAGE_USERS
        try {
            val pm = packageManagerService() ?: error("no package service")
            val pkgArray = arrayOf(packageName)
            val method = pm.javaClass.methods.firstOrNull { it.name == "setPackagesSuspendedAsUser" }
            if (method != null) {
                val result = method.invoke(pm, *buildSuspendArgs(method, pkgArray, true, userId))
                val failed = result as? Array<*>
                if (failed != null && failed.isEmpty()) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "suspendApp IPC failed for $packageName, falling back", e)
        }
        return ShellExecutor.execBool("pm", "suspend", "--user", "0", packageName)
    }

    override fun unsuspendApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val userId = callingUserId()
        // Primary: IPackageManager.setPackagesSuspendedAsUser
        try {
            val pm = packageManagerService() ?: error("no package service")
            val pkgArray = arrayOf(packageName)
            val method = pm.javaClass.methods.firstOrNull { it.name == "setPackagesSuspendedAsUser" }
            if (method != null) {
                val result = method.invoke(pm, *buildSuspendArgs(method, pkgArray, false, userId))
                val failed = result as? Array<*>
                if (failed != null && failed.isEmpty()) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "unsuspendApp IPC failed for $packageName, falling back", e)
        }
        return ShellExecutor.execBool("pm", "unsuspend", "--user", "0", packageName)
    }

    // setPackagesSuspendedAsUser signature changed across API levels:
    // API 24-27: (String[], boolean, int) | API 28-29: (String[], boolean, PersistableBundle, PersistableBundle, String, int)
    // API 30+: (String[], boolean, PersistableBundle, PersistableBundle, SuspendDialogInfo, String, int)
    private fun buildSuspendArgs(method: java.lang.reflect.Method, pkgs: Array<String>, suspend: Boolean, userId: Int): Array<Any?> {
        return when (method.parameterTypes.size) {
            3 -> arrayOf(pkgs, suspend, userId)
            6 -> arrayOf(pkgs, suspend, null, null, "com.android.shell", userId)
            7 -> arrayOf(pkgs, suspend, null, null, null, "com.android.shell", userId)
            else -> arrayOf(pkgs, suspend, userId)
        }
    }

    override fun isAppSuspended(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ApplicationInfo.FLAG_SUSPENDED — direct Binder IPC, no text parsing
        try {
            val ai = getApplicationInfoReflect(packageName, callingUserId())
            if (ai != null) return (ai.javaClass.getField("flags").getInt(ai) and FLAG_SUSPENDED) != 0
        } catch (e: Exception) {
            Log.w(TAG, "isAppSuspended IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return ShellExecutor.exec("pm", "dump", packageName).lines().any { it.trim() == "suspended=true" }
    }

    override fun installApk(apkPath: String?): Boolean {
        if (apkPath.isNullOrBlank()) return false
        // Only accept files under the sanctioned path prefixes and with a real .apk
        // extension, so an authorized caller cannot point `pm install -g` (which
        // auto-grants every runtime permission) at an arbitrary dropped file
        // (audit slice4 P0-3). The install -g behavior itself is unchanged.
        if (!InputValidationUtils.isSafePath(apkPath)) return false
        if (!apkPath.endsWith(".apk", ignoreCase = true)) return false
        return ShellExecutor.execBool("pm", "install", "-g", apkPath)
    }

    override fun isAppDebuggable(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ApplicationInfo.FLAG_DEBUGGABLE — direct Binder IPC
        try {
            val ai = getApplicationInfoReflect(packageName, callingUserId())
            if (ai != null) return (ai.javaClass.getField("flags").getInt(ai) and FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            Log.w(TAG, "isAppDebuggable IPC failed for $packageName, falling back", e)
        }
        // Fallback: run-as exits 0 only for debuggable apps
        return ShellExecutor.execBool("run-as", packageName, "true")
    }

    override fun isBackupAllowed(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        // Primary: ApplicationInfo.FLAG_ALLOW_BACKUP — direct Binder IPC
        try {
            val ai = getApplicationInfoReflect(packageName, callingUserId())
            if (ai != null) return (ai.javaClass.getField("flags").getInt(ai) and FLAG_ALLOW_BACKUP) != 0
        } catch (e: Exception) {
            Log.w(TAG, "isBackupAllowed IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return ShellExecutor.exec("pm", "dump", packageName).lines().any { it.trim().equals("allowBackup=true", ignoreCase = true) }
    }

    override fun getAppDataDir(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        // Primary: ApplicationInfo.dataDir — direct Binder IPC, no text parsing
        try {
            val ai = getApplicationInfoReflect(packageName, callingUserId())
            val dir = ai?.javaClass?.getField("dataDir")?.get(ai) as? String
            if (!dir.isNullOrEmpty()) return dir
        } catch (e: Exception) {
            Log.w(TAG, "getAppDataDir IPC failed for $packageName, falling back", e)
        }
        // Fallback: pm dump parse
        return ShellExecutor.exec("pm", "dump", packageName).lines()
            .firstOrNull { it.trim().startsWith("dataDir=") }
            ?.trim()?.removePrefix("dataDir=")?.trim()
            ?.takeIf { it.isNotEmpty() }
    }
}
