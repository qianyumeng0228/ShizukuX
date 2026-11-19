package af.shizuku.manager.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import af.shizuku.manager.settings.DeviceOwnerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/**
 * Single choke point for arming / disarming adbd.
 *
 * Three independent ways to write these Global settings:
 *
 *  - [Method.WRITE_SECURE_SETTINGS] — obtained either from
 *    `adb shell pm grant <pkg> android.permission.WRITE_SECURE_SETTINGS` or,
 *    after the first successful start, granted back to this app by the server
 *    (see `ShizukuService`, which grants it to the manager on bind). This is a
 *    strict superset: it can write every key we touch, including
 *    `adb_allowed_connection_time`.
 *  - [Method.DEVICE_OWNER] — `DevicePolicyManager.setGlobalSetting` is
 *    allowlisted for exactly `Settings.Global.ADB_ENABLED` and
 *    `adb_wifi_enabled` (AOSP `DevicePolicyManagerService.GLOBAL_SETTINGS_ALLOWLIST`).
 *    It CANNOT write `adb_allowed_connection_time` — that key is not on the
 *    allowlist and the call would throw `SecurityException` — so that write is
 *    skipped on this path and pairing keys keep their default lifetime.
 *  - [Method.SHIZUKU_SERVER] — the running server is shell (UID 2000) and holds
 *    `WRITE_SECURE_SETTINGS` itself, so it can write these keys on our behalf
 *    through `settings put`. This is the path that matters on ROMs whose
 *    permission monitor refuses to hand the app the system permission
 *    (ColorOS / OxygenOS): `pm grant` fails there, but the server writing the
 *    setting directly is not a grant and is not blocked.
 *
 * `WRITE_SECURE_SETTINGS` is a superset of what the other two can do, so it always
 * wins when available; Device Owner is the fallback for installs that never received
 * the `pm grant`; the server is the last resort before giving up.
 *
 * Every previously scattered `Settings.Global.putInt(ADB_*)` call site routes
 * through here so the paths can never drift apart again.
 *
 * Ported from shevery `62fbe35` (AdbArm), adapted to ShizukuX's existing
 * `DeviceOwnerHelper` (dhizuku mode) — the DO path writes only the allowlisted
 * ADB keys and never touches `development_settings_enabled`. The server path follows
 * Shizuku-Next's `canWriteSecureSettings` / `writeGlobal` semantics, but uses the
 * project's own shell details (bounded wait, no main-thread blocking).
 */
object AdbArm {

    private const val TAG = "AdbArm"

    const val KEY_ADB_WIFI_ENABLED = "adb_wifi_enabled"
    const val KEY_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    private const val SERVER_WRITE_TIMEOUT_MS = 5_000L

    enum class Method {
        WRITE_SECURE_SETTINGS,
        DEVICE_OWNER,
        SHIZUKU_SERVER,
        NONE
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** A server that is up can write these settings as shell even when we cannot. */
    private fun serverAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Which method a write would use right now. Cheap and synchronous: two
     * permission/DO checks plus a binder ping, so it is safe to call from a
     * [android.content.BroadcastReceiver].
     */
    fun method(context: Context): Method = when {
        hasWriteSecureSettings(context) -> Method.WRITE_SECURE_SETTINGS
        DeviceOwnerHelper.isDeviceOwner(context) -> Method.DEVICE_OWNER
        serverAvailable() -> Method.SHIZUKU_SERVER
        else -> Method.NONE
    }

    fun canArm(context: Context): Boolean = method(context) != Method.NONE

    // ---------------------------------------------------------------------
    // Synchronous API
    //
    // Kept for callers that cannot suspend. The server path blocks on a shell
    // process, so it is deliberately NOT attempted here: those callers get
    // [Method.NONE] and a log line telling them to use the suspend variant.
    // Use the *Suspend functions below wherever a coroutine is available.
    // ---------------------------------------------------------------------

    /**
     * Turn USB debugging + wireless debugging on. Idempotent: writing a value
     * that is already set is a no-op for every path.
     *
     * @return the method used, or [Method.NONE] when nothing was written.
     */
    fun arm(context: Context): Method {
        val app = context.applicationContext
        val method = method(app)
        when (method) {
            Method.WRITE_SECURE_SETTINGS -> writeWithSecureSettings(app)
            Method.DEVICE_OWNER -> DeviceOwnerHelper.enableAdbViaDpm(app)
            Method.SHIZUKU_SERVER -> Log.w(
                TAG,
                "arm: server path needs a coroutine, use armSuspend()"
            )
            Method.NONE -> Log.d(TAG, "cannot arm adbd: no writable route")
        }
        return method
    }

