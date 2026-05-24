package af.shizuku.manager.database

import timber.log.Timber

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

object RootCompatHelper {

    private fun escapeSed(s: String) = s
        .replace("\\", "\\\\")
        .replace("|", "\\|")
        .replace("&", "\\&")

    private fun escapeShellSingleQuote(s: String) = s.replace("'", "'\\''")

    private val GLOBAL_SETTINGS_APPS = mapOf(
        "com.android.vending" to "vending",
        "com.google.android.gms" to "gms"
    )

    suspend fun autoSetupAll(context: Context, path: String): Int = withContext(Dispatchers.IO) {
        Timber.i("Auto setup all for path: $path")
        0
    }

    suspend fun autoSetup(context: Context, packageName: String, path: String): Boolean = withContext(Dispatchers.IO) {
        Timber.i("Auto setup for $packageName at path: $path")
        fixPermissions(context, packageName)
    }

    suspend fun fixPermissions(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val pm = context.packageManager
            val pi = pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            val permissions = pi.requestedPermissions ?: return@withContext true

            for (perm in permissions) {
                if (perm.startsWith("android.permission.")) {
                    try {
                        // Use Shizuku to grant permissions if possible
                        // Shizuku.grantRuntimePermission(packageName, perm, UserHandleCompat.myUserHandle())
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to grant permission $perm to $packageName")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to fix permissions for $packageName")
            false
        }
    }
}