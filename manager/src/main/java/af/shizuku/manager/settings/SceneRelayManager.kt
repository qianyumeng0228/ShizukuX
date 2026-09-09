package af.shizuku.manager.settings

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import af.shizuku.manager.R
import af.shizuku.manager.authorization.AuthorizationManager

/**
 * External relay authorization for apps that do not declare Shizuku permissions themselves.
 *
 * Currently only Scene (com.omarea.vtools) is supported: ShizukuX acts as a middle-man, running
 * Scene's official activation script (up.sh) through a Shizuku shell process so that scene-daemon
 * goes resident and Scene gains its ADB mode. The same entry point will be reused by future
 * relayed apps (see ExternalRelayActivity).
 *
 * Unlike Brevent, Scene never puts its activation script somewhere a plain shell can reach by
 * itself: its ADB-mode dialog only *shows* the command
 * `adb shell sh /storage/emulated/0/Android/data/.../up.sh`, while the actual up.sh lives inside
 * Scene's private data dir (not readable without root) and the shown path is never created on the
 * device. So instead of waiting for the script to exist, this manager *assembles* the activation
 * chain itself: it extracts scene-daemon (res/raw/daemon) and busybox (assets/toolkit/busybox)
 * from Scene's own APK into /data/local/tmp, writes the official up.sh template (bundled asset
 * scene_up.sh), and then runs it exactly like the official flow does.
 */
object SceneRelayManager {

    /** Scene's official package; the middle-man scheme only works against it (paths in up.sh are bound to this package name). */
    private const val SCENE_PACKAGE = "com.omarea.vtools"

    private const val TMP = "/data/local/tmp"

    // The whole activation chain lives in a dedicated sub-directory, never directly in $TMP:
    // up.sh resolves its own directory as the "origin" of scene-daemon and copies it to
    // /data/local/tmp/scene-daemon. If the script sat in $TMP, origin and target would be the
    // same file and `cp` would abort with "cp: ... is the same file", leaving the daemon dead.
    private const val SCENE_DIR = "$TMP/scene"
    private const val UP_SCRIPT = "$SCENE_DIR/up.sh"
    private const val DAEMON_BIN = "$SCENE_DIR/scene-daemon"   // origin (script dir)
    private const val BUSYBOX_BIN = "$SCENE_DIR/busybox"
    private const val DAEMON_TARGET = "$TMP/scene-daemon"      // final resident location

    /** Entry paths inside Scene's APK that carry the activation payload. The daemon's entry is
     *  resolved dynamically (its path changed across Scene versions); busybox still sits here. */
    private const val BUSYBOX_APK_ENTRY = "assets/toolkit/busybox"

    /** Official activation script template bundled with ShizukuX (assets/scene_up.sh). */
    private const val UP_SCRIPT_ASSET = "scene_up.sh"

    /**
     * Activate Scene's ADB mode via Shizuku middle-man.
     *
     * @param context host context (Activity or Fragment; an application context is fine too).
     * @param scope   coroutine scope tied to the caller's lifecycle.
     * @param silent  when true, results are surfaced as toasts instead of dialogs (used by the
     *                background auto-activation service, which has no window to host a dialog).
     */
    fun startSceneAdbActivation(context: Context, scope: CoroutineScope, silent: Boolean = false) {
        android.util.Log.w("SceneRelay", "startSceneAdbActivation called")

        try {
            android.util.Log.w("SceneRelay", "pingBinder=${Shizuku.pingBinder()} binder=${Shizuku.getBinder()}")
            try {
                android.util.Log.w("SceneRelay", "getUid=${Shizuku.getUid()}")
            } catch (e: Throwable) {
                android.util.Log.w("SceneRelay", "getUid threw: ${e.javaClass.simpleName}: ${e.message}")
            }
            try {
                android.util.Log.w("SceneRelay", "getVersion=${Shizuku.getVersion()}")
            } catch (e: Throwable) {
                android.util.Log.w("SceneRelay", "getVersion threw: ${e.javaClass.simpleName}: ${e.message}")
            }
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "binder introspection failed: $e")
        }

