package af.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.RootCompatHelper
import af.shizuku.manager.service.LockedBootStartService
import af.shizuku.manager.service.WatchdogService
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.DeveloperRestoreWorker

class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val handled = when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> true
            else -> false
        }
        if (!handled) return

        Timber.tag("BootCompleteReceiver").i("Triggered by: $action")

        // Restore developer options / USB debugging / wireless debugging before attempting the
        // Shizuku auto-start below, so the worker can discover the wireless-debugging port.
        // MIUI/HyperOS force developer options off on boot; with WRITE_SECURE_SETTINGS we can
        // write it (and adb_enabled / adb_wifi_enabled) back. The first pass runs inline (the
        // writes are fast enough to survive goAsync()); the delayed retry plus the
        // "wireless debugging needs Wi-Fi" notification live in DeveloperRestoreWorker, because
        // a 15s coroutine delay here used to get cancelled when the system reclaimed the process.
        // Direct boot (LOCKED) is skipped: prefs are credential-encrypted and unavailable there.
        if (action == Intent.ACTION_BOOT_COMPLETED && ShizukuSettings.isAutoRestoreDeveloperOptionsEnabled()) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    if (DeveloperOptionsRestorer.restore(context)) {
                        DeveloperRestoreWorker.enqueue(context)
                    }
                } catch (e: Exception) {
                    Timber.tag("BootCompleteReceiver").w(e, "Auto-restore developer options failed")
                } finally {
                    pending.finish()
                }
            }
        }

        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            // Direct boot, before the first unlock: WorkManager cannot run (its database is in
            // credential-encrypted storage) and prefs are inaccessible, so the ordinary start
            // chain must NOT be touched here — a request would take the process down. A wireless
            // start needs only device-protected storage (settings + encrypted ADB key), the system
            // settings and the network, so it is made in the direct-boot-aware foreground service
            // instead. Everything else waits for BOOT_COMPLETED after the unlock.
            //
            // Note: no "manually stopped" check here. ShizukuX has no such persisted flag, and
            // loadPersistedSettledState() degrades to STOPPED while locked (prefs unreadable), so
            // a state-based check would be true on every cold boot and skip the start. This
            // matches the BOOT_COMPLETED path, which also always attempts when start-on-boot is on.
            val startWanted = ShizukuSettings.getStartOnBoot(context)
            val adbMethod = ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB
            if (startWanted && adbMethod) {
                LockedBootStartService.start(context)
            }
        } else {
            // rootStart() spawns the su shell synchronously (1-30 s). A manifest BroadcastReceiver's
            // onReceive runs on the main thread with a ~10s budget before the system reclaims the
            // process, so an inline start() blocked on su made root boot auto-start unreliable.
            // goAsync() + Dispatchers.IO runs the blocking start off the main thread and keeps the
            // receiver alive until it returns. Mirrors the developer-options / bridge branches above.
            //
            // v26 (Boot sync): no extra delayed-retry layer is needed here. start()'s ADB branch
            // now probes availability as `getAdbTcpPort() > 0` — the persist.adb.tcp.port property
            // survives reboots and is already readable at BOOT_COMPLETED, so a configured wireless
            // debugging is detected immediately even if Wi-Fi is not connected yet; AdbStartWorker
            // owns the readiness semantics (AWAITING_WIFI notification, live-port probe, mDNS
            // discovery, retries). Only a genuinely missing port falls through to the root fallback /
            // unavailable notification, matching the watchdog recovery semantics. (LOCKED_BOOT_COMPLETED
            // keeps its own direct-boot path above: rootStart cannot run before credential unlock.)
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ShizukuReceiverStarter.start(context)
                } catch (e: Exception) {
                    // LOCKED_BOOT_COMPLETED fires during direct boot, before credential-encrypted storage
                    // is available — WorkManager can't initialize and prefs may be inaccessible. This is
                    // expected; the later BOOT_COMPLETED (post-unlock) handles auto-start. Catch broadly so
                    // no variant crashes the receiver, and log at warn (breadcrumb, not a billed Sentry event).
                    Timber.tag("BootCompleteReceiver").w(e, "Auto-start skipped (service not ready, e.g. direct boot)")
                } finally {
                    pending.finish()
                }
            }
        }
        try {
            // The watchdog is not direct-boot aware, so before the first unlock the platform
            // cannot find it; start it only after BOOT_COMPLETED (the unlock has happened by
            // then). This mirrors Shizuku-Next c5eeafa (UserUnlockedStarter starts it at the
            // unlock) without needing a separate unlock receiver here.
            if (ShizukuSettings.getWatchdog() && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
                WatchdogService.start(context)
                // Exact alarms don't survive a reboot; re-arm the external watchdog (#415, #417).
                WatchdogAlarmReceiver.schedule(context)
            }
        } catch (e: Exception) {
            // startForegroundService can be refused during direct boot / from background — expected.
            Timber.tag("BootCompleteReceiver").w(e, "Watchdog start skipped")
        }
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED && ShizukuSettings.isSuBridgeEnabled()) {
            // The su/rish_shizuku.dex bridge deployed to /data/local/tmp is version-specific
            // bytecode compiled against this exact APK build; previously it was only ever
            // refreshed when the user manually reopened Root Compatibility settings, so an app
            // update left every existing SU Bridge user running a stale dex against the new
            // server until they happened to revisit that screen (#423 - "script" callers started
            // getting a null newProcess() result after updating). Re-deploy on every self-update
            // for users who already opted into the bridge; deployBridgeToTmp() no-ops safely if
            // Shizuku isn't up yet (best-effort, same as the starter/watchdog calls above).
            val appContext = context.applicationContext
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    RootCompatHelper.deployBridgeToTmp(appContext)
                } catch (e: Exception) {
                    Timber.tag("BootCompleteReceiver").w(e, "SU bridge redeploy skipped")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
