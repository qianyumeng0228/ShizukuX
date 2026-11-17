package af.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import timber.log.Timber
import kotlinx.coroutines.*
import androidx.annotation.RequiresApi
import af.shizuku.manager.ShizukuSettings
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Int>
) {

    private var registered = false
    private var running = false
    private var serviceName: String? = null

    /**
     * Host the discovered adb service resolved to, for consumers to connect to. Normalized so
     * loopback stays "127.0.0.1" (byte-identical to the previous hard-coded behavior for the common
     * on-device case); only a non-loopback *local-interface* address — as can happen under Android
     * 16+ Local Network Protection, where the adb daemon may advertise a real interface rather than
     * loopback — is surfaced, so pairing connects to the actual host instead of failing on 127.0.0.1.
     */
    @Volatile
    var resolvedHost: String = "127.0.0.1"
        private set
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)
    private val mdnsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var restartJob: Job? = null
    private var restartScheduled = false
    private var attempts = 0

    /**
     * Per-service resolve retry counters. A failed resolve used to be dropped without a
     * trace (the callback was an empty override), so a device that once answered
     * `FAILURE_ALREADY_ACTIVE` was never discovered again until the whole discovery restarted.
     * All access happens on the main thread (NsdManager callbacks and [mdnsScope]).
     */
    private val resolveAttempts = HashMap<String, Int>()

    /**
     * Sightings this client is resolving, or has accepted, keyed by service name.
     *
     * A ROM that delivers one service's found event several times, each time as a fresh
     * [NsdServiceInfo], would otherwise issue one resolve per delivery. Android 12 allows a
     * single in-flight resolve per client, so only the first can answer; the rest fail with
     * FAILURE_ALREADY_ACTIVE, and the one success is then dropped because the entry it was
     * meant for had already been replaced. The port never reached the observer. Keeping the
     * entry while its resolve is pending (and after it was accepted) turns a repeat into a
     * duplicate sighting instead of a second resolve.
     */
    private val found = HashMap<String, NsdServiceInfo>()

    fun start() {
        if (running) return
        running = true
        if (!registered) {
            try {
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Throwable) {
                // Android 16 local-network protection can refuse discovery outright; the caller
                // keeps its own state, so leave this instance off rather than half-started.
                running = false
                Timber.tag(TAG).w(e, "service discovery was refused")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        restartJob?.cancel()
        restartJob = null
        // A cancelled restart coroutine never reaches its own "restartScheduled = false"
        // epilogue; without resetting these here, a later start() on this instance would
        // never auto-reconnect again (and the dead scope would swallow new jobs). Keep the
        // scope alive (it is only used for the restart job, which we just cancelled).
        restartScheduled = false
        attempts = 0
        found.clear()
        resolveAttempts.clear()
        if (registered) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                Timber.tag(TAG).e(e, "listener not registered when stopping service discovery")
            }
            registered = false
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        if (!running) return
        val key = info.serviceName ?: return
        // A name that already has an entry — either still being resolved or already accepted —
        // is a duplicate sighting of the same service, not a new one.
        if (key in found || key == serviceName) {
            Timber.tag(TAG).v("onServiceFound: $key (duplicate, skipped)")
            return
        }
        found[key] = info
        resolve(info)
    }

    private fun resolve(info: NsdServiceInfo) {
        try {
            nsdManager.resolveService(info, ResolveListener(this, info))
        } catch (e: Throwable) {
            // A resolve that could not even be submitted leaves no callback behind, so the
            // entry has to go here or the next sighting would be skipped as a duplicate.
            forget(info)
            Timber.tag(TAG).w(e, "cannot resolve ${info.serviceName}")
        }
    }

    /**
     * Drops [info] from [found], but only while it is still the instance that was recorded:
     * a callback for an older sighting must not clear the entry a newer one installed.
     */
    private fun forget(info: NsdServiceInfo) {
        val key = info.serviceName ?: return
        if (found[key] === info) found.remove(key)
    }

    /**
     * Bounded retry for a failed mDNS resolve, with linear backoff, reusing the same
     * [NsdServiceInfo] record. Gives up after [MAX_RESOLVE_ATTEMPTS] so a permanently broken
     * advertisement cannot spin forever.
     */
    private fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
        if (!running) return
        val key = info.serviceName ?: return
        // Nothing is in flight for this entry any more — it either failed outright or was
        // refused because another resolve of this client still held the single slot. Either
        // way the entry has to go, or the next sighting would be skipped as a duplicate.
        forget(info)
        val attempt = resolveAttempts[key] ?: 0
        if (attempt >= MAX_RESOLVE_ATTEMPTS) {
            Timber.tag(TAG).w("resolve of $key failed ($errorCode), giving up after $attempt retries")
            return
        }
        resolveAttempts[key] = attempt + 1
        Timber.tag(TAG).v("resolve of $key failed ($errorCode), retry ${attempt + 1}/$MAX_RESOLVE_ATTEMPTS")
        mdnsScope.launch {
            delay((attempt + 1) * 1000L)
            if (running && key !in found && key != serviceName) {
                found[key] = info
                resolve(info)
            }
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        val key = info.serviceName ?: return
        // The service is gone, so its entry must stop blocking the next announce of it.
        found.remove(key)
        resolveAttempts.remove(key)
        if (key == serviceName) {
            serviceName = null
            observer.onChanged(-1)
        }
    }

    private fun onServiceResolved(sighting: NsdServiceInfo, resolvedService: NsdServiceInfo) {
        val key = resolvedService.serviceName ?: sighting.serviceName
        val host = resolvedService.host
        val hostAddress = host?.hostAddress
        if (hostAddress == null) {
            // No address to connect to: drop the entry so a later announce resolves again
            // instead of being skipped as a duplicate.
            forget(sighting)
            Timber.tag(TAG).v("onServiceResolved: $key has no host")
            return
        }
        val isLocal = hostAddress == "127.0.0.1" || hostAddress == "::1" || host.isLoopbackAddress

        val accepted = try {
            (isLocal || isOnLocalInterface(hostAddress)) && isPortAvailable(resolvedService.port)
        } catch (e: Exception) {
            // Interface enumeration and the port probe can throw, and this runs on a
            // main-thread callback where an escape takes the app down. A probe that fails
            // is a rejection, not a crash.
            Timber.tag(TAG).w(e, "probe of $key failed")
            false
        }

        if (running && accepted) {
            serviceName = resolvedService.serviceName
            resolvedHost = if (isLocal) "127.0.0.1" else hostAddress
            resolveAttempts.remove(resolvedService.serviceName)
            observer.onChanged(resolvedService.port)
        } else {
            forget(sighting)
            if (running && ShizukuSettings.isAutoReconnectMdnsEnabled() && attempts < 5 && !restartScheduled) {
                attempts++
                restartScheduled = true
                val delayMs = attempts * 1000L
                restartJob = mdnsScope.launch {
                    delay(delayMs)
                    if (registered) {
                        try {
                            nsdManager.stopServiceDiscovery(listener)
                        } catch (e: IllegalArgumentException) {
                            Timber.tag(TAG).e(e, "listener not registered when restarting service discovery")
                        }
                    }
                    delay(100L)
                    if (!registered) nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                    restartScheduled = false
                }
            }
        }
    }

    private fun isOnLocalInterface(hostAddress: String): Boolean =
        NetworkInterface.getNetworkInterfaces().asSequence().any { networkInterface ->
            networkInterface.inetAddresses.asSequence().any { hostAddress == it.hostAddress }
        }

    private fun isPortAvailable(port: Int) = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.tag(TAG).v("onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Timber.tag(TAG).v("onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Timber.tag(TAG).v("onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Timber.tag(TAG).v("onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
        private val sighting: NsdServiceInfo
    ) : NsdManager.ResolveListener {

        // The callback hands back a copy of the submitted record, not the instance itself, so
        // "is this still the entry I recorded?" could not be answered from the callback alone.
        // Passing the remembered sighting keeps that comparison meaningful.
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            adbMdns.onResolveFailed(sighting, errorCode)
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(sighting, nsdServiceInfo)
        }

    }

    companion object {
        private const val MAX_RESOLVE_ATTEMPTS = 3

        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}
