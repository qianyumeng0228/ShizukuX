package af.shizuku.manager.utils

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.SystemProperties
import android.provider.Settings
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import com.topjohnwu.superuser.Shell

private val appContext: Context
    get() = try {
        ShizukuApplication.appContext
    } catch (e: UninitializedPropertyAccessException) {
        // Fallback for very early access if possible, or just rethrow with better message
        throw IllegalStateException("EnvironmentUtils.appContext accessed before ShizukuApplication.appContext was initialized", e)
    }

object EnvironmentUtils {

    @JvmStatic
    fun isWatch(): Boolean = af.shizuku.common.util.EnvironmentUtils.isWatch(appContext)

    @JvmStatic
    fun isTelevision(): Boolean = af.shizuku.common.util.EnvironmentUtils.isTelevision(appContext)

    fun isTlsSupported(): Boolean = af.shizuku.common.util.EnvironmentUtils.isTlsSupported(appContext)

    fun isWifiRequired(): Boolean {
        return (getAdbTcpPort() <= 0 || !ShizukuSettings.getTcpMode())
    }

    // Warm up the root check eagerly when the class is loaded.
    // checkSuExists() just performs basic file stats and does not spawn an su process,
    // so it is safe and fast enough to run synchronously if needed.
    @Volatile
    private var isRootedCached: Boolean? = null

    @Volatile
    private var isRootedCheckedAt: Long = 0L

    /** Re-probe after this long: a root grant can be given/revoked in the external root manager
     *  (SukiSU/KernelSU) while the app stays alive, so the cached answer should not be permanent.
     *  30s keeps the home start card's launch-mode label in sync with the real grant state. */
    private const val ROOT_CHECK_TTL_MS = 30 * 1000L

    init {
        // Still prewarm in background to avoid any potential main thread hit during class load
        startRootCheck()
    }

    /** Kick off an early root-availability check. Safe to call multiple times. */
    fun prewarmAsync() {
        if (isRootedCached == null || System.currentTimeMillis() - isRootedCheckedAt > ROOT_CHECK_TTL_MS) startRootCheck()
    }

    private fun startRootCheck() {
        if (isRootedCached != null && System.currentTimeMillis() - isRootedCheckedAt <= ROOT_CHECK_TTL_MS) return
        val t = Thread({
            if (isRootedCached == null || System.currentTimeMillis() - isRootedCheckedAt > ROOT_CHECK_TTL_MS) {
                isRootedCached = try { checkRootCapability() } catch (_: Exception) { false }
                isRootedCheckedAt = System.currentTimeMillis()
            }
        }, "root-check")
        t.isDaemon = true
        t.start()
    }

    /** Returns the cached root-check result, or computes it synchronously if not yet available /
     *  stale (TTL elapsed). The synchronous path only runs file stats + the libsu grant query,
     *  both fast; no su process is spawned. */
    fun isRooted(): Boolean {
        var result = isRootedCached
        if (result == null || System.currentTimeMillis() - isRootedCheckedAt > ROOT_CHECK_TTL_MS) {
            result = try { checkRootCapability() } catch (_: Exception) { false }
            isRootedCached = result
            isRootedCheckedAt = System.currentTimeMillis()
        }
        return result
    }

    /** Root capability = su binary the app can actually stat (Magisk-style installs) OR the
     *  libsu root-grant query OR a direct su probe. The file-stat half alone is unreliable on
     *  KernelSU/SukiSU devices under enforcing SELinux: /system/bin/su carries a su_exec label
     *  that untrusted_app cannot stat, so File.exists() silently returns false and the cached
     *  result reads "no root" even though the device is rooted and the app is granted su.
     *  isAppGrantedRoot() answers the exact question the UI gates need ("can THIS app use root")
     *  by querying the root manager's grant record via libsu's service without spawning an su
     *  process, but on KernelSU/SukiSU under enforcing it can also report false before any
     *  libsu root shell has been built. probeSuAccess() is the final fallback that spawns
     *  `su -c id` directly; on a grant-listed app the root manager answers within a second
     *  without a prompt, on non-rooted devices exec fails immediately, and the 2s bound keeps
     *  the synchronous isRooted() path safe. */
    private fun checkRootCapability(): Boolean {
        if (checkSuExists()) return true
        if (Shell.isAppGrantedRoot() == true) return true
        return probeSuAccess()
    }