    /**
     * Re-arm USB debugging only (ADB_ENABLED=1, plus the immortal pairing-key
     * timeout when WRITE_SECURE_SETTINGS is held). Never throws.
     */
    fun armUsbDebugging(context: Context): Method {
        val app = context.applicationContext
        val method = method(app)
        when (method) {
            Method.WRITE_SECURE_SETTINGS -> {
                putInt(app, Settings.Global.ADB_ENABLED, 1)
                putLong(app, KEY_ADB_ALLOWED_CONNECTION_TIME, 0L)
            }
            Method.DEVICE_OWNER -> DeviceOwnerHelper.setGlobalSetting(app, Settings.Global.ADB_ENABLED, "1")
            Method.SHIZUKU_SERVER -> Log.w(
                TAG,
                "armUsbDebugging: server path needs a coroutine, use armUsbDebuggingSuspend()"
            )
            Method.NONE -> Log.d(TAG, "cannot arm USB debugging: no writable route")
        }
        return method
    }

    /**
     * Re-arm only `adb_wifi_enabled` (0 → 1 recovery path). Never throws, so it
     * is safe to call from a receiver.
     */
    fun armWifiEnabled(context: Context): Boolean {
        val app = context.applicationContext
        return when (method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, KEY_ADB_WIFI_ENABLED, 1)
            // enableAdbViaDpm writes both allowlisted keys; writing 1 → 1 again is harmless.
            Method.DEVICE_OWNER -> DeviceOwnerHelper.enableAdbViaDpm(app)
            Method.SHIZUKU_SERVER -> {
                Log.w(TAG, "armWifiEnabled: server path needs a coroutine, use armWifiEnabledSuspend()")
                false
            }
            Method.NONE -> false
        }
    }

