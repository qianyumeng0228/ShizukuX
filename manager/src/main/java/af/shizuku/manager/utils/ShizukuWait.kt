package af.shizuku.manager.utils

import android.os.SystemClock
import rikka.shizuku.Shizuku

/**
 * Waits for the Shizuku binder instead of asking once.
 *
 * An activity started straight from the shell (`am start .../FilesActivity`) can run before the
 * provider has attached the binder, and a single `pingBinder()` then answers false for a service
 * that is in fact running. Every caller of this runs on an IO thread, so the bounded wait costs
 * nothing on the UI.
 */
object ShizukuWait {

    private const val STEP_MS = 100L

    fun ping(timeoutMs: Long = 2500): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            try {
                Thread.sleep(STEP_MS)
            } catch (_: InterruptedException) {
                return runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            }
        }
    }
}