    /** Direct su probe: `su -c id` must print uid=0 for this app. 2s bound; on a granted app
     *  the root manager returns immediately, on ungranted devices KernelSU/SukiSU remember the
     *  denial so no prompt is re-shown every call. */
    private fun probeSuAccess(): Boolean {
        return try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val granted = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) && p.inputStream
                .bufferedReader().use { it.readText().contains("uid=0") }
            if (!granted && p.isAlive) p.destroyForcibly()
            granted
        } catch (_: Exception) {
            false
        }
    }

    private fun checkSuExists(): Boolean {
        val paths = System.getenv("PATH")?.split(":")?.toMutableList() ?: mutableListOf()
        // Add common modern root paths that might not be in the standard PATH
        paths.addAll(listOf(
            "/data/adb/ksu/bin",
            "/data/adb/ap/bin",
            "/data/adb/magisk",
            "/sbin",
            "/system/bin",
            "/system/xbin",
            "/data/local/xbin",
            "/data/local/bin",
            "/system/sd/xbin",
            "/system/bin/failsafe",
            "/data/local"
        ))
        for (path in paths) {
            if (java.io.File(path, "su").exists()) return true
        }
        return false
    }

    /** Returns the first su binary path that actually exists on this device, or null if none. */
    @JvmStatic
    fun resolveSuPath(): String? {
        val paths = System.getenv("PATH")?.split(":")?.toMutableList() ?: mutableListOf()
        paths.addAll(listOf(
            "/data/adb/ksu/bin",
            "/data/adb/ap/bin",
            "/data/adb/magisk",
            "/sbin",
            "/system/bin",
            "/system/xbin",
            "/data/local/xbin",
            "/data/local/bin",
            "/system/sd/xbin",
            "/system/bin/failsafe",
            "/data/local"
        ))
        for (path in paths) {
            val f = java.io.File(path, "su")
            if (f.exists()) return f.absolutePath
        }
        return null
    }

    @JvmStatic
    fun getFullSdkVersion(): Int = af.shizuku.common.util.EnvironmentUtils.getFullSdkVersion()

    @JvmStatic
    fun isSamsung(): Boolean = af.shizuku.common.util.EnvironmentUtils.isSamsung()

    @JvmStatic
    fun getOneUiVersion(): Int = af.shizuku.common.util.EnvironmentUtils.getOneUiVersion()

    @JvmStatic
    fun isOneUi8(): Boolean = af.shizuku.common.util.EnvironmentUtils.isOneUi8()

    @JvmStatic
    fun isOppo(): Boolean = af.shizuku.common.util.EnvironmentUtils.isOppo()

    @JvmStatic
    fun isOnePlus(): Boolean = af.shizuku.common.util.EnvironmentUtils.isOnePlus()

    @JvmStatic
    fun getColorOsVersion(): String {
        return SystemProperties.get("ro.build.version.opporom", "unknown")
    }

    @JvmStatic
    fun isXiaomi(): Boolean = af.shizuku.common.util.EnvironmentUtils.isXiaomi()

    @JvmStatic
    fun getHyperOsVersion(): String {
        return SystemProperties.get("ro.miui.ui.version.name", "unknown")
    }

    @JvmStatic
    fun isTCL(): Boolean = af.shizuku.common.util.EnvironmentUtils.isTCL()

    @JvmStatic
    fun isDeX(context: Context): Boolean = af.shizuku.common.util.EnvironmentUtils.isDeX(context)

    @JvmStatic
    fun isSecondaryUser(): Boolean = af.shizuku.common.util.EnvironmentUtils.isSecondaryUser()

    /**
     * Android 17 (API 37) redacts Settings.Global.ADB_ENABLED to 0 for third-party apps,
     * so a 0 reading no longer means USB debugging is actually disabled.
     */
    fun isAdbEnabled(): Boolean {
        if (Settings.Global.getInt(appContext.contentResolver, Settings.Global.ADB_ENABLED, 0) > 0) return true
        return Build.VERSION.SDK_INT >= 37
    }

    fun getAdbTcpPort(): Int {
        var port = af.shizuku.common.util.EnvironmentUtils.getAdbTcpPort()
        if (port == -1 && isTelevision() && !isTlsSupported()) port = ShizukuSettings.getTcpPort()
        return port
    }

    /**
     * Resolves a SAF directory URI to a physical file path for a given filename.
     */
    @JvmStatic
    fun resolveExportedPath(filename: String): String? {
        val uriStr = ShizukuSettings.getExportDirUri() ?: return null
        return try {
            val uri = android.net.Uri.parse(uriStr)
            var docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("raw:")) {
                docId = docId.removePrefix("raw:")
            }

            // Check for common volume patterns
            val basePath = when {
                docId.startsWith("primary:") -> {
                    val relative = docId.removePrefix("primary:")
                    if (relative.isEmpty()) "/storage/emulated/0"
                    else "/storage/emulated/0/$relative"
                }
                docId.contains(":") -> {
                    val parts = docId.split(":")
                    val volumeId = parts[0]
                    val relative = parts[1]
                    "/storage/$volumeId/$relative"
                }
                docId.startsWith("Download") || docId.startsWith("Documents") || docId.startsWith("Movies") -> {
                    "/storage/emulated/0/$docId"
                }
                else -> {
                    if (!docId.startsWith("/")) "/storage/emulated/0/$docId"
                    else docId
                }
            }

            val path = basePath.replace("//", "/") + "/" + filename
            path.replace("//", "/")
        } catch (e: Exception) {
            null
        }
    }
}
