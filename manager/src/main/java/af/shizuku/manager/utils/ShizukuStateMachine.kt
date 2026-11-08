package af.shizuku.manager.utils

import android.content.Context
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.adb.AdbArm
import rikka.shizuku.Shizuku
import io.sentry.Sentry
import io.sentry.Breadcrumb

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    // Seeded from the last persisted settled state (see the persistence hook in transition()),
    // not a hardcoded STOPPED - a freshly cold-started process (e.g. WatchdogAlarmReceiver reviving
    // the app after an OEM freezer killed it, #417) otherwise has no way to tell "the server was
    // RUNNING when this process died" apart from "the user deliberately stopped it": both would
    // read as the in-memory default, and update() below would silently report STOPPED for an actual
    // crash, so the watchdog's CRASHED-triggered restart would never fire for exactly the case it
    // exists to handle.
    private var state = AtomicReference<State>(loadPersistedSettledState())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    private fun loadPersistedSettledState(): State = try {
        when (ShizukuSettings.getLastSettledState()) {
            State.RUNNING.name -> State.RUNNING
            State.CRASHED.name -> State.CRASHED
            else -> State.STOPPED
        }
    } catch (e: Exception) {
        State.STOPPED
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                Sentry.addBreadcrumb(Breadcrumb("Binder received - service is now RUNNING").apply {
                    category = "shizuku.service"
                })
                set(State.RUNNING)
            }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener {
                Sentry.addBreadcrumb(Breadcrumb("Binder dead - service connection lost").apply {
                    category = "shizuku.service"
                    level = io.sentry.SentryLevel.WARNING
                })
                setDead()
            }
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        // Capture the value our own CAS produced: a separate state.get() after getAndUpdate can
        // observe a concurrent transition's later write, making oldState == newState and silently
        // skipping listener/broadcast side effects for a transition that did occur.
        var computed: State? = null
        val oldState = state.getAndUpdate { current -> transform(current).also { computed = it } }
        val newState = computed!!
        if(oldState != newState) {
            listeners.forEach { it(newState) }
            Timber.tag("ShizukuStateMachine").d(newState.toString())

            // Record which app build is starting this server instance. All deliberate start paths
            // (AdbStarter, ShizukuReceiverStarter, tile, StarterActivity) funnel through STARTING,
            // so this captures every fresh start centrally and lets isServerVersionSkewed() later
            // detect a running server left behind by a pre-update build.
            if (newState == State.STARTING) {
                try {
                    ShizukuSettings.setServerStartedBuild(BuildConfig.VERSION_CODE)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to record server start build")
                }

                // A deliberate start overwrites the "user stopped it" tombstone written by
                // markUserStopped() below. Without this, every automatic starter (boot receiver,
                // LockedBootStartService, AdbStartWorker, watchdog) would keep skipping after the
                // user's next manual start - the tombstone has to be cleared by intent, and only a
                // start carries that intent. STARTING is not RUNNING, so a cold-started process
                // still resolves it to STOPPED via loadPersistedSettledState(): a start that never
                // completed must not be mistaken for a live server or for a crash.
                try {
                    ShizukuSettings.setLastSettledState(State.STARTING.name)
                    ShizukuSettings.setUserStoppedIntent(false)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to persist start intent")
                }
            }

            if (newState == State.RUNNING || newState == State.STOPPED || newState == State.CRASHED) {
                try {
                    val context = ShizukuApplication.appContext
                    af.shizuku.manager.automation.AutomationEngine.dispatchEvent(
                        af.shizuku.manager.automation.ShizukuStateEvent(newState == State.RUNNING),
                        context
                    )
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to dispatch automation event")
                }

                // Auto-close the ADB wireless-debugging listener (and the TCP port adbd was
                // bound to) when the session ends, so the port is not left open after a stop.
                // Only on STOPPED/CRASHED: the connection is already gone by then, so writing
                // adb_wifi_enabled=0 cannot tear down a live socket (hostile-ROM red line).
                if ((newState == State.STOPPED || newState == State.CRASHED) &&
                    ShizukuSettings.isAutoCloseTcpPortEnabled()
                ) {
                    try {
                        val context = ShizukuApplication.appContext
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                if (AdbArm.disarmWifiEnabled(context)) {
                                    Timber.tag("ShizukuStateMachine").i("auto-closed TCP port (disabled wireless debugging)")
                                }
                            } catch (e: Exception) {
                                Timber.tag("ShizukuStateMachine").w(e, "Failed to auto-close TCP port")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.tag("ShizukuStateMachine").w(e, "Failed to initiate TCP auto-close on $newState")
                    }
                }

                // Persist so a future cold-started process (see loadPersistedSettledState() above)
                // can tell a crash from a deliberate stop instead of defaulting to "assume stopped".
                try {
                    ShizukuSettings.setLastSettledState(newState.name)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to persist settled state")
                }
            }

            // Broadcast state change for widgets and other receivers
            try {
                val context = ShizukuApplication.appContext
                val intent = android.content.Intent("af.shizuku.manager.action.STATE_CHANGED").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
            } catch (e: UninitializedPropertyAccessException) {
                Timber.tag("ShizukuStateMachine").w("Skipping broadcast: appContext not initialized yet")
            }
        }
    }

    fun set(newState: State) = transition { newState }

    /**
     * The user asked for the server to stop (home X, ⋮ stop, tile, manual-stop broadcast).
     *
     * Persist STOPPED *before* the kill lands, then enter STOPPING. This closes a real race:
     * killServerProcess() / Shizuku.exit() tear the binder down from our side, and
     * OnBinderDeadListener does not reliably fire for a connection we closed ourselves. The state
     * machine then stays in STOPPING - which is deliberately not persisted - so the last written
     * value remains RUNNING. A process that is force-stopped / frozen / reaped afterwards cold-starts
     * with RUNNING, update()'s "was RUNNING but binder is gone" branch promotes it to CRASHED,
     * and WatchdogService does exactly what it is designed to do with a crash: restarts the server.
     * The user's stop is thereby undone, and because the restart resolves LaunchMethod.ADB the
     * server comes back as a different instance with an empty grant list.
     *
     * STOPPED is also the tombstone every automatic starter checks (ShizukuSettings.isUserStoppedIntent),
     * so the intent survives even if some other component gets the chance to start first.
     */
    fun markUserStopped() {
        try {
            ShizukuSettings.setLastSettledState(State.STOPPED.name)
            ShizukuSettings.setUserStoppedIntent(true)
        } catch (e: Exception) {
            Timber.tag("ShizukuStateMachine").w(e, "Failed to persist stop intent")
        }
        set(State.STOPPING)
        // The binder-death callback is not guaranteed for a connection we closed ourselves, and
        // STOPPING is never persisted. Settle it ourselves so listeners (the apps page's empty
        // state, the watchdog flow) see a terminal STOPPED even when the callback never arrives.
        // setDead() is the same STOPPING -> STOPPED path the callback uses, including the USB
        // debugging clean-up, so nothing is bypassed.
        CoroutineScope(Dispatchers.Main.immediate).launch {
            delay(800)
            if (get() == State.STOPPING) setDead()
        }
    }

    fun setDead() = transition {
        when (it) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> {
                try {
                    val context = ShizukuApplication.appContext
                    val shouldDisableUsbDebugging = ShizukuSettings.getAutoDisableUsbDebugging()
                    if (shouldDisableUsbDebugging) {
                        AdbArm.disarmUsbDebugging(context)
                    }
                } catch (e: UninitializedPropertyAccessException) {
                    Timber.tag("ShizukuStateMachine").w("Skipping USB debugging disable: appContext not initialized yet")
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to disable USB debugging")
                }
                State.STOPPED
            }
            else -> it
        }
    }

    /**
     * A start attempt finished without producing a live binder.
     *
     * Deliberately NOT STOPPED. WatchdogService only reacts to CRASHED, and STOPPED is what
     * isUserStoppedIntent() reads as "the user wants it down" (unless the explicit flag is present,
     * see ShizukuSettings). Settling a failed *start* there switched self-healing off for good: the
     * watchdog's automatic restart resolves LaunchMethod.ADB, hits a device with no
     * wireless-debugging port, falls back to the root probe, which cannot answer headless, and the
     * app parked itself in STOPPED - no further watchdog attempt, no boot retry, and the UI read
     * "ShizukuX 已停止" as if the user had pressed stop. CRASHED is the honest reading (there is no
     * server and nobody asked for that) and it is the one state the watchdog listens for, so its
     * backoff / MAX_AUTO_RESTARTS / degraded probing keep owning the retry cadence.
     *
     * The stop tombstone is not touched here: only markUserStopped() sets it, and any transition
     * into STARTING already cleared it.
     *
     * update() re-checks the binder afterwards: a server that is actually alive (a manual
     * `su -c` instance the guard in start() chose not to disturb, or one that came up while the
     * previous shell was still exiting) flips this to RUNNING rather than parking a false crash.
     */
    fun markStartFailed() {
        set(State.CRASHED)
        update()
    }

    fun update(): State {
        val span = Sentry.getSpan()?.startChild("ipc.shizuku", "pingBinder")
        val isAlive = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        } finally {
            span?.finish()
        }

        val currentState = get()
        val state = when {
            isAlive -> State.RUNNING
            currentState == State.STARTING -> State.STARTING
            currentState == State.STOPPING -> State.STOPPING
            currentState == State.CRASHED -> State.CRASHED
            // Was RUNNING (or, thanks to loadPersistedSettledState(), a freshly cold-started
            // process that persisted RUNNING before it died) and the binder isn't answering: that's
            // a crash, not a stop. Previously fell into the `else -> STOPPED` branch below, which
            // WatchdogService's flow collector (only listens for CRASHED) silently ignores - the
            // watchdog's external re-arm (#417) never restarted anything because every unexpected
            // death got misreported as an intentional stop.
            currentState == State.RUNNING -> State.CRASHED
            else -> State.STOPPED
        }
        set(state)
        return state
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
    }

    /**
     * True when the running privileged server was started by an app build older than the one now
     * installed — i.e. the app was updated but the server (a separate long-lived process) is still
     * running the old code. The binder wire protocol can differ across versions, so this can
     * silently break connections for third-party apps until the service is restarted. Returns false
     * when the starting build is unknown (0, e.g. server predates this tracking) or already current.
     */
    fun isServerVersionSkewed(): Boolean {
        if (!isRunning()) return false
        val startedBuild = ShizukuSettings.getServerStartedBuild()
        return startedBuild in 1 until BuildConfig.VERSION_CODE
    }

    fun isDead(): Boolean {
        return (get() == State.STOPPED || get() == State.CRASHED)
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

}
