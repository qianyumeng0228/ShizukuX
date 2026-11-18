package af.shizuku.manager.hiding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import af.shizuku.manager.apps.PrivilegedShell
import af.shizuku.manager.apps.RotationOverride
import af.shizuku.manager.devicecontrol.AutoRotatePreference
import af.shizuku.manager.force_dark.ForceDarkManager
import af.shizuku.manager.utils.Logger.LOGGER
import kotlin.concurrent.thread

/**
 * Puts the per-app rules back after a restart.
 *
 * Two things are involved, and neither survives a reboot by itself:
 *
 * - The display lock is device-wide state. If the device restarted while an app was pinned, the
 *   system comes back with its own defaults and the saved base no longer describes anything, so
 *   it is dropped before anything else runs.
 * - The watch that applies the rules is a foreground service. Nothing starts it at boot, so a
 *   user who relies on the forced modes finds them dead until they open the app and touch a
 *   setting. Reported from ColorOS, where the app is also frozen in the background after a
 *   restart, so this is the only chance it gets.
 *
 * The shell usually is not up yet a few seconds after boot - the service either has to be started
 * by the user or by the boot start this app already runs - so the check is repeated instead of
 * tried once. Nothing here writes anything when the rules are empty.
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }

        val app = context.applicationContext
        // A receiver gets seconds; every step below is blocking shell or service work.
        val pending = goAsync()

        thread(name = "boot-restore") {
            try {
                // A stale lock marker would make the next pass restore a state captured before the
                // restart, which is not what the device is in now.
                runCatching { RotationOverride.resetAfterBoot() }

                if (RotationOverride.hasWorkToDo() ||
                    Hiding.hasWorkToDo() ||
                    ForceDarkManager.needsInProcessWatch()
                ) {
                    // Up to about 90 s: the boot start of the API server and the user unlocking
                    // both land inside that window on the devices measured so far.
                    for (attempt in 0 until 30) {
                        if (PrivilegedShell.available()) break
                        Thread.sleep(3000)
                    }

                    // The platform comes back with auto-rotate on, whatever the user had it at,
                    // so the switch has to be put back before the watch starts sampling it -
                    // otherwise the first idle pass records the platform's default as the user's
                    // choice. Runs first for that reason, not after the refresh.
                    if (PrivilegedShell.available()) {
                        val restored = runCatching {
                            AutoRotatePreference.restore(
                                available = { PrivilegedShell.available() },
                                run = { cmd -> PrivilegedShell.run(cmd) }
                            )
                        }.getOrDefault(false)
                        if (restored) LOGGER.w("boot restore put the auto-rotate switch back")
                    }

                    HidingWatchService.refresh(app)
                }
            } catch (t: Throwable) {
                LOGGER.w("boot restore failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
