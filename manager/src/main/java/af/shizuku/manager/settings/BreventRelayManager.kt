package af.shizuku.manager.settings

import android.content.Context
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import af.shizuku.manager.R

/**
 * External relay activation for Brevent (黑阈, me.piebridge.brevent).
 *
 * Brevent does not declare any Shizuku permission, so it can never appear in the authorized-apps
 * list and a plain authorization grant is meaningless for it. Its activation is a pure shell
 * operation: run `/data/local/tmp/brevent.sh`, which copies libbrevent.so and leaves the
 * brevent_daemon/brevent_server resident. ShizukuX's own service runs at shell level, so it acts
 * as a middle-man exactly like [SceneRelayManager] does for Scene — the user no longer needs
 * wireless debugging / a PC to (re)activate Brevent after every reboot.
 */
object BreventRelayManager {

    /** Brevent's official package. */
    private const val BREVENT_PACKAGE = "me.piebridge.brevent"

    /** Activation script Brevent writes to /data/local/tmp once the user has opened the app. */
    private const val BREVENT_SCRIPT = "/data/local/tmp/brevent.sh"

    /** Resident process names as shown by ps (argv[0]); do NOT use pgrep -x: the daemon's comm
     *  is "brevent" and the server's comm is "main" on this binary, so an exact pgrep never hits. */

    fun activateBrevent(context: Context, scope: CoroutineScope, silent: Boolean = false) {
        if (!Shizuku.pingBinder()) {
            toastOnMain(context, R.string.scene_relay_shizuku_not_running)
            return
        }
        val installed = try {
            context.packageManager.getPackageInfo(BREVENT_PACKAGE, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            toastOnMain(context, R.string.brevent_relay_brevent_not_installed)
            return
        }

        toastOnMain(context, R.string.external_relay_brevent_activating, Toast.LENGTH_SHORT)
        scope.launch(Dispatchers.IO) {
            try {
                // 0) The script only exists after the user opened Brevent once; surface that instead
                //    of a confusing "not found" failure later on. Like Scene, never call
                //    waitFor()/exitValue() on the remote process — on some OEM builds
                //    (OPPO/Android 16) ShizukuProcess.exitValue() throws IllegalArgumentException
                //    and rikka's waitFor(timeout) does not catch it; reading the stream to EOF is
                //    enough here.
                val scriptOk = try {
                    val ls = Shizuku.newProcess(arrayOf("sh", "-c", "ls $BREVENT_SCRIPT"), null, "/data/local/tmp")
                    ls.inputStream.bufferedReader().use { it.readText() }.contains("brevent.sh")
                } catch (e: Throwable) {
                    false
                }
                if (!scriptOk) {
                    withContext(Dispatchers.Main) {
                        showResult(context, R.string.brevent_relay_result_title, context.getString(R.string.brevent_relay_script_missing), silent)
                    }
                    return@launch
                }

                // 1) Already resident? Don't spawn a duplicate daemon.
                val existingPid = queryDaemonPid()
                if (existingPid.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        showResult(context, R.string.brevent_relay_result_title, context.getString(R.string.brevent_relay_already_running, existingPid), silent)
                    }
                    return@launch
                }

                // 2) Activate. Brevent's script ends in `exec $brevent` (a resident daemon), so it
                //    must be launched in the background — waitFor() would otherwise block forever
                //    (and on some OEM builds ShizukuProcess.waitFor()/exitValue() throws anyway).
                //    Draining stdout/stderr to EOF is sufficient proof the shell started.
                val cmd = "/system/bin/sh $BREVENT_SCRIPT >/dev/null 2>&1 &"
                val process = Shizuku.newProcess(
                    arrayOf("/system/bin/sh", "-c", cmd),
                    null,
                    "/data/local/tmp"
                )
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }

                // 3) Give the daemon a moment to go resident, then verify.
                delay(1000)
                val daemonPid = queryDaemonPid()
                val activated = daemonPid.isNotEmpty()

                withContext(Dispatchers.Main) {
                    val sb = StringBuilder()
                    sb.append(
                        if (activated) {
                            context.getString(R.string.brevent_relay_daemon_running, daemonPid)
                        } else {
                            context.getString(R.string.brevent_relay_failed)
                        }
                    )
                    showResult(context, R.string.brevent_relay_result_title, sb.toString(), silent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showResult(
                        context,
                        R.string.brevent_relay_result_title,
                        context.getString(R.string.brevent_relay_failed) + "\n\n" + (e.message ?: e.javaClass.simpleName),
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
     * Returns the first PID of a resident Brevent service, or an empty string if not running.
     * Matches ps output by argv[0] (brevent_daemon / brevent_server) and parses the PID column —
     * the comm-based pgrep -x path misses both daemons ("brevent" / "main").
     * Blocking IPC; call from an IO thread/coroutine, never the main thread.
     */
    fun queryDaemonPid(): String {
        return try {
            // The grep process is created after ps snapshots, so it never pollutes the output;
            // the wrapping sh has argv[0]="sh" and is filtered out by the regex below. The
            // stream is drained to EOF; no waitFor()/exitValue() — see activateBrevent.
            val verify = Shizuku.newProcess(
                arrayOf("sh", "-c", "ps -A | grep -E 'brevent_(daemon|server)'"), null, null
            )
            val vOut = verify.inputStream.bufferedReader().use { it.readText() }
            vOut.lines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                val name = parts.lastOrNull()
                if (parts.size >= 2 && (name == "brevent_daemon" || name == "brevent_server")) {
                    parts[1]
                } else {
                    null
                }
            }.firstOrNull().orEmpty()
        } catch (e: Throwable) {
            ""
        }
    }
}
