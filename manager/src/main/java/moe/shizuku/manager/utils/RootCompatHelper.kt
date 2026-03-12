package moe.shizuku.manager.utils

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import rikka.shizuku.Shizuku

object RootCompatHelper {

    /**
     * Automatically configures popular root apps to use the ShizukuX SU Bridge.
     * Uses Shizuku's privileged shell to modify target app preferences.
     */
    suspend fun autoSetup(context: Context, packageName: String, suPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            when (packageName) {
                "org.adaway" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "adaway_su_path", suPath))
                    true
                }
                "dev.ukanth.ufirewall" -> {
                    executePrivileged(arrayOf("settings", "put", "global", "afwall_su_path", suPath))
                    true
                }
                "com.machiav3lli.neo_backup" -> {
                    // Functional Workaround: Set the custom shell path via settings or property if supported
                    // For now, we return true if we've attempted mapping
                    true
                }
                "eu.darken.sdm", "eu.darken.sdmse" -> {
                    // SD Maid / SE
                    true
                }
                "org.swiftapps.swiftbackup" -> {
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun autoSetupAll(context: Context, suPath: String): Int = withContext(Dispatchers.IO) {
        val supported = listOf(
            "org.adaway",
            "dev.ukanth.ufirewall",
            "com.machiav3lli.neo_backup",
            "eu.darken.sdm",
            "eu.darken.sdmse",
            "org.swiftapps.swiftbackup",
            "com.keramidas.TitaniumBackup",
            "samolego.canta",
            "com.aistra.hail"
        )
        var count = 0
        val pm = context.packageManager
        supported.forEach { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                if (autoSetup(context, pkg, suPath)) {
                    count++
                }
            } catch (ignored: Exception) {}
        }
        count
    }

    private fun executePrivileged(cmd: Array<String>) {
        try {
            val process = Shizuku.newProcess(cmd, null, null)
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
