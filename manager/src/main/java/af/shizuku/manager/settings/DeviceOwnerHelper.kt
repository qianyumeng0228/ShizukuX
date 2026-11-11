package af.shizuku.manager.settings

import android.app.admin.DeviceAdminInfo
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import af.shizuku.manager.admin.DhizukuAdminReceiver
import af.shizuku.manager.R
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Device-Owner capabilities for ShizukuX (dhizuku mode).
 *
 * Aligned with shevery DeviceOwnerManager (26 methods) — the local helper
 * now covers ownership checks, ADB commands, delegated scopes, app hiding /
 * permission-grant state, admin activation (reflection setActiveAdmin), ownership
 * transfer and clear, Shizuku-assisted activation, and eligible-admin discovery.
 *
 * When this app holds Device Owner, [DevicePolicyManager.setGlobalSetting] can write
 * system-global settings directly. That bypasses the wireless-debugging switch entirely:
 * HyperOS-class ROMs disable that switch unless a Wi-Fi network is *connected*, which the
 * accessibility assistant cannot overcome — but the settings write works regardless, and
 * pairing still runs over 127.0.0.1. This is the only clean path for a single-phone,
 * no-Wi-Fi, no-PC user who has already set the device owner once.
 */
object DeviceOwnerHelper {

    private const val TAG = "DeviceOwnerHelper"

    data class DelegatedScope(
        val scopeName: String,
        val label: String,
        val description: String
    )

