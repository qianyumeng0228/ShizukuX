package af.shizuku.manager.devicecontrol

import rikka.shizuku.Shizuku
import moe.shizuku.server.IShizukuService

/**
 * Client-side wrapper for IDeviceControlExtra.
 *
 * All methods throw if Shizuku binder is not available; callers should
 * check Shizuku.pingBinder() first and handle RemoteException.
 *
 * Ported from upstream ShizukuPlus DeviceControl API (r2647), adapted to
 * ShizukuX Extra naming.
 */
object DeviceControlManager {

    private fun service(): IShizukuService =
        IShizukuService.Stub.asInterface(Shizuku.getBinder())

    private fun dc() = service().deviceControlExtra

    // ── Connectivity ──────────────────────────────────────────────────────────

    fun setAirplaneMode(enabled: Boolean): Boolean = dc().setAirplaneModeEnabled(enabled)

    fun setWifi(enabled: Boolean): Boolean = dc().setWifiEnabled(enabled)

    fun setBluetooth(enabled: Boolean): Boolean = dc().setBluetoothEnabled(enabled)

    fun setMobileData(enabled: Boolean): Boolean = dc().setMobileDataEnabled(enabled)

    fun setNfc(enabled: Boolean): Boolean = dc().setNfcEnabled(enabled)

    // ── USB ───────────────────────────────────────────────────────────────────

    fun setUsbFunction(function: String): Boolean = dc().setUsbFunction(function)

    // ── Power ─────────────────────────────────────────────────────────────────
    // NOTE: These require REBOOT permission which shell uid (2000) does NOT have.
    // They will return false on ADB mode. Only work reliably on root.

    fun reboot(reason: String? = null): Boolean = dc().reboot(reason)

    fun shutdown(): Boolean = dc().shutdown()

    /**
     * Check if power operations (reboot/shutdown) are likely to work.
     * Returns true if running as root (where REBOOT permission is available),
     * false if running as ADB/shell (uid 2000 lacks REBOOT).
     */
    fun isPowerAvailable(): Boolean {
        return try {
            val uid = service().uid
            uid == 0
        } catch (_: Exception) {
            false
        }
    }

    // ── Display ───────────────────────────────────────────────────────────────

    fun setScreenBrightness(level: Int): Boolean = dc().setScreenBrightness(level)

    fun setAutoBrightness(enabled: Boolean): Boolean = dc().setAutoBrightnessEnabled(enabled)

    fun setScreenTimeout(ms: Int): Boolean = dc().setScreenTimeout(ms)

    fun setAutoRotate(enabled: Boolean): Boolean = dc().setAutoRotateEnabled(enabled)

    // ── Audio ─────────────────────────────────────────────────────────────────

    fun setStreamVolume(stream: Int, level: Int): Boolean = dc().setStreamVolume(stream, level)

    fun getStreamVolume(stream: Int): Int = dc().getStreamVolume(stream)

    // ── System Appearance ─────────────────────────────────────────────────────

    fun setFontScale(scale: Float): Boolean = dc().setFontScale(scale)

    fun setAnimations(enabled: Boolean): Boolean = dc().setAnimationsEnabled(enabled)

    // ── Settings convenience ──────────────────────────────────────────────────

    fun putSetting(namespace: String, key: String, value: String): Boolean =
        dc().putSetting(namespace, key, value)

    fun getSetting(namespace: String, key: String): String? = dc().getSetting(namespace, key)
}
