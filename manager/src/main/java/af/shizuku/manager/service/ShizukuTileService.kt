package af.shizuku.manager.service

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import af.shizuku.manager.MainActivity
import af.shizuku.manager.utils.ShizukuStateMachine

class ShizukuTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING
        val isStarting = state == ShizukuStateMachine.State.STARTING

        tile.state = when {
            isRunning -> Tile.STATE_ACTIVE
            isStarting -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = when {
            isRunning -> "Shizuku: Active"
            isStarting -> "Starting..."
            else -> "Shizuku: Off"
        }
        tile.subtitle = when {
            isRunning -> "Running"
            isStarting -> "Please wait"
            else -> "Tap to Start"
        }
        tile.updateTile()
    }

    override fun onClick() {
        val state = ShizukuStateMachine.get()
        val isRunning = state == ShizukuStateMachine.State.RUNNING

        try {
            if (isRunning) {
                // If running, launch Main Dashboard to manage
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivityAndCollapse(intent)
            } else {
                // Attempt to start silently if root is available
                if (com.topjohnwu.superuser.Shell.isAppGrantedRoot() == true) {
                    ShizukuStateMachine.set(ShizukuStateMachine.State.STARTING)
                    updateTile()
                    com.topjohnwu.superuser.Shell.cmd(af.shizuku.manager.starter.Starter.internalCommand)
                        .submit {
                            ShizukuStateMachine.update()
                            updateTile()
                        }
                } else {
                    // Fallback to UI for ADB pairing/start
                    val intent = Intent("af.shizuku.manager.action.START_SERVICE").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivityAndCollapse(intent)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to launch Shizuku: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
