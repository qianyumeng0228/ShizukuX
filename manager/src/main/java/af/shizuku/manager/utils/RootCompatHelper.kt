package af.shizuku.manager.utils

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import af.shizuku.manager.ktx.loge
import af.shizuku.manager.R
import rikka.shizuku.Shizuku
import timber.log.Timber

object RootCompatHelper {

    private fun escapeSed(s: String) = s
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("&", "\\&")

    private fun escapeShellSingleQuote(s: String) = s.replace("'", "'\\''")

    /**
     * Automatically configures popular root apps to use the ShizukuX SU Bridge.
     * Uses Shizuku's privileged shell to modify target app preferences.
     */
    suspend fun autoSetup(context: Context, packageName: String, suPath: String): Boolean = withContext(Dispatchers.IO) {
        // Check if Shizuku service is available, as it's needed for executing commands.
        if (!isShizukuAvailable()) {
            Toast.makeText(context, R.string.shizuku_not_available, Toast.LENGTH_LONG).show()
            return@withContext false
        }

        var success = false
        try {
            when (packageName) {
                "org.adaway" -> {
                    // AdAway supports a global setting for SU path, which Shizuku can modify.
                    success = executePrivileged(arrayOf("settings", "put", "global", "adaway_su_path", suPath))
                    if (success) {
                        Toast.makeText(context, R.string.su_bridge_magic_setup_success_global_setting, Toast.LENGTH_SHORT).show()
                    }
                }
                "dev.ukanth.ufirewall" -> {
                    // AFWall+ supports a global setting for SU path.
                    success = executePrivileged(arrayOf("settings", "put", "global", "afwall_su_path", suPath))
                    if (success) {
                        Toast.makeText(context, R.string.su_bridge_magic_setup_success_global_setting, Toast.LENGTH_SHORT).show()
                    }
                }
                // For all other apps, direct file editing is blocked by Android security.
                // We will guide the user to manual setup via copy-paste.
                else -> {
                    // No automatic step taken here, but the UI will prompt for manual setup.
                    success = true // Indicate that the process moved forward to manual guidance.
                }
            }
        } catch (e: Exception) {
            loge("autoSetup failed for package $packageName", e)
            false
        }
        success
    }


    private fun isShizukuRoot(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.getUid() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun autoSetupAll(context: Context, suPath: String): Int = withContext(Dispatchers.IO) {
        // We no longer require root for global settings, but Shizuku service availability is key.
        if (!isShizukuAvailable()) {
            Toast.makeText(context, R.string.shizuku_not_available, Toast.LENGTH_LONG).show()
            return@withContext 0
        }

        val pm = context.packageManager
        val installedPackages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        var processedCount = 0
        
        val appsSupportingGlobalSettings = setOf(
            "org.adaway",
            "dev.ukanth.ufirewall"
        )

        for (pkgInfo in installedPackages) {
            val pkg = pkgInfo.packageName
            if (pkg == context.packageName) continue // Skip self

            if (appsSupportingGlobalSettings.contains(pkg)) {
                // Attempt global setting injection for known supported apps
                if (autoSetup(context, pkg, suPath)) {
                    processedCount++
                }
            } else {
                // For all other apps, we fall back to prompting manual setup.
                // This counts as processed, as the UI will guide the user.
                processedCount++
            }
        }
        processedCount
    }

    private fun executePrivileged(cmd: Array<String>): Boolean {
        if (!Shizuku.pingBinder()) {
            Timber.w("RootCompatHelper: Shizuku binder not available, skipping command")
            return false
        }
        return try {
            val process = Shizuku.newProcess(cmd, null, null)
            
            // Start threads to drain output/error streams to prevent buffer-fill hangs
            val outDrainer = Thread { try { process.inputStream.bufferedReader().use { it.readText() } } catch (e: Exception) {} }
            val errDrainer = Thread { try { process.errorStream.bufferedReader().use { it.readText() } } catch (e: Exception) {} }
            outDrainer.start()
            errDrainer.start()

            val exitCode = process.waitFor()
            outDrainer.join(500)
            errDrainer.join(500)

            // Close all streams
            process.inputStream.close()
            process.errorStream.close()
            process.outputStream.close()
            
            exitCode == 0
        } catch (e: IllegalStateException) {
            // Binder lost between ping and call — not a bug, just timing.
            Timber.w("RootCompatHelper: binder lost during privileged command: ${e.message}")
            false
        } catch (e: Exception) {
            loge("execute privileged command failed", e)
            false
        }
    }
}
