package af.shizuku.manager.hiding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.utils.Logger.LOGGER
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * The permissions hiding needs beyond the ones this app already holds, given once by Shizuku.
 *
 * None of them can be asked for with a dialog: there is no prompt to show, only a command to run.
 * All of them are declared in the manifest, which is not a formality - a permission an app has not
 * declared cannot be granted to it, and a `pm grant` against an undeclared permission appears to
 * do nothing at all.
 *
 *     cmd appops set --user current <package> GET_USAGE_STATS allow   which app is in front
 *     pm grant <package> android.permission.DUMP                       why another app was closed
 *     pm grant <package> android.permission.WRITE_SECURE_SETTINGS      writing the settings
 *     pm grant <package> android.permission.POST_NOTIFICATIONS         the watch's own notice
 *
 * The first is the one the watch cannot do without: with it the foreground comes from the
 * platform, which is what keeps a list that hides a debugging transport survivable. Without it
 * the watch falls back to the shell, and the shell is the first thing to die when the setting
 * being hidden is the one carrying it. The third is what makes `Settings.Global.putString` work at
 * all - without it the provider silently accepts nothing. The last is what makes the notification
 * that carries Restore and Resume visible at all on Android 13 and later; without it the watch
 * still runs, but the one control that puts the device back is not on screen.
 *
 * All of them survive until the app is reinstalled, so this is a setup step and not a per-run one.
 */
object HidingGrants {

    /** Whether the app may ask the platform which app is in front. */
    fun usageAccess(): Boolean = Hiding.hasUsageAccess()

    /** Whether the app may ask the platform why another app was closed. */
    fun dump(): Boolean = granted(Manifest.permission.DUMP)

    /** Whether the app may write the settings a hiding list hides. */
    fun secureSettings(): Boolean = granted(Manifest.permission.WRITE_SECURE_SETTINGS)

    /** Whether the watch's notification - and with it Restore and Resume - is visible. */
    fun notifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)

    fun all(): Boolean =
        usageAccess() && dump() && secureSettings() && notifications()

    private fun granted(permission: String): Boolean = runCatching {
        ShizukuApplication.appContext.checkSelfPermission(permission) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /** Whether there is a shell to ask at all: all three grants are shell-only. */
    fun canGrant(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Asks for whichever of the three is missing, and says whether they are all in place after.
     *
     * Runs shell commands, so it does not belong on the main thread.
     */
    fun ensure(): Boolean {
        if (all()) return true
        if (!canGrant()) return false

        val packageName = ShizukuApplication.appContext.packageName

        if (!usageAccess()) {
            Hiding.shell("cmd appops set --user current $packageName GET_USAGE_STATS allow")
        }
        if (!dump()) {
            Hiding.shell("pm grant $packageName android.permission.DUMP")
        }
        if (!secureSettings()) {
            Hiding.shell("pm grant $packageName android.permission.WRITE_SECURE_SETTINGS")
        }
        if (!notifications()) {
            Hiding.shell("pm grant $packageName android.permission.POST_NOTIFICATIONS")
        }

        val done = all()
        if (!done) {
            LOGGER.w(
                "the hiding grants are not in place: " +
                    "usage=${usageAccess()} dump=${dump()} secure=${secureSettings()} " +
                    "notifications=${notifications()}"
            )
        }
        return done
    }

    /**
     * The same, for callers that are not asking a question.
     *
     * Called when a hiding list is opened and when the watch starts. Runs off the caller's thread
     * on its own scope, because one of those callers is not on a thread that may block.
     */
    fun ensureQuietly() {
        scope.launch { runCatching { ensure() } }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
