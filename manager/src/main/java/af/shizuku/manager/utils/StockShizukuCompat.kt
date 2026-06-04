package af.shizuku.manager.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object StockShizukuCompat {

    const val PACKAGE = "moe.shizuku.privileged.api"

    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun launch(context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun startViaStockShizuku(): Boolean {
        if (!rikka.shizuku.Shizuku.pingBinder()) return false
        return try {
            val starterCmd = af.shizuku.manager.starter.Starter.internalCommand
            // Spawn a fully detached process that waits 1 second, then starts our server.
            // We immediately force-stop the original Shizuku so the ports/ServiceManager are freed up.
            val cmd = "nohup sh -c 'sleep 1 && $starterCmd' >/dev/null 2>&1 & am force-stop $PACKAGE"
            rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isOriginalRunning(): Boolean {
        if (!rikka.shizuku.Shizuku.pingBinder()) return false
        return try {
            val process = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", "ps -A | grep shizuku_server"), null, null)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            var isOriginal = false
            while (reader.readLine().also { line = it } != null) {
                if (line!!.contains("moe.shizuku.privileged.api")) {
                    isOriginal = true
                    break
                }
            }
            process.destroy()
            isOriginal
        } catch (e: Exception) {
            false
        }
    }
}
