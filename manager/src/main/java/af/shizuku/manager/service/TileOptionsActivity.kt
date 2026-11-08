package af.shizuku.manager.service

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.MainActivity
import af.shizuku.manager.R
import af.shizuku.manager.starter.Starter
import af.shizuku.manager.receiver.ShizukuReceiverStarter
import af.shizuku.manager.utils.ShizukuStateMachine
import af.shizuku.manager.worker.AdbStartWorker
import androidx.work.WorkManager
import com.topjohnwu.superuser.Shell

/**
 * Transparent dialog activity opened when the user long-presses the Shizuku QS tile.
 * Presents context-sensitive options (Start / Stop / Restart / Open App) based on current state.
 */
class TileOptionsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The option sheet is a stock AlertDialog; without the overlay it renders in the default
        // grey M3 palette against the gradient pages behind it.
        af.shizuku.manager.utils.GlassSkin.applyIfEnabled(this, transparentWindow = true)
        showOptions()
    }

    private fun showOptions() {
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val isStopped = state == ShizukuStateMachine.State.STOPPED
            || state == ShizukuStateMachine.State.CRASHED
        val isTransient = state == ShizukuStateMachine.State.STARTING
            || state == ShizukuStateMachine.State.STOPPING

        val items: Array<String>
        val actions: List<() -> Unit>

        when {
            isRunning -> {
                items = arrayOf(
                    getString(R.string.tile_action_restart),
                    getString(R.string.tile_action_stop),
                    getString(R.string.tile_action_open_app)
                )
                actions = listOf(
                    { stopShizuku(); startShizuku() },
                    { stopShizuku() },
                    { openApp() }
                )
            }
            isStopped -> {
                items = arrayOf(
                    getString(R.string.tile_action_start),
                    getString(R.string.tile_action_open_app)
                )
                actions = listOf(
                    { startShizuku() },
                    { openApp() }
                )
            }
            isTransient -> {
                items = arrayOf(getString(R.string.tile_action_open_app))
                actions = listOf { openApp() }
            }
            else -> {
                openApp()
                return
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_name)
            .setItems(items) { _, which ->
                actions.getOrNull(which)?.invoke()
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startShizuku() {
        ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
        // Shell.isAppGrantedRoot() can block on a cold su spawn (1-5s); do it off the main
        // thread so the QS long-press dialog doesn't ANR.
        kotlin.concurrent.thread(name = "tile-options-root-probe") {
            val hasRoot = runCatching { Shell.isAppGrantedRoot() == true }.getOrDefault(false)
            if (hasRoot) {
                Shell.cmd(Starter.internalCommand).submit {
                    ShizukuStateMachine.update()
                }
            } else {
                runOnUiThread { AdbStartWorker.enqueue(this) }
            }
        }
    }

    private fun stopShizuku() {
        ShizukuStateMachine.markUserStopped()
        WorkManager.getInstance(this).cancelUniqueWork("adb_start_worker")
        ShizukuReceiverStarter.killServerProcess()
        kotlin.runCatching { rikka.shizuku.Shizuku.exit() }
        ShizukuStateMachine.set(ShizukuStateMachine.State.STOPPED)
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }
}