        if (!Shizuku.pingBinder()) {
            toastOnMain(context, R.string.scene_relay_shizuku_not_running)
            return
        }
        val sceneInstalled = try {
            context.packageManager.getPackageInfo(SCENE_PACKAGE, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        if (!sceneInstalled) {
            toastOnMain(context, R.string.scene_relay_scene_not_installed)
            return
        }

        toastOnMain(context, R.string.scene_relay_activating, Toast.LENGTH_SHORT)
        scope.launch(Dispatchers.IO) {
            try {
                // 0) Already resident? Don't respawn a duplicate daemon. But a resident process is
                //    not enough: Scene's ADB mode only works when scene-daemon is *listening* on
                //    its port (14754). A process that failed to bind (stale connection/TIME_WAIT
                //    still occupying the port) stays alive yet unreachable, which makes Scene show
                //    the "run this on your PC" dialog. Detect that and restart the daemon.
                val runningPid = queryDaemonPid()
                if (runningPid.isNotEmpty()) {
                    if (!awaitDaemonListening()) {
                        android.util.Log.w("SceneRelay", "daemon pid=$runningPid alive but 14754 not listening; restarting")
                        var recovered = false
                        repeat(3) {
                            if (restartDaemon()) { recovered = true; return@repeat }
                            Thread.sleep(1000)
                        }
                        if (recovered) {
                            val newPid = queryDaemonPid()
                            withContext(Dispatchers.Main) {
                                showResult(
                                    context,
                                    R.string.scene_relay_result_title,
                                    context.getString(R.string.scene_relay_daemon_restarted, newPid),
                                    silent
                                )
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                showResult(
                                    context,
                                    R.string.scene_relay_result_title,
                                    context.getString(R.string.scene_relay_listen_failed) + "\n\n" + listenDiagnosis(),
                                    silent
                                )
                            }
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        showResult(
                            context,
                            R.string.scene_relay_result_title,
                            context.getString(R.string.scene_relay_already_running, runningPid),
                            silent
                        )
                    }
                    return@launch
                }

                // 1) Assemble the activation chain. Brevent's script is on disk once the app has
                //    been opened once; Scene's never is, so extract it from Scene's own APK here.
                val prepareError = prepareActivationFiles(context)
                if (prepareError != null) {
                    withContext(Dispatchers.Main) {
                        showResult(context, R.string.scene_relay_result_title, prepareError, silent)
                    }
                    return@launch
                }

                // 2) Middle-man core: run Scene's official up.sh through a shell-level process
                //    (inherits the service UID, i.e. shell/ADB on a non-root start). The script
                //    copies scene-daemon to /data/local/tmp/scene-daemon and leaves it running in
                //    the background, which is what actually grants Scene its ADB permission.
                //    cwd is the scene dir so the script's relative paths (./busybox) resolve.
                //    stderr is merged into the stream (2>&1) so a chatty stderr cannot fill the
                //    pipe buffer and deadlock the read; we never call waitFor()/exitValue() —
                //    on some OEM builds (OPPO/Android 16) ShizukuProcess.exitValue() throws
                //    IllegalArgumentException which rikka's waitFor(timeout) does not catch.
                android.util.Log.w("SceneRelay", "about to call newProcess, pingBinder=${Shizuku.pingBinder()}")
                val process = try {
                    Shizuku.newProcess(
                        arrayOf("/system/bin/sh", "-c", "/system/bin/sh $UP_SCRIPT 2>&1"),
                        null,
                        SCENE_DIR
                    )
                } catch (e: Throwable) {
                    android.util.Log.w("SceneRelay", "newProcess threw: ${e.javaClass.simpleName}: ${e.message}", e)
                    throw e
                }
                android.util.Log.w("SceneRelay", "newProcess returned: $process")
                val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
                android.util.Log.w("SceneRelay", "up.sh output: $output")

                // 3) Verify the daemon actually went resident (the real source of ADB permission).
                //    The official script's own pgrep check is unreliable on some devices (fresh
                //    fork not yet visible, or a missing/odd pgrep), so wait briefly and use
                //    pidof/ps fallbacks instead of trusting "Scene-Daemon OK!" from the script.
                //    Then verify it is actually *listening* on 14754 — a resident but unbound
                //    daemon (port stolen by a stale connection) is invisible to Scene, which
                //    would keep showing its PC-instruction dialog. If so, bounce the daemon.
                var daemonPid = awaitDaemonPid()
                var activated = daemonPid.isNotEmpty()
                if (activated && !awaitDaemonListening()) {
                    android.util.Log.w("SceneRelay", "daemon pid=$daemonPid up but 14754 not listening; bouncing")
                    var recovered = false
                    repeat(3) {
                        if (restartDaemon()) { recovered = true; return@repeat }
                        Thread.sleep(1000)
                    }
                    if (recovered) {
                        // restartDaemon() already verified the respawn listens on 14754 — trust
                        // that over a fresh pid probe (pidof/pgrep can transiently miss).
                        activated = true
                        daemonPid = queryDaemonPid()
                    } else {
                        activated = false
                    }
                }

                // 4) On success, add Scene to ShizukuX's authorized apps list (updates the
                //    service-side permission flags).
                var sceneGranted = false
                if (activated) {
                    try {
                        val appInfo = context.packageManager.getApplicationInfo(SCENE_PACKAGE, 0)
                        val sceneUid = appInfo.uid
                        if (sceneUid > 0) {
                            AuthorizationManager.grant(SCENE_PACKAGE, sceneUid)
                            sceneGranted = AuthorizationManager.granted(SCENE_PACKAGE, sceneUid)
                            android.util.Log.w("SceneRelay", "grant scene uid=$sceneUid granted=$sceneGranted")
                        }
                    } catch (e: Throwable) {
                        android.util.Log.w("SceneRelay", "grant scene failed: ${e.message}")
                    }
                }

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.append(
                        if (activated) {
                            if (daemonPid.isNotEmpty()) {
                                context.getString(R.string.scene_relay_daemon_running, daemonPid)
                            } else {
                                context.getString(R.string.scene_relay_success)
                            }
                        } else {
                            context.getString(R.string.scene_relay_failed)
                        }
                    )
                    if (activated && sceneGranted) {
                        sb.append("\n").append(context.getString(R.string.scene_relay_granted))
                    } else if (activated) {
                        sb.append("\n").append(context.getString(R.string.scene_relay_grant_failed))
                    }
                    if (output.isNotEmpty()) {
                        sb.append("\n\n").append(output)
                    }
                    showResult(context, R.string.scene_relay_result_title, sb.toString(), silent)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled (screen closed / caller stopped). Do not surface a fake
                // "activation failed" dialog on a dead activity — propagate the cancel.
                android.util.Log.w("SceneRelay", "startSceneAdbActivation cancelled")
                throw e
            } catch (e: Exception) {
                android.util.Log.w("SceneRelay", "startSceneAdbActivation catch: ${e.javaClass.simpleName}: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showResult(
                        context,
                        R.string.scene_relay_result_title,
                        context.getString(R.string.scene_relay_failed) + "\n\n" + (e.message ?: e.javaClass.simpleName),
                        silent
                    )
                }
            }
        }
    }

    /**
     * Surfaces a relay result either as a dialog (interactive callers) or as a toast
     * (silent/background callers from the accessibility auto-activation service).
     */
    private fun showResult(context: Context, titleRes: Int, message: String, silent: Boolean) {
        if (silent) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } else {
            MaterialAlertDialogBuilder(context)
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    /** Shows a toast on the main thread — safe to call from any caller thread (accessibility
     *  service coroutines included). */
    private fun toastOnMain(context: Context, resId: Int, length: Int = Toast.LENGTH_LONG) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, resId, length).show()
        }
    }

    /**
     * Prepares Scene's activation chain in /data/local/tmp: resolves Scene's APK, extracts
     * scene-daemon + busybox from it, and writes the official up.sh template.
     *
     * @return null on success, or a user-facing error message (with detail) on failure.
     */
    private fun prepareActivationFiles(context: Context): String? {
        // a) Locate Scene's APK.
        val apk = runShell("pm path $SCENE_PACKAGE | sed 's/package://' | head -1").trim()
        if (apk.isEmpty()) {
            android.util.Log.w("SceneRelay", "prepareActivationFiles: apk not found")
            return context.getString(R.string.scene_relay_apk_not_found)
        }
        android.util.Log.w("SceneRelay", "prepareActivationFiles: apk=$apk")

        // a2) Fresh scene dir — the previous run may have left a stale or half-written chain.
        runShell("rm -rf $SCENE_DIR && mkdir -p $SCENE_DIR && chmod 777 $SCENE_DIR")

        // a3) Scene's own runtime artifacts take precedence. Newer Scene builds (N1 2026.09+)
        //     no longer bundle the daemon inside the APK — the app writes up.sh + daemon +
        //     busybox into its external files dir at runtime, and the ADB-mode dialog simply
        //     asks the user to run that up.sh. If it exists, reuse it (it knows its own
        //     daemon path); we only mirror the files into our scene dir because the run step
        //     executes up.sh with cwd=SCENE_DIR and the script resolves paths via dirname $0.
        val externalUp = findExternalUp()
        if (externalUp != null) {
            android.util.Log.w("SceneRelay", "prepareActivationFiles: Scene official up.sh found: $externalUp")
            val extDir = externalUp.substringBeforeLast('/')
            // Mirror the daemon (any plausible native binary >1MB next to up.sh) into our dir.
            val extDaemon = runShell("find \"$extDir\" -maxdepth 2 -type f -size +1M 2>/dev/null | grep -v busybox | head -1").trim()
            if (extDaemon.isNotEmpty()) {
                runShell("cp \"$extDaemon\" $DAEMON_BIN && chmod 777 $DAEMON_BIN")
            }
            // Busybox: prefer Scene's own copy next to up.sh, else extract from the APK.
            if (runShell("test -f \"$extDir/busybox\" && echo YES").trim() == "YES") {
                runShell("cp \"$extDir/busybox\" $BUSYBOX_BIN && chmod 777 $BUSYBOX_BIN")
            } else {
                runShell("unzip -p \"$apk\" $BUSYBOX_APK_ENTRY > $BUSYBOX_BIN 2>/dev/null; chmod 777 $BUSYBOX_BIN")
            }
            // Use Scene's official script instead of our bundled template.
            runShell("cp \"$externalUp\" $UP_SCRIPT && chmod 777 $UP_SCRIPT")
            val daemonBytes = runShell("wc -c < $DAEMON_BIN 2>/dev/null").trim().toLongOrNull() ?: 0L
            if (daemonBytes >= 1_000_000) {
                android.util.Log.w("SceneRelay", "prepareActivationFiles: external artifacts ready (daemon=${daemonBytes}B)")
                return null
            }
            android.util.Log.w("SceneRelay", "prepareActivationFiles: external up.sh had no usable daemon (${daemonBytes}B); falling back to APK extraction")
        }

        // a4) APK extraction (older Scene builds bundle the daemon). A hardcoded entry is
        //     catastrophic: unzip -p on a missing entry prints nothing yet the redirect still
        //     creates a 0-byte file, which the old name-only check passed — leaving a 0-byte
        //     scene-daemon that exits instantly (trial exit 0, no bind) and Scene keeps showing
        //     its PC-instruction dialog. Enumerate instead and pick the largest plausible entry.
        val daemonEntry = resolveDaemonEntry(apk)
        if (daemonEntry == null) {
            val listing = runShell("unzip -l \"$apk\" 2>/dev/null | grep -iE 'daemon|scene' | head -20")
            android.util.Log.w("SceneRelay", "prepareActivationFiles: no daemon entry. listing:\n$listing")
            return context.getString(R.string.scene_relay_prepare_failed) + "\n\nAPK entries (daemon|scene):\n" + listing.trim()
        }
        android.util.Log.w("SceneRelay", "prepareActivationFiles: daemon entry = $daemonEntry")

        // b) Extract scene-daemon from the resolved entry (origin inside the scene dir).
        val out1 = runShell("unzip -p \"$apk\" \"$daemonEntry\" > $DAEMON_BIN && chmod 777 $DAEMON_BIN")
        // c) Extract busybox from assets/toolkit/busybox.
        val out2 = runShell("unzip -p \"$apk\" $BUSYBOX_APK_ENTRY > $BUSYBOX_BIN && chmod 777 $BUSYBOX_BIN")

        // d) Verify the payload actually landed non-empty. The daemon is a multi-MB native
        //    binary and busybox ~1.5MB; a 0-byte file means extraction failed (wrong entry
        //    or APK layout) and would make activation fail invisibly later — catch it here
        //    instead of shipping a dead daemon to the runtime.
        val daemonBytes = runShell("wc -c < $DAEMON_BIN 2>/dev/null").trim().toLongOrNull() ?: 0L
        val busyboxBytes = runShell("wc -c < $BUSYBOX_BIN 2>/dev/null").trim().toLongOrNull() ?: 0L
        if (daemonBytes < 1_000_000 || busyboxBytes < 500_000) {
            android.util.Log.w("SceneRelay", "prepareActivationFiles: extract failed (daemon=${daemonBytes}B busybox=${busyboxBytes}B). out1=$out1 out2=$out2")
            return context.getString(R.string.scene_relay_prepare_failed) +
                "\n\ndaemon=${daemonBytes}B busybox=${busyboxBytes}B (need daemon>=1MB, busybox>=500KB)\n" + (out1 + out2).trim()
        }

        // e) Write the official up.sh via the shell process stdin (avoids quoting/escaping issues
        //    and keeps the script byte-identical to Scene's).
        val upShContent = try {
            context.assets.open(UP_SCRIPT_ASSET).bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "prepareActivationFiles: read asset failed: $e")
            return context.getString(R.string.scene_relay_prepare_failed)
        }
        try {
            val w = Shizuku.newProcess(arrayOf("sh", "-c", "cat > $UP_SCRIPT && chmod 777 $UP_SCRIPT 2>&1"), null, SCENE_DIR)
            w.outputStream.write(upShContent.toByteArray(Charsets.UTF_8))
            w.outputStream.flush()
            w.outputStream.close()
            val wOut = w.inputStream.bufferedReader().use { it.readText() }
            try { w.waitFor() } catch (e: Throwable) { /* exit code optional */ }
            android.util.Log.w("SceneRelay", "write up.sh: out=$wOut")
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "prepareActivationFiles: write up.sh failed: $e")
            return context.getString(R.string.scene_relay_prepare_failed)
        }
        return null
    }

    /**
     * Locates Scene's official up.sh, which the app writes into its external files dir at
     * runtime (shell-readable). Common locations are probed first (cheap `test -f`), then a
     * bounded find as a last resort. Returns the first hit or null.
     */
    private fun findExternalUp(): String? {
        val base = "/sdcard/Android/data/$SCENE_PACKAGE"
        val candidates = listOf(
            "$base/files/up.sh",
            "$base/cache/up.sh",
            "$base/up.sh",
            "$base/files/cache/up.sh"
        )
        for (c in candidates) {
            if (runShell("test -f \"$c\" && echo YES").trim() == "YES") return c
        }
        val found = runShell("find \"$base\" -maxdepth 5 -name 'up.sh' 2>/dev/null | head -1").trim()
        return found.ifEmpty { null }
    }

    /**
     * Finds the scene-daemon binary inside Scene's APK. Newer Scene builds moved the
     * daemon out of res/raw/daemon (assets/toolkit/daemon, per-ABI raw names, ...), so
     * the entry is enumerated instead of hardcoded and the largest plausible entry wins
     * (the native daemon is ~2.2MB while helper scripts are tiny).
     *
     * unzip -l column order (toybox/Info-ZIP): Length Date Time Name — awk prints
     * "size name", then we filter for names containing "daemon", drop doc/sidecar
     * suffixes, sort by size descending and return the biggest >=1MB match (falling
     * back to the first plausible name when nothing is that large).
     *
     * @return the entry path (e.g. "res/raw/daemon"), or null when nothing plausible exists.
     */
    private fun resolveDaemonEntry(apk: String): String? {
        val listing = runShell(
            "unzip -l \"$apk\" 2>/dev/null | awk '{print \$1, \$4}' | grep -iE ' daemon' | " +
                "grep -viE '\\.(bak|log|txt|md|sh|json|xml)\$' | sort -rn | head -10"
        )
        var fallback: String? = null
        for (line in listing.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val size = parts[0].toLongOrNull() ?: 0L
            val name = parts[1]
            if (name.contains("daemon", ignoreCase = true)) {
                if (fallback == null) fallback = name
                if (size >= 1_000_000) return name
            }
        }
        return fallback
    }

    /** Runs a command through a Shizuku shell process and returns its combined output.
     *  stderr is merged (2>&1) to avoid pipe-buffer deadlock; waitFor/exitValue are never used —
     *  on OPPO/Android 16 ShizukuProcess.exitValue() throws IllegalArgumentException which rikka's
     *  waitFor(timeout) does not catch. The merged stream reaching EOF is the exit signal. */
    private fun runShell(cmd: String): String {
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd + " 2>&1"), null, TMP)
            p.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "runShell failed: cmd=$cmd err=${e.message}")
            ""
        }
    }

    /**
     * First PID of a resident scene-daemon, or an empty string when not running. Blocking IPC;
     *  call from an IO thread/coroutine, never the main thread.
     *
     * Probes are chained: `pidof` (toybox) first, then `pgrep -f`, then a raw `ps -A` grep as a
     * final fallback. Some OEMs ship a pgrep that returns nothing for a freshly-forked process or
     * is missing entirely, and the daemon may take a moment to show up after nohup, so a single
     * probe is not enough — callers should retry with a short delay (see the activation flow).
     */
    fun queryDaemonPid(): String {
        return try {
            val verify = Shizuku.newProcess(
                arrayOf(
                    "sh", "-c",
                    "pidof scene-daemon 2>/dev/null; " +
                        // Exclude our own shell ($$): some OEM pgrep -f matches the ancestor
                        // shell whose cmdline contains "scene-daemon", which would otherwise
                        // report a daemon that does not exist.
                        "pgrep -f scene-daemon 2>/dev/null | grep -vw \$\$; " +
                        "ps -A 2>/dev/null | grep scene-daemon | grep -v grep | awk '{print $2}' | head -1"
                ), null, null
            )
            val vOut = verify.inputStream.bufferedReader().use { it.readText() }
            try { verify.waitFor() } catch (e: Throwable) { /* exit code optional */ }
            vOut.lines().firstOrNull { it.isNotBlank() }?.trim()?.split(' ')?.first()?.orEmpty().orEmpty()
        } catch (e: Throwable) {
            ""
        }
    }

    /** Waits for scene-daemon to show up after activation (nohup spawns it async and some
     *  devices are slow to reflect the new process). Returns its PID or empty after [attempts]. */
    private fun awaitDaemonPid(attempts: Int = 4): String {
        var pid = ""
        repeat(attempts) {
            pid = queryDaemonPid()
            if (pid.isNotEmpty()) return pid
            Thread.sleep(700)
        }
        return pid
    }

    /**
     * True when scene-daemon is actually listening on 127.0.0.1:14754 (0x39A2), the port Scene
     * connects to for its ADB mode. `cat /proc/net/tcp{,6}` works on every Android shell and is
     * more reliable than ss/netstat, which OEM builds often drop.
     */
    private fun isDaemonListening(): Boolean {
        return try {
            val p = Shizuku.newProcess(
                arrayOf(
                    "sh", "-c",
                    "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i 39A2 | grep -q ' 0A ' && echo YES || echo NO"
                ), null, null
            )
            val out = p.inputStream.bufferedReader().use { it.readText() }
            try { p.waitFor() } catch (e: Throwable) { /* exit code optional */ }
            out.contains("YES")
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "isDaemonListening failed: ${e.message}")
            false
        }
    }

    /** Waits (polling) until the daemon starts listening on 14754, or [attempts] polls expire. */
    private fun awaitDaemonListening(attempts: Int = 4): Boolean {
        repeat(attempts) {
            if (isDaemonListening()) return true
            Thread.sleep(700)
        }
        return false
    }

    /**
     * Bounces scene-daemon: kill it (releasing 14754 — the stale connection that caused the
     * failed bind dies with the process), give the port a moment to settle, then respawn it
     * with nohup exactly like up.sh does. Returns true when the respawn listens on 14754.
     */
    private fun restartDaemon(): Boolean {
        // mkdir first: this path is also reached from the already-running branch, where a stale
        // daemon may exist while /data/local/tmp/scene was cleared (fresh reboot, manual rm).
        runShell("mkdir -p $SCENE_DIR; kill \$(pidof scene-daemon) 2>/dev/null; sleep 1")
        runShell("nohup $DAEMON_TARGET > $SCENE_DIR/daemon.log 2>&1 &")
        val pid = awaitDaemonPid()
        if (pid.isEmpty()) return false
        return awaitDaemonListening()
    }

    /** Human-readable port state for the failure dialog / diagnostics export. */
    private fun listenDiagnosis(): String {
        val raw = runShell("cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i 39A2")
        return "daemon pid: ${queryDaemonPid().ifEmpty { "(none)" }}\n\n14754 port state:\n" +
            raw.ifEmpty { "(no 14754 entries — daemon never bound the port)" }
    }
}
