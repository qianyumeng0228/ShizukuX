package af.shizuku.manager.utils

/**
 * Calls back when the Shizuku service appears or disappears, and only on those two edges.
 *
 * Every screen that reads platform state through the service renders an empty state while it is
 * away. Without this a screen keeps that empty state after the service comes back - or keeps the
 * last reading after it goes away - and either way the user reads it as "the setting was lost and
 * has to be made again". The rules themselves live in the platform and are never lost; only the
 * ability to read them comes and goes.
 *
 * Seeded with the current state so the immediate first callback from addListener() does not
 * reload on top of the caller's own first load.
 */
class ServiceStateReloader(private val onChange: () -> Unit) {

    private var running = ShizukuStateMachine.isRunning()

    private val listener: (ShizukuStateMachine.State) -> Unit = { state ->
        val now = state == ShizukuStateMachine.State.RUNNING
        if (now != running) onChange()
        running = now
    }

    fun attach() {
        ShizukuStateMachine.addListener(listener)
    }

    fun detach() {
        ShizukuStateMachine.removeListener(listener)
    }
}
