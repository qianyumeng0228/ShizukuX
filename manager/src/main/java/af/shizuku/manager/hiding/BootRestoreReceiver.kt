package af.shizuku.manager.hiding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import af.shizuku.manager.apps.RotationOverride
import af.shizuku.manager.devicecontrol.AutoRotatePreference
import af.shizuku.manager.force_dark.ForceDarkManager
import af.shizuku.manager.utils.Logger.LOGGER

/**
 * Asks for the per-app rules to be put back after a restart, and returns immediately.
 *
 * Two things are involved, and neither survives a reboot by itself:
 *
 * - The display lock is device-wide state. If the device restarted while an app was pinned, the
 *   system comes back with its own defaults and the saved base no longer describes anything, so
 *   it is dropped here, before anything else runs.
 * - The watch that applies the rules is a foreground service. Nothing starts it at boot, so a
 *   user who relies on the forced modes finds them dead until they open the app and touch a
 *   setting. Reported from ColorOS, where the app is also frozen in the background after a
 *   restart, so this is the only chance it gets.
 *
 * The waiting lives in [BootRestoreService], not here. A `goAsync` receiver used to hold the
 * broadcast open for up to ninety seconds; measured on 2210132C / HyperOS the platform reports
 * `ANR ... Broadcast ... BOOT_COMPLETED ... BootRestoreReceiver` sixty seconds in, so the restore
 * was working inside a window the platform had already closed. Everything in this method is a
 * preference read, which takes milliseconds, so the broadcast is finished long before that.
 *
 * `LOCKED_BOOT_COMPLETED` is deliberately not handled here: the preferences live in
 * credential-encrypted storage and are unreadable before the first unlock. The direct-boot start
 * that runs at that point is the ADB starter's job, not this one.
 */
class BootRestoreReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val app = context.applicationContext
        try {
            // A stale lock marker would make the next pass restore a state captured before the
            // restart, which is not what the device is in now.
            runCatching { RotationOverride.resetAfterBoot() }

            val wanted = RotationOverride.hasWorkToDo() ||
                Hiding.hasWorkToDo() ||
                ForceDarkManager.needsInProcessWatch() ||
                // A remembered auto-rotate value is a rule of its own. Without it, a user who only
                // switched the rotation off in this app never gets it back: the platform turns the
                // switch on at every restart and nothing else looks at it.
                AutoRotatePreference.value() != null

            if (wanted) {
                BootRestoreService.start(app)
            } else {
                LOGGER.w("no rules to restore after boot")
            }
        } catch (t: Throwable) {
            LOGGER.w("boot restore could not be queued", t)
        }
    }
}
