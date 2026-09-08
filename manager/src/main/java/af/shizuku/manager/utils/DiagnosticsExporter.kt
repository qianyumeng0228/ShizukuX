package af.shizuku.manager.utils

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.home.isPairingAssistantEnabled
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One-tap diagnostics exporter. Collects everything a client can observe about the device,
 * the server, and key settings into a plain-text report that users can copy or share (e.g. to
 * QQ) when reporting issues — no adb / logcat permissions needed.
 *
 * Every probe is defensive: a broken binder or a restricted OEM layer must never crash the export.
 */
object DiagnosticsExporter {

    fun collect(context: Context): String {
        val sb = StringBuilder()
        fun line(key: String, value: Any?) {
            sb.append(key).append(": ").append(value).append('\n')
        }
        fun tryGet(block: () -> Any?): Any? = try {
            block()
        } catch (e: Throwable) {
            "err:${e.javaClass.simpleName}"
        }

        sb.append("==== ShizukuX Diagnostics ====").append('\n')
        line("Time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        sb.append('\n')

        sb.append("[App]").append('\n')
        line("Package", context.packageName)
        line("Version", BuildConfig.VERSION_NAME)
        line("VersionCode", BuildConfig.VERSION_CODE)
        line("Flavor", BuildConfig.FLAVOR)
        line("BuildType", BuildConfig.BUILD_TYPE)
        sb.append('\n')

        sb.append("[Device]").append('\n')
        line("Manufacturer", Build.MANUFACTURER)
        line("Brand", Build.BRAND)
        line("Model", Build.MODEL)
        line("Android", Build.VERSION.RELEASE)
        line("SDK", Build.VERSION.SDK_INT)
        line("FullSdk", tryGet { EnvironmentUtils.getFullSdkVersion() })
        line("ColorOS", tryGet { EnvironmentUtils.getColorOsVersion() })
        line("HyperOS", tryGet { EnvironmentUtils.getHyperOsVersion() })
        line("Root", tryGet { EnvironmentUtils.isRooted() })
        line("SecondaryUser", tryGet { EnvironmentUtils.isSecondaryUser() })
        line("BrandFlags", buildList {
            if (EnvironmentUtils.isOppo()) add("OPPO")
            if (EnvironmentUtils.isOnePlus()) add("OnePlus")
            if (EnvironmentUtils.isXiaomi()) add("Xiaomi")
            if (EnvironmentUtils.isSamsung()) add("Samsung")
            if (EnvironmentUtils.isTelevision()) add("TV")
        }.joinToString("/").ifEmpty { "generic" })
        sb.append('\n')

        sb.append("[Shizuku Server]").append('\n')
        line("pingBinder", tryGet { Shizuku.pingBinder() })
        line("Running", tryGet { ShizukuStateMachine.isRunning() })
        line("ServerUid", tryGet { Shizuku.getUid() })
        line("ServerVersion", tryGet { Shizuku.getVersion() })
        line("LatestVersion", tryGet { Shizuku.getLatestServiceVersion() })
        line("PatchVersion", tryGet { Shizuku.getServerPatchVersion() })
        line("SELinux", tryGet { Shizuku.getSELinuxContext() })
        line("CustomApi", tryGet { Shizuku.isCustomApiEnabled() })
        line("AdbPermission(GRANT_RUNTIME_PERMISSIONS)", tryGet {
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
        })
        line("SecureSettings(WRITE_SECURE_SETTINGS)", tryGet {
            Shizuku.checkRemotePermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
        })
        sb.append('\n')

        sb.append("[Settings]").append('\n')
        line("ExternalRelayAuto", tryGet { ShizukuSettings.getExternalRelayAuto() })
        line("ActivityLog", tryGet { ShizukuSettings.getPreferences().getBoolean(ShizukuSettings.Keys.KEY_ENABLE_ACTIVITY_LOG, true) })
        line("Watchdog", tryGet { ShizukuSettings.getWatchdog() })
        line("StartOnBoot", tryGet { ShizukuSettings.getStartOnBoot(context) })
        line("AutoPairing", tryGet { context.isPairingAssistantEnabled() })
        line("TcpPort", tryGet { ShizukuSettings.getTcpPort() })
        line("LastPort", tryGet { ShizukuSettings.getLastPort() })
        sb.append('\n')

        sb.append("[Accessibility]").append('\n')
        line("EnabledServices", tryGet {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } ?: "(empty)")
        line("MasterSwitch", tryGet {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) == 1
        })
        sb.append('\n')

        sb.append("[Wireless Debugging]").append('\n')
        line("AdbPort", tryGet { EnvironmentUtils.getAdbTcpPort() })

        return sb.toString()
    }
}
