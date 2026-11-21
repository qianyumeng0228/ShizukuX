package af.shizuku.manager.utils

import android.os.Handler
import android.os.Looper
import timber.log.Timber

/**
 * Calls back when the Shizuku service appears or disappears, and only on those two edges.
 *
 * Every screen that reads platform state through the service renders an empty state while it is
 * away. Without this a screen keeps that empty state after the service comes back - or keeps the
 * last reading after it goes away - and either way the user reads it as "the setting was lost and
 * has to be made again". The rules themselves live in the platform and are never lost; only the
 * ability to read them comes and goes.
 *
 * The state machine notifies from whichever thread the binder callback lands on. Compose state
 * and Activity views belong to the main thread, so the reload is posted there; otherwise a reload
 * racing the first load mutates snapshot state off-main. A generation token drops a queued reload
 * if the page detached in the meantime.
 *
 * Seeded with the current state so the immediate first callback from addListener() does not
 * reload on top of the caller's own first load.
 */
class ServiceStateReloader(private val onChange: () -> Unit) {

    @Volatile
    private var running = ShizukuStateMachine.isRunning()

    @Volatile
    private var attached = false

    @Volatile
    private var generation = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    private val listener: (ShizukuStateMachine.State) -> Unit = { state ->
        val now = state == ShizukuStateMachine.State.RUNNING
        if (now != running) {
            running = now
            val token = generation
            mainHandler.post {
                if (attached && token == generation) {
                    try {
                        onChange()
                    } catch (t: Throwable) {
                        Timber.tag("ServiceStateReloader").w(t, "Service reload callback failed")
                    }
                }
            }
        } else {
            running = now
        }
    }

    fun attach() {
        if (attached) return
        generation++
        attached = true
        ShizukuStateMachine.addListener(listener)
    }

    fun detach() {
        if (!attached) return
        attached = false
        generation++
        ShizukuStateMachine.removeListener(listener)
    }
}
