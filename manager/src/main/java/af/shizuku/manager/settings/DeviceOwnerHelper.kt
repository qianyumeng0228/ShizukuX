package af.shizuku.manager.settings

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import af.shizuku.manager.admin.DhizukuAdminReceiver
import af.shizuku.manager.R
import timber.log.Timber

/**
 * Device-Owner capabilities for ShizukuX (dhizuku mode).
 *
 * When this app holds Device Owner, [DevicePolicyManager.setGlobalSetting] can write
 * system-global settings directly. That bypasses the wireless-debugging switch entirely:
 * HyperOS-class ROMs disable that switch unless a Wi-Fi network is *connected*, which the
 * accessibility assistant cannot overcome — but the settings write works regardless, and
 * pairing still runs over 127.0.0.1. This is the only clean path for a single-phone,
 * no-Wi-Fi, no-PC user who has already set the device owner once.
 */
object DeviceOwnerHelper {

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, DhizukuAdminReceiver::class.java)

    /** True when ShizukuX itself is the active device owner. */
    fun isDeviceOwner(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(context.packageName)
        } catch (e: Throwable) {
            false
        }
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
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = adminComponent(context)
            dpm.setGlobalSetting(admin, "development_settings_enabled", "1")
            dpm.setGlobalSetting(admin, Settings.Global.ADB_ENABLED, "1")
            dpm.setGlobalSetting(admin, "adb_wifi_enabled", "1")
            dpm.setGlobalSetting(admin, "adb_allowed_connection_time", "0")
            // Android 10 and below use the legacy secure port (adbd must be restarted there,
            // which the owner cannot do — still write it for consistency; the wireless path
            // on those versions is out of scope for the one-tap flow).
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                dpm.setSecureSetting(admin, "adb_port", "5555")
            }
            Timber.tag("DeviceOwnerHelper").i("Wireless debugging enabled via device owner")
            ""
        } catch (e: Throwable) {
            Timber.tag("DeviceOwnerHelper").w(e, "setGlobalSetting failed as device owner")
            e.message ?: e.javaClass.simpleName
        }
    }
}
