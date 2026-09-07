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

    fun activateBrevent(context: Context, scope: CoroutineScope) {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(context, R.string.scene_relay_shizuku_not_running, Toast.LENGTH_LONG).show()
            return
        }
        val installed = try {
            context.packageManager.getPackageInfo(BREVENT_PACKAGE, 0)
            true
        } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) {
            Toast.makeText(context, R.string.brevent_relay_brevent_not_installed, Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(context, R.string.external_relay_brevent_activating, Toast.LENGTH_SHORT).show()
        scope.launch(Dispatchers.IO) {
            try {
                // 0) The script only exists after the user opened Brevent once; surface that instead
                //    of a confusing "not found" failure later on.
                val scriptOk = try {
                    val ls = Shizuku.newProcess(arrayOf("sh", "-c", "ls $BREVENT_SCRIPT"), null, "/data/local/tmp")
                    val out = ls.inputStream.bufferedReader().use { it.readText() }
                    ls.waitFor()
                    out.contains("brevent.sh")
                } catch (e: Throwable) {
                    false
                }
                if (!scriptOk) {
                    withContext(Dispatchers.Main) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.brevent_relay_result_title)
                            .setMessage(context.getString(R.string.brevent_relay_script_missing))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    return@launch
                }

                // 1) Already resident? Don't spawn a duplicate daemon.
                val existingPid = queryDaemonPid()
                if (existingPid.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(R.string.brevent_relay_result_title)
                            .setMessage(context.getString(R.string.brevent_relay_already_running, existingPid))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                    return@launch
                }

                // 2) Activate. Brevent's script ends in `exec $brevent` (a resident daemon), so it
                //    must be launched in the background — waitFor() would otherwise block forever.
                val cmd = "/system/bin/sh $BREVENT_SCRIPT >/dev/null 2>&1 &"
                val process = Shizuku.newProcess(
                    arrayOf("/system/bin/sh", "-c", cmd),
                    null,
                    "/data/local/tmp"
                )
                process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }
                process.waitFor()

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
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.brevent_relay_result_title)
                        .setMessage(sb.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(R.string.brevent_relay_result_title)
                        .setMessage(context.getString(R.string.brevent_relay_failed) + "\n\n" + (e.message ?: e.javaClass.simpleName))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    /**
     * Returns the first PID of a resident Brevent service, or an empty string if not running.
     * Matches ps output by argv[0] (brevent_daemon / brevent_server) and parses the PID column —
     * the comm-based pgrep -x path misses both daemons ("brevent" / "main").
     * Runs on the IO dispatcher (blocking IPC); callers must not touch the main thread.
     */
    private suspend fun queryDaemonPid(): String {
        return try {
            // The grep process is created after ps snapshots, so it never pollutes the output;
            // the wrapping sh has argv[0]="sh" and is filtered out by the regex below.
            val verify = Shizuku.newProcess(
                arrayOf("sh", "-c", "ps -A | grep -E 'brevent_(daemon|server)'"), null, null
            )
            val vOut = verify.inputStream.bufferedReader().use { it.readText() }
            verify.waitFor()
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
