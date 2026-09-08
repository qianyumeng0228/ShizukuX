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
    private const val UP_SCRIPT = "$TMP/up.sh"
    private const val DAEMON_BIN = "$TMP/scene-daemon"
    private const val BUSYBOX_BIN = "$TMP/busybox"

    /** Entry paths inside Scene's APK that carry the activation payload. */
    private const val DAEMON_APK_ENTRY = "res/raw/daemon"
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
                // 0) Already resident? Don't respawn a duplicate daemon.
                val runningPid = queryDaemonPid()
                if (runningPid.isNotEmpty()) {
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
                //    copies scene-daemon to /data/local/tmp and leaves it running in the
                //    background, which is what actually grants Scene its ADB permission.
                android.util.Log.w("SceneRelay", "about to call newProcess, pingBinder=${Shizuku.pingBinder()}")
                val process = try {
                    Shizuku.newProcess(
                        arrayOf("/system/bin/sh", "-c", "/system/bin/sh $UP_SCRIPT"),
                        null,
                        // cwd 设为脚本所在目录：up.sh 内部用相对路径 ./busybox，且需避免
                        // Runtime.exec 按 PATH 找到 APEX 受限 sush（不支持 [[ ]] 等语法）导致 syntax error。
                        TMP
                    )
                } catch (e: Throwable) {
                    android.util.Log.w("SceneRelay", "newProcess threw: ${e.javaClass.simpleName}: ${e.message}", e)
                    throw e
                }
                android.util.Log.w("SceneRelay", "newProcess returned: $process")
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                process.waitFor()
                val output = (stdout + stderr).trim()
                android.util.Log.w("SceneRelay", "up.sh output: $output")
                val ok = stdout.contains("Scene-Daemon OK")

                // 3) Verify the daemon actually went resident (the real source of ADB permission).
                val daemonPid = queryDaemonPid()
                val activated = ok || daemonPid.isNotEmpty()

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

        // b) Extract scene-daemon from res/raw/daemon.
        val out1 = runShell("unzip -p \"$apk\" $DAEMON_APK_ENTRY > $DAEMON_BIN && chmod 777 $DAEMON_BIN")
        // c) Extract busybox from assets/toolkit/busybox.
        val out2 = runShell("unzip -p \"$apk\" $BUSYBOX_APK_ENTRY > $BUSYBOX_BIN && chmod 777 $BUSYBOX_BIN")

        // d) Verify the two payload files actually landed.
        val ls = runShell("ls -l $DAEMON_BIN $BUSYBOX_BIN")
        if (!ls.contains("scene-daemon") || !ls.contains("busybox")) {
            android.util.Log.w("SceneRelay", "prepareActivationFiles: extract failed. ls=$ls out1=$out1 out2=$out2")
            return context.getString(R.string.scene_relay_prepare_failed) + "\n\n" + (ls + out1 + out2).trim()
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
            val w = Shizuku.newProcess(arrayOf("sh", "-c", "cat > $UP_SCRIPT && chmod 777 $UP_SCRIPT"), null, TMP)
            w.outputStream.write(upShContent.toByteArray(Charsets.UTF_8))
            w.outputStream.flush()
            w.outputStream.close()
            val wOut = w.inputStream.bufferedReader().use { it.readText() }
            val wErr = w.errorStream.bufferedReader().use { it.readText() }
            w.waitFor()
            android.util.Log.w("SceneRelay", "write up.sh: out=$wOut err=$wErr")
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "prepareActivationFiles: write up.sh failed: $e")
            return context.getString(R.string.scene_relay_prepare_failed)
        }
        return null
    }

    /** Runs a command through a Shizuku shell process and returns its combined output. */
    private fun runShell(cmd: String): String {
        return try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, TMP)
            val out = p.inputStream.bufferedReader().use { it.readText() }
            val err = p.errorStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out + err
        } catch (e: Throwable) {
            android.util.Log.w("SceneRelay", "runShell failed: cmd=$cmd err=${e.message}")
            ""
        }
    }

    /** First PID of a resident scene-daemon, or an empty string when not running. Blocking IPC;
     *  call from an IO thread/coroutine, never the main thread. */
    fun queryDaemonPid(): String {
        return try {
            val verify = Shizuku.newProcess(
                arrayOf("sh", "-c", "pgrep -f scene-daemon"), null, null
            )
            val vOut = verify.inputStream.bufferedReader().use { it.readText() }
            verify.waitFor()
            vOut.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        } catch (e: Throwable) {
            ""
        }
    }
}
