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

        // Deep shell diagnostics require Shizuku; when it is running, append the full
        // device-side picture (logcat, /data/local/tmp state, daemon processes, ABI) so a
        // remote report can triage failures like Scene relay without a real device.
        sb.append(collectShell(context))

        return sb.toString()
    }

    /**
     * Runs a command through a Shizuku shell process and returns its combined output.
     *
     * Defensive on every axis: stdout and stderr are merged (`2>&1`) so a chatty stderr can never
     * fill the pipe buffer and deadlock a sequential read; the read runs on a worker thread; a
     * command that never finishes is force-destroyed after 8s so it can't hang the export
     * forever; any failure yields an "err:..." line instead of throwing, so one broken probe
     * never kills the whole report.
     *
     * NOTE: we deliberately never call waitFor()/exitValue() on the process. On some OEM builds
     * (OPPO ColorOS, Android 16) ShizukuProcess.exitValue() throws IllegalArgumentException
     * instead of IllegalThreadStateException when the child has not yet been reaped, and rikka's
     * waitFor(timeout) only catches the latter — the exception escapes and turns every single
     * probe into "err:IllegalArgumentException:process hasn't exited". Reading the merged stream
     * to EOF is equivalent: the stream closes exactly when the child exits, so we get the full
     * output (and implicitly know the process finished) without touching the buggy path.
     */
    private fun runShell(cmd: String): String {
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd + " 2>&1"), null, null)
            val read = java.util.concurrent.CompletableFuture.supplyAsync {
                try {
                    p.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Throwable) {
                    ""
                }
            }
            try {
                read.get(8, java.util.concurrent.TimeUnit.SECONDS).trim()
            } catch (e: java.util.concurrent.TimeoutException) {
                try { p.destroy() } catch (e2: Throwable) { /* already dead */ }
                // After destroy the stream may still drain whatever is buffered; give it a
                // short last chance, then fall back to a readable timeout marker.
                try {
                    read.get(3, java.util.concurrent.TimeUnit.SECONDS).trim()
                } catch (e2: Throwable) {
                    "(command timed out after 8s and was killed)"
                }
            }
        } catch (e: Throwable) {
            "err:${e.javaClass.simpleName}:${e.message}"
        }
    }

    /**
     * Deep shell diagnostics — collected only when Shizuku is actually running. Every block is
     * bounded (tail/head) so the report stays shareable over QQ as plain text, and every probe is
     * defensive so a locked-down OEM layer reports "err:..." rather than crashing the export.
     */
    private fun collectShell(context: Context): String {
        val sb = StringBuilder()
        if (!Shizuku.pingBinder()) {
            sb.append("\n\n==== Deep Shell Diagnostics ====\n")
            sb.append("(Shizuku is not running — shell diagnostics skipped. Start Shizuku, reproduce the issue, then re-export.)\n")
            return sb.toString()
        }
        fun block(title: String, cmd: String) {
            sb.append("\n--- ").append(title).append(" ---\n")
            sb.append(runShell(cmd)).append('\n')
        }
        sb.append("\n\n==== Deep Shell Diagnostics (via Shizuku) ====")
        block("ABI", "getprop ro.product.cpu.abi; getprop ro.product.cpu.abilist")
        block("SELinux / uid", "getenforce; id")
        block("/data/local/tmp (scene-related)", "ls -la /data/local/tmp 2>&1 | grep -iE 'scene|total'; echo '-- scene dir --'; ls -la /data/local/tmp/scene 2>&1")
        block("Scene binaries (checksums + magic)", "md5sum /data/local/tmp/scene/scene-daemon /data/local/tmp/scene-daemon /data/local/tmp/scene/busybox 2>&1; echo '-- daemon magic --'; head -c 8 /data/local/tmp/scene/scene-daemon 2>&1 | od -An -tx1")
        block("System props (build/ColorOS hints)", "getprop 2>/dev/null | grep -iE 'ro.build.version|ro.product.(name|device|model)|ro.build.display|coloros|oplus|sys.oppo|ro.oplus' | head -30")
        block("Scene / relay processes", "ps -A 2>&1 | grep -iE 'scene|vtools'; echo 'pidof:'; pidof scene-daemon 2>&1; echo 'pgrep:'; pgrep -l scene-daemon 2>&1; echo 'port 8765:'; ss -tulnp 2>&1 | grep 8765")
        block("up.sh on disk", "wc -c /data/local/tmp/scene/up.sh 2>&1; head -12 /data/local/tmp/scene/up.sh 2>&1")
        // Trial-run the daemon for 2s and capture its real stderr + exit code. exit=124 means the
        // daemon stayed alive until timeout killed it (it CAN run); any other code plus a stderr
        // message (missing library, exec format error, ...) shows exactly why it dies instantly.
        // If the daemon is already resident this reports its "address already in use" — which is
        // itself the answer (it IS running). No lasting side effect: output goes to a temp file
        // that is removed right after, and timeout reaps the process.
        block("Daemon trial run (2s, real stderr + exit code)", "timeout 2 /data/local/tmp/scene-daemon > /data/local/tmp/scene/daemon-trial.log 2>&1; ec=\$?; cat /data/local/tmp/scene/daemon-trial.log 2>/dev/null; echo \"trial_exit=\$ec (124 = daemon alive until timeout)\"; rm -f /data/local/tmp/scene/daemon-trial.log")
        // Logcat across main/system/crash buffers: single-buffer -d can miss the SystemServer /
        // crash ring where OEM permission denials and Scene crashes show up. Bounded by tail so
        // the report stays shareable.
        block("Logcat (main+system+crash, SX_DEBUG / SceneRelay / FATAL / scene)", "logcat -d -b main -b system -b crash -v threadtime 2>&1 | grep -iE 'SX_DEBUG|SceneRelay|FATAL|AndroidRuntime|scene-daemon|omarea|shizuku|ColorOS|Permission (denial|Denied)|SecurityException' | tail -200")
        // dumpsys probes: package state tells us Scene's installed version/uid; activity
        // processes reveals whether Scene is running and its death reason; the shizuku server
        // process line confirms which privilege source is alive.
        block("dumpsys (Scene pkg + shizuku server)", "dumpsys package com.omarea.vtools 2>&1 | grep -E 'versionCode|versionName|uid=|flags=|firstInstallTime|lastUpdateTime' | head -6; echo '-- scene/daemon in activity --'; dumpsys activity processes 2>&1 | grep -iE 'scene-daemon|omarea|vtools' | head -6; echo '-- shizuku server --'; ps -A 2>&1 | grep -iE 'shizuku|su_' | head -5")
        block("dmesg tail", "dmesg 2>&1 | tail -10")
        return sb.toString()
    }
}
