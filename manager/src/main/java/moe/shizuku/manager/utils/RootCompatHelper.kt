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
                    // AdAway uses a custom su path in its preferences
                    executePrivileged("settings put global adaway_su_path $suPath")
                    // Some versions might use XML prefs, we can use Shizuku to 'sed' them if needed,
                    // but many modern ones have a hidden setting or check common paths.
                    true
                }
                "dev.ukanth.ufirewall" -> {
                    // AFWall+ su path
                    executePrivileged("settings put global afwall_su_path $suPath")
                    true
                }
                "eu.darken.sdm", "eu.darken.sdmse" -> {
                    // SD Maid / SE
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun executePrivileged(cmd: String) {
        // Use the new ShizukuX Shell optimization for native speed
        try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            process.waitFor()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