    /**
     * Turn wireless debugging off. Device Owner may write "0" to the same
     * allowlisted keys, so the auto-disable paths work without the permission too.
     */
    fun disarmWifiEnabled(context: Context): Boolean {
        val app = context.applicationContext
        return when (method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, KEY_ADB_WIFI_ENABLED, 0)
            Method.DEVICE_OWNER ->
                DeviceOwnerHelper.setGlobalSetting(app, KEY_ADB_WIFI_ENABLED, "0")
            Method.SHIZUKU_SERVER -> {
                Log.w(TAG, "disarmWifiEnabled: server path needs a coroutine, use disarmWifiEnabledSuspend()")
                false
            }
            Method.NONE -> false
        }
    }

    /** Turn USB debugging off (honours the auto-disable-USB-debugging toggle). */
    fun disarmUsbDebugging(context: Context): Boolean {
        val app = context.applicationContext
        return when (method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, Settings.Global.ADB_ENABLED, 0)
            Method.DEVICE_OWNER ->
                DeviceOwnerHelper.setGlobalSetting(app, Settings.Global.ADB_ENABLED, "0")
            Method.SHIZUKU_SERVER -> {
                Log.w(TAG, "disarmUsbDebugging: server path needs a coroutine, use disarmUsbDebuggingSuspend()")
                false
            }
            Method.NONE -> false
        }
    }

    // ---------------------------------------------------------------------
    // Suspend API — the only variants that can use the server path.
    // Each hops to Dispatchers.IO itself, so callers may invoke them from
    // the main dispatcher without blocking the UI.
    // ---------------------------------------------------------------------

    suspend fun armSuspend(context: Context): Method = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val method = method(app)
        when (method) {
            Method.WRITE_SECURE_SETTINGS -> writeWithSecureSettings(app)
            Method.DEVICE_OWNER -> DeviceOwnerHelper.enableAdbViaDpm(app)
            Method.SHIZUKU_SERVER -> {
                // Always attempt every key: unlike the DO path there is no allowlist
                // here, and a partial failure must not stop the remaining writes.
                val adb = writeViaServer(app, Settings.Global.ADB_ENABLED, "1")
                val wifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    writeViaServer(app, KEY_ADB_WIFI_ENABLED, "1")
                } else {
                    true
                }
                val timeout = writeViaServer(app, KEY_ADB_ALLOWED_CONNECTION_TIME, "0")
                if (!adb || !wifi || !timeout) {
                    Log.w(TAG, "arm via server partially failed: adb=$adb wifi=$wifi timeout=$timeout")
                }
            }
            Method.NONE -> Log.d(TAG, "cannot arm adbd: no writable route")
        }
        method
    }

    suspend fun armUsbDebuggingSuspend(context: Context): Method = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val method = method(app)
        when (method) {
            Method.WRITE_SECURE_SETTINGS -> {
                putInt(app, Settings.Global.ADB_ENABLED, 1)
                putLong(app, KEY_ADB_ALLOWED_CONNECTION_TIME, 0L)
            }
            Method.DEVICE_OWNER ->
                DeviceOwnerHelper.setGlobalSetting(app, Settings.Global.ADB_ENABLED, "1")
            Method.SHIZUKU_SERVER -> {
                val adb = writeViaServer(app, Settings.Global.ADB_ENABLED, "1")
                val timeout = writeViaServer(app, KEY_ADB_ALLOWED_CONNECTION_TIME, "0")
                if (!adb || !timeout) {
                    Log.w(TAG, "arm USB debugging via server partially failed: adb=$adb timeout=$timeout")
                }
            }
            Method.NONE -> Log.d(TAG, "cannot arm USB debugging: no writable route")
        }
        method
    }

    suspend fun armWifiEnabledSuspend(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        when (val method = method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, KEY_ADB_WIFI_ENABLED, 1)
            // enableAdbViaDpm writes both allowlisted keys; writing 1 → 1 again is harmless.
            Method.DEVICE_OWNER -> DeviceOwnerHelper.enableAdbViaDpm(app)
            Method.SHIZUKU_SERVER -> writeViaServer(app, KEY_ADB_WIFI_ENABLED, "1")
            Method.NONE -> false
        }
    }

    suspend fun disarmWifiEnabledSuspend(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        when (val method = method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, KEY_ADB_WIFI_ENABLED, 0)
            Method.DEVICE_OWNER ->
                DeviceOwnerHelper.setGlobalSetting(app, KEY_ADB_WIFI_ENABLED, "0")
            Method.SHIZUKU_SERVER -> writeViaServer(app, KEY_ADB_WIFI_ENABLED, "0")
            Method.NONE -> false
        }
    }

    suspend fun disarmUsbDebuggingSuspend(context: Context): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        when (val method = method(app)) {
            Method.WRITE_SECURE_SETTINGS -> putInt(app, Settings.Global.ADB_ENABLED, 0)
            Method.DEVICE_OWNER ->
                DeviceOwnerHelper.setGlobalSetting(app, Settings.Global.ADB_ENABLED, "0")
            Method.SHIZUKU_SERVER -> writeViaServer(app, Settings.Global.ADB_ENABLED, "0")
            Method.NONE -> false
        }
    }

    /**
     * Writes one global setting as shell through the running server.
     *
     * The server is UID 2000 and holds `WRITE_SECURE_SETTINGS` itself, so this needs
     * no grant and is unaffected by a ROM that blocks grant-based provisioning.
     * Output is discarded and success is the exit status: `settings put` prints
     * nothing when it works, and a partially redacted key (Android 17) cannot be
     * read back to prove the write either.
     *
     * Bounded on purpose: a server that stopped answering must fail the write
     * instead of hanging the caller.
     */
    private suspend fun writeViaServer(context: Context, key: String, value: String): Boolean {
        if (!serverAvailable()) return false

        val process = runCatching {
            Shizuku.newProcess(
                arrayOf("sh", "-c", "settings put global $key $value >/dev/null 2>&1"),
                null,
                null
            )
        }.getOrNull() ?: run {
            Log.w(TAG, "settings put $key=$value: could not start a shell process")
            return false
        }

        return try {
            val exit = withTimeout(SERVER_WRITE_TIMEOUT_MS) { runInterruptible { process.waitFor() } }
            if (exit != 0) Log.w(TAG, "settings put $key=$value exited with $exit")
            exit == 0
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "settings put $key=$value timed out")
            false
        } catch (e: Exception) {
            Log.e(TAG, "settings put $key=$value failed", e)
            false
        } finally {
            runCatching { process.destroy() }
        }
    }

    /**
     * Full write set, only reachable with WRITE_SECURE_SETTINGS.
     *
     * `adb_allowed_connection_time = 0` makes wireless-debugging pairing keys
     * immortal (default is 7 days). This key is deliberately NOT attempted on
     * the Device Owner path.
     */
    private fun writeWithSecureSettings(context: Context): Boolean {
        return try {
            val cr = context.contentResolver
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.Global.putInt(cr, KEY_ADB_WIFI_ENABLED, 1)
            }
            Settings.Global.putLong(cr, KEY_ADB_ALLOWED_CONNECTION_TIME, 0L)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "arming adbd via WRITE_SECURE_SETTINGS failed", e)
            false
        }
    }

    private fun putInt(context: Context, key: String, value: Int): Boolean {
        return try {
            Settings.Global.putInt(context.contentResolver, key, value)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "writing $key=$value failed", e)
            false
        }
    }

    private fun putLong(context: Context, key: String, value: Long): Boolean {
        return try {
            Settings.Global.putLong(context.contentResolver, key, value)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "writing $key=$value failed", e)
            false
        }
    }
}
