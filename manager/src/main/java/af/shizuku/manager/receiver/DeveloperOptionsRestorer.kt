package af.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemProperties
import android.provider.Settings
import af.shizuku.manager.database.ShizukuProcessUtils
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Re-enables the developer-options / USB-debugging / wireless-debugging settings after a
 * reboot. MIUI/HyperOS (and some other Chinese ROMs) force developer options off on boot by
 * writing `Settings.Global.development_settings_enabled = 0`; the app then cannot be reached
 * over adb until the user re-enables it by hand. If the app holds WRITE_SECURE_SETTINGS (an
 * adb-granted appop that survives a reboot), this writes the three keys back — which also makes
 * the existing boot-time Shizuku auto-start (AdbStartWorker) able to discover the port again.
 *
 * All writes are best-effort and idempotent; without the permission this is a no-op returning
 * false so callers can surface the permission state instead of failing silently.
 */
object DeveloperOptionsRestorer {

    const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Whether this app currently holds the permission needed to write these keys. */
    fun canRestore(context: Context): Boolean {
        return context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }

    /** True when the wireless-debugging toggle actually stuck (reads the live key back). */
    fun isWirelessDebuggingEnabled(context: Context): Boolean {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, KEY_ADB_WIFI_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    /** Whether USB debugging itself is currently enabled (adb_enabled). */
    fun isUsbDebuggingEnabled(context: Context): Boolean {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)
    }

    /**
     * Whether Xiaomi's "USB debugging (security settings)" toggle is on. It is stored in the
     * `persist.security.adbinput` system property (not a Settings key); MIUI/HyperOS resets it on
     * reboot and the shell uid cannot write it back (SELinux blocks `setprop` for non-root), so
     * without root this can only ever be *read* to guide the user — never automated.
     */
    fun isAdbSecuritySettingEnabled(): Boolean {
        return runCatching {
            SystemProperties.get("persist.security.adbinput", "") == "1"
        }.getOrDefault(false)
    }

    /**
     * Turns Wi-Fi on so the wireless-debugging toggle can stick. Third-party apps cannot flip
     * Wi-Fi on Android 13+ (`WifiManager.setWifiEnabled` is a no-op for non-system apps), so this
     * routes `svc wifi enable` through Shizuku's privileged shell-uid process — the same channel
     * used by the stop-service button's `pkill`. When the Shizuku binder isn't up yet (first boot
     * pass) this is a no-op returning false; callers fall back to the delayed retry pass.
     */
    fun ensureWifiEnabled(): Boolean {
        return runCatching {
            if (!Shizuku.pingBinder()) {
                Timber.tag("DeveloperOptionsRestorer").i("Wi-Fi enable skipped: Shizuku binder not ready")
                return false
            }
            val r = ShizukuProcessUtils.runPrivilegedCapture(arrayOf("svc", "wifi", "enable"))
            Timber.tag("DeveloperOptionsRestorer")
                .i("svc wifi enable -> exit ${r.exitCode} out=${r.stdout.trim()} err=${r.stderr.trim()}")
            r.exitCode == 0
        }.getOrDefault(false)
    }

    /**
     * Writes developer options on, USB debugging on and wireless debugging on. Wireless debugging
     * only sticks while Wi-Fi is up, so when the Shizuku binder is available this first enables
     * Wi-Fi (see [ensureWifiEnabled]) and gives the Wi-Fi stack a short moment before writing
     * `adb_wifi_enabled`.
     * @return true when the writes were attempted (permission present); false when the app
     *         lacks WRITE_SECURE_SETTINGS. Throws are swallowed and reported via Timber.
     */
    fun restore(context: Context): Boolean {
        if (!canRestore(context)) {
            Timber.tag("DeveloperOptionsRestorer").i("No WRITE_SECURE_SETTINGS; skip restore")
            return false
        }
        return runCatching {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
            if (ensureWifiEnabled()) {
                // The toggle is rejected while the Wi-Fi stack is still coming up.
                Thread.sleep(1500L)
            }
            Settings.Global.putInt(cr, KEY_ADB_WIFI_ENABLED, 1)
            Timber.tag("DeveloperOptionsRestorer").i("Developer options / USB debugging / wireless debugging restored")
            true
        }.getOrElse { e ->
            Timber.tag("DeveloperOptionsRestorer").w(e, "Failed to restore developer options")
            false
        }
    }
}
