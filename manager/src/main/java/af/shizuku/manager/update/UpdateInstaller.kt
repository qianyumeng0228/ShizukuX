package af.shizuku.manager.update

import android.content.Context
import android.os.Environment
import com.topjohnwu.superuser.Shell
import af.shizuku.manager.utils.SettingsBackupManager
import rikka.shizuku.Shizuku
import timber.log.Timber
import java.io.File

object UpdateInstaller {
    private const val TAG = "UpdateInstaller"
    const val AUTO_BACKUP_FILENAME = "shizuku_plus_auto_backup.json"

    fun getBackupFile(context: Context): File? {
        val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir ?: return null
        return File(extDir, AUTO_BACKUP_FILENAME)
    }

    fun forceUpdateWithShizuku(context: Context, apkFile: File): Boolean {
        val hasShizuku = try { Shizuku.pingBinder() } catch (e: Exception) { false }
        val hasRoot = try { com.topjohnwu.superuser.Shell.getShell().isRoot } catch (e: Exception) { false }
        if (!hasShizuku && !hasRoot) {
            Timber.tag(TAG).w("No root or Shizuku available for force update.")
            return false
        }

        try {
            // 1. Auto-export settings to a public location
            val backupJson = SettingsBackupManager.export(context)
            val backupFile = getBackupFile(context)
            if (backupFile != null) {
                backupFile.writeText(backupJson)
                Timber.tag(TAG).i("Auto-exported settings to ${backupFile.absolutePath}")
            }

            val packageName = context.packageName
            val apkPath = apkFile.absolutePath

            // NOTE: this script must never `pm uninstall` the package first. Uninstalling clears
            // /data/user/0/<pkg> (settings, per-uid authorization records, Dhizuku configuration)
            // and gives the reinstall a new UID, which silently invalidates every app
            // authorization. A plain `pm install -r -d` replaces the APK in place; a signature
            // conflict then fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE and leaves the old
            // build installed — the correct outcome, not something to "work around" by wiping.
            val script = """
                #!/system/bin/sh
                rm -f /data/local/tmp/update.apk
                sleep 2
                cp "$apkPath" /data/local/tmp/update.apk || exit 2
                chmod 644 /data/local/tmp/update.apk
                pm install -r -d /data/local/tmp/update.apk || exit 3
                pm grant $packageName android.permission.POST_NOTIFICATIONS 2>/dev/null
                pm grant $packageName android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
                appops set $packageName SYSTEM_ALERT_WINDOW allow 2>/dev/null
                appops set $packageName GET_USAGE_STATS allow 2>/dev/null
                am start -n $packageName/af.shizuku.manager.MainActivity
                rm -f /data/local/tmp/update.apk /data/local/tmp/force_update.sh
            """.trimIndent()

            if (hasRoot) {
                // Root: write script via libsu Shell, then run it synchronously so the exit
                // status actually reflects whether `pm install` succeeded (a nohup'd script
                // would discard that, leaving a failed install invisible).
                val cacheDir = context.cacheDir ?: context.filesDir ?: return false
                val scriptFile = File(cacheDir, "force_update.sh")
                scriptFile.writeText(script)
                val result = com.topjohnwu.superuser.Shell.cmd(
                    "cp '${scriptFile.absolutePath}' /data/local/tmp/force_update.sh && " +
                    "chmod 755 /data/local/tmp/force_update.sh && " +
                    "sh /data/local/tmp/force_update.sh"
                ).exec()
                if (!result.isSuccess) {
                    Timber.tag(TAG).w("Force update script exited with ${result.code} (root)")
                    return false
                }
            } else {
                // Shizuku (shell uid=2000): cannot read app-private cacheDir, so write script
                // directly via the process stdin (same pattern as SceneRelayManager).
                val writer = Shizuku.newProcess(
                    arrayOf("sh", "-c", "cat > /data/local/tmp/force_update.sh && chmod 755 /data/local/tmp/force_update.sh"),
                    null, null
                )
                if (writer == null) {
                    Timber.tag(TAG).w("Shizuku.newProcess returned null for script writer")
                    return false
                }
                writer.outputStream.write(script.toByteArray(Charsets.UTF_8))
                writer.outputStream.flush()
                writer.outputStream.close()
                writer.waitFor()

                // Run synchronously (no nohup) so exit != 0 can be reported instead of
                // disappearing into a detached process.
                val runner = Shizuku.newProcess(
                    arrayOf("sh", "-c", "sh /data/local/tmp/force_update.sh"),
                    null, null
                )
                if (runner == null) {
                    Timber.tag(TAG).w("Shizuku.newProcess returned null for script runner")
                    return false
                }
                val exit = runner.waitFor()
                if (exit != 0) {
                    Timber.tag(TAG).w("Force update script exited with $exit")
                    return false
                }
            }

            return true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to force update with Shizuku")
            return false
        }
    }
}
