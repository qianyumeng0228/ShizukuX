package af.shizuku.manager.firewall

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import af.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The installed-application list as the shell sees it.
 *
 * The manager's own PackageManager cannot answer on a Chinese ROM: QUERY_ALL_PACKAGES is
 * intercepted there by a runtime "get installed apps" permission, so it returns this app alone.
 * The IShizukuService list is no better - it exists for authorized apps and carries only the
 * eleven packages that talk to Shizuku, against 172 installed. Neither is what a firewall list
 * needs, so the platform's package manager is asked directly through the same binder wrapping
 * the firewall itself uses.
 */
object PackageEnumerator {

    private const val TAG = "PackageEnumerator"

    private fun packageManager(): Any? = runCatching {
        val binder: IBinder = SystemServiceHelper.getSystemService("package") ?: return null
        Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, ShizukuBinderWrapper(binder))
    }
        .onFailure { LOGGER.w("$TAG: package manager unreachable: ${it.message}") }
        .getOrNull()

    /**
     * Every installed application for [userId], or null when the platform cannot be asked.
     *
     * Both flag widths are tried because the hidden method takes a `long` from Android 13 on and
     * an `int` before it; the argument is built to match each signature rather than left to
     * coercion, which reflection does not do.
     */
    fun installedApplications(userId: Int): List<ApplicationInfo>? {
        val service = packageManager() ?: return null
        val flags = (PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong()

        val attempts = ArrayList<Pair<java.lang.reflect.Method, Any>>(2)
        runCatching {
            service.javaClass.getMethod(
                "getInstalledApplications",
                Long::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()?.let { attempts.add(it to flags) }
        runCatching {
            service.javaClass.getMethod(
                "getInstalledApplications",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
        }.getOrNull()?.let { attempts.add(it to flags.toInt()) }

        for ((method, argument) in attempts) {
            val result = runCatching { method.invoke(service, argument, userId) }.getOrNull() ?: continue
            val list = when (result) {
                is List<*> -> result.filterIsInstance<ApplicationInfo>()
                // Older platforms hand back the slice itself instead of the list inside it.
                else -> runCatching {
                    (result.javaClass.getMethod("getList").invoke(result) as? List<*>)
                        ?.filterIsInstance<ApplicationInfo>()
                }.getOrNull()
            }
            if (!list.isNullOrEmpty()) return list
        }

        LOGGER.w("$TAG: no installed-application list for user $userId")
        return null
    }
}