    val ALL_SCOPES: List<DelegatedScope> = listOf(
        DelegatedScope(
            scopeName = DevicePolicyManager.DELEGATION_APP_RESTRICTIONS,
            label = "App Restrictions",
            description = "Allows configuring managed configurations and app restrictions"
        ),
        DelegatedScope(
            scopeName = DevicePolicyManager.DELEGATION_BLOCK_UNINSTALL,
            label = "Block Uninstall",
            description = "Allows preventing specified applications from being uninstalled"
        ),
        DelegatedScope(
            scopeName = DevicePolicyManager.DELEGATION_PERMISSION_GRANT,
            label = "Permission Granting",
            description = "Allows granting or revoking runtime permissions without user prompts"
        ),
        DelegatedScope(
            scopeName = DevicePolicyManager.DELEGATION_PACKAGE_ACCESS,
            label = "Package Access (Freeze)",
            description = "Allows hiding, unhiding, and suspending applications"
        ),
        DelegatedScope(
            scopeName = DevicePolicyManager.DELEGATION_ENABLE_SYSTEM_APP,
            label = "Enable System Apps",
            description = "Allows enabling pre-installed disabled system applications"
        )
    )

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, DhizukuAdminReceiver::class.java)

    /** True when ShizukuX itself is the active device owner. */
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            dpm(context).isDeviceOwnerApp(context.packageName)
        } catch (e: Throwable) {
            false
        }
    }

    fun isProfileOwner(context: Context): Boolean {
        return try {
            dpm(context).isProfileOwnerApp(context.packageName)
        } catch (e: Throwable) {
            false
        }
    }

    fun isOwner(context: Context): Boolean = isDeviceOwner(context) || isProfileOwner(context)

    fun isAdminActive(context: Context): Boolean {
        return try {
            dpm(context).isAdminActive(adminComponent(context))
        } catch (e: Throwable) {
            false
        }
    }

    fun getAdbCommand(context: Context, asProfileOwner: Boolean = false): String =
        if (asProfileOwner) getProfileOwnerAdbCommand(context) else getDeviceOwnerAdbCommand(context)

    fun getDeviceOwnerAdbCommand(context: Context): String {
        val cn = adminComponent(context).flattenToShortString()
        return "dpm set-device-owner $cn"
    }

    fun getProfileOwnerAdbCommand(context: Context): String {
        val cn = adminComponent(context).flattenToShortString()
        return "dpm set-profile-owner $cn"
    }

    /**
     * Writes a single system-global setting as device owner.
     *
     * `DevicePolicyManager.setGlobalSetting` is allowlisted for exactly
     * `Settings.Global.ADB_ENABLED` and `adb_wifi_enabled` (AOSP
     * `DevicePolicyManagerService.GLOBAL_SETTINGS_ALLOWLIST`); writing any other key
     * throws `SecurityException`, so callers must only pass allowlisted keys here.
     *
     * @return true when the write succeeded, false otherwise (logs the failure).
     */
    fun setGlobalSetting(context: Context, key: String, value: String): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            dpm(context).setGlobalSetting(adminComponent(context), key, value)
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "setGlobalSetting $key failed")
            false
        }
    }

    /**
     * Turns USB debugging + wireless debugging on as device owner (ADB allowlist keys
     * only; does NOT touch development_settings_enabled). Idempotent.
     *
     * @return true when both writes succeeded.
     */
    fun enableAdbViaDpm(context: Context): Boolean {
        var ok = setGlobalSetting(context, Settings.Global.ADB_ENABLED, "1")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ok = setGlobalSetting(context, "adb_wifi_enabled", "1") && ok
        }
        return ok
    }

    /**
     * Enables developer options + USB debugging + wireless debugging as device owner.
     * No Wi-Fi connection and no settings-switch click are required.
     *
     * @return "" on success, otherwise a user-facing error message.
     */
    fun enableWirelessDebugging(context: Context): String {
        if (!isDeviceOwner(context)) {
            return context.getString(R.string.dhizuku_owner_not_active)
        }
        return try {
            val dpm = dpm(context)
            val admin = adminComponent(context)
            // Each setting is written independently: a single OEM-protected key must not
            // roll back the whole enable flow (e.g. adb_allowed_connection_time is a
            // system-guarded timestamp that throws "device owners cannot update ..." on
            // every Android 11+; it has no functional effect on pairing, so a failure
            // there is logged and ignored, never surfaced to the user as a hard error).
            fun writeGlobal(key: String, value: String, critical: Boolean): String? {
                return try {
                    dpm.setGlobalSetting(admin, key, value)
                    null
                } catch (e: Throwable) {
                    Timber.tag(TAG).w(e, "setGlobalSetting $key failed")
                    if (critical) "$key: ${e.message ?: e.javaClass.simpleName}" else null
                }
            }
            val errors = mutableListOf<String>()
            writeGlobal("development_settings_enabled", "1", critical = true)?.let { errors += it }
            writeGlobal(Settings.Global.ADB_ENABLED, "1", critical = true)?.let { errors += it }
            writeGlobal("adb_wifi_enabled", "1", critical = true)?.let { errors += it }
            // Deliberately NOT writing adb_allowed_connection_time: Android treats it as a
            // system-guarded ADB-authorization timestamp that device owners cannot update,
            // and writing it adds nothing to the pairing flow (the system stamps it itself
            // once a pairing is accepted).
            // Android 10 and below use the legacy secure port (adbd must be restarted there,
            // which the owner cannot do — still write it for consistency; the wireless path
            // on those versions is out of scope for the one-tap flow). Non-critical:
            // OEMs may guard it as well.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                try {
                    dpm.setSecureSetting(admin, "adb_port", "5555")
                } catch (e: Throwable) {
                    Timber.tag(TAG).w(e, "setSecureSetting adb_port failed")
                }
            }
            if (errors.isEmpty()) {
                Timber.tag(TAG).i("Wireless debugging enabled via device owner")
                ""
            } else {
                errors.joinToString("\n")
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "enableWirelessDebugging failed")
            e.message ?: e.javaClass.simpleName
        }
    }

    fun getDelegatedScopes(context: Context, packageName: String): List<String> {
        return try {
            dpm(context).getDelegatedScopes(adminComponent(context), packageName)
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun setDelegatedScopes(context: Context, packageName: String, scopes: List<String>): Boolean {
        return try {
            dpm(context).setDelegatedScopes(adminComponent(context), packageName, scopes)
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "setDelegatedScopes failed for $packageName")
            false
        }
    }

    fun setApplicationHidden(context: Context, packageName: String, hidden: Boolean): Boolean {
        return try {
            dpm(context).setApplicationHidden(adminComponent(context), packageName, hidden)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "setApplicationHidden failed: $packageName")
            false
        }
    }

    fun setPermissionGrantState(
        context: Context,
        packageName: String,
        permission: String,
        grantState: Int
    ): Boolean {
        return try {
            dpm(context).setPermissionGrantState(adminComponent(context), packageName, permission, grantState)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "setPermissionGrantState failed")
            false
        }
    }

    fun isStubPackage(context: Context, packageName: String): Boolean {
        if (packageName == "moe.shizuku.privileged.api") return true
        if (packageName == "com.rosan.dhizuku") {
            return try {
                val pi = context.packageManager.getPackageInfo(packageName, 0)
                pi.versionCode >= 1000000000
            } catch (e: Throwable) {
                false
            }
        }
        return false
    }

    fun ensureAdminActive(context: Context, targetComponent: ComponentName): Boolean {
        val dpm = dpm(context)
        if (dpm.isAdminActive(targetComponent)) return true
        // Reflection: DPM.setActiveAdmin(ComponentName, boolean) and the 3-arg user variant.
        // Only reachable for packages that declare a device-admin receiver; still guarded so
        // a SecurityException surfaces as false rather than crashing the settings screen.
        val reflectionAttempts = listOf(
            arrayOf<Any?>(targetComponent, true),
            arrayOf<Any?>(targetComponent, true, android.os.Process.myUserHandle().hashCode())
        )
        for (args in reflectionAttempts) {
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
                if (dpm.isAdminActive(targetComponent)) return true
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "setActiveAdmin(${args.size} args) failed")
            }
        }
        return dpm.isAdminActive(targetComponent)
    }

    fun transferOwnership(context: Context, targetComponent: ComponentName): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        return try {
            val dpm = dpm(context)
            val admin = adminComponent(context)
            ensureAdminActive(context, targetComponent)
            dpm.transferOwnership(admin, targetComponent, PersistableBundle())
            Timber.tag(TAG).i("Transferred Device Ownership to ${targetComponent.flattenToString()}")
            true
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "transferOwnership failed")
            false
        }
    }

    fun clearOwner(context: Context): Boolean {
        return try {
            val dpm = dpm(context)
            val admin = adminComponent(context)
            var ok = false
            if (isProfileOwner(context)) {
                try {
                    @Suppress("DEPRECATION")
                    dpm.clearProfileOwner(admin)
                    ok = true
                } catch (e: Throwable) {
                    Timber.tag(TAG).w(e, "clearProfileOwner failed")
                }
            }
            if (isDeviceOwner(context)) {
                try {
                    dpm.clearDeviceOwnerApp(context.packageName)
                    ok = true
                } catch (e: Throwable) {
                    Timber.tag(TAG).w(e, "clearDeviceOwnerApp failed")
                }
            }
            try {
                dpm.removeActiveAdmin(admin)
            } catch (e: Throwable) {
                Timber.tag(TAG).w(e, "removeActiveAdmin after clearOwner failed")
            }
            ok || !isOwner(context)
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "clearOwner failed")
            false
        }
    }

    fun clearDeviceOwner(context: Context): Boolean = clearOwner(context)

    /**
     * Activates this app as (profile/device) owner through the ShizukuX service's shell
     * privilege. The service runs at shell/ADB UID, which is allowed to run
     * `dpm set-{profile,device}-owner`. Returns (success, message).
     */
    fun activateOwnerViaShizuku(context: Context, asProfileOwner: Boolean = true): Pair<Boolean, String> {
        if (!Shizuku.pingBinder()) return false to context.getString(R.string.dhizuku_setup_shizukux_not_running)
        return try {
            val cn = adminComponent(context).flattenToShortString()
            val cmd = if (asProfileOwner) "dpm set-profile-owner $cn" else "dpm set-device-owner $cn"
            // Explicit environment variables are required because Shizuku.newProcess may not
            // inherit the full shell environment (PATH, HOME, ANDROID_ROOT, etc.).
            val env = arrayOf(
                "PATH=/system/bin:/system/xbin:/vendor/bin",
                "HOME=/data/local/tmp",
                "ANDROID_ROOT=/system",
                "ANDROID_DATA=/data",
                "EXTERNAL_STORAGE=/sdcard",
                "TMPDIR=/data/local/tmp"
            )
            val process = Shizuku.newProcess(arrayOf("/system/bin/sh", "-c", cmd), env, "/data/local/tmp")
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            val success = exitCode == 0 && (if (asProfileOwner) isProfileOwner(context) else isDeviceOwner(context))
            if (success) {
                true to (stdout.ifBlank { "Success" })
            } else {
                false to (stderr.ifBlank { stdout.ifBlank { "Command returned exit code $exitCode" } })
            }
        } catch (e: Throwable) {
            Timber.tag(TAG).w(e, "activateOwnerViaShizuku failed")
            false to (e.message ?: "Unknown error")
        }
    }

    data class AdminAppInfo(
        val componentName: ComponentName,
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val isActiveAdmin: Boolean
    )

    fun getEligibleAdminApps(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        val adminReceivers = pm.queryBroadcastReceivers(
            Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED),
            PackageManager.GET_META_DATA
        )
        val ownPackage = context.packageName
        return adminReceivers
            .mapNotNull { it.activityInfo?.applicationInfo }
            .filter { it.packageName != ownPackage && !isStubPackage(context, it.packageName) }
            .distinctBy { it.packageName }
    }

    fun getEligibleAdminAppsDetailed(context: Context): List<AdminAppInfo> {
        val pm = context.packageManager
        val dpm = dpm(context)
        val receivers = pm.queryBroadcastReceivers(
            Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED),
            PackageManager.GET_META_DATA
        )
        val ownPackage = context.packageName
        return receivers.mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            val appInfo = ai.applicationInfo ?: return@mapNotNull null
            if (ai.packageName == ownPackage || isStubPackage(context, ai.packageName)) return@mapNotNull null
            try {
                val adminInfo = DeviceAdminInfo(context, ri)
                val cn = adminInfo.component
                val isActive = dpm.isAdminActive(cn)
                val label = adminInfo.loadLabel(pm)?.toString() ?: appInfo.loadLabel(pm).toString()
                val icon = adminInfo.loadIcon(pm) ?: appInfo.loadIcon(pm)
                AdminAppInfo(
                    componentName = cn,
                    packageName = ai.packageName,
                    label = label,
                    icon = icon,
                    isActiveAdmin = isActive
                )
            } catch (e: Throwable) {
                val cn = ComponentName(ai.packageName, ai.name)
                val isActive = dpm.isAdminActive(cn)
                val label = appInfo.loadLabel(pm).toString()
                val icon = appInfo.loadIcon(pm)
                AdminAppInfo(
                    componentName = cn,
                    packageName = ai.packageName,
                    label = label,
                    icon = icon,
                    isActiveAdmin = isActive
                )
            }
        }.distinctBy { it.componentName }
    }
}
