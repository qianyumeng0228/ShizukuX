package af.shizuku.manager.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
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

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isRunning) "Running" else "Stopped"
        tile.updateTile()
    }

    override fun onClick() {
        // Simple toggle logic or launch app
        if (qsTile.state == Tile.STATE_INACTIVE) {
            // Trigger start flow
            startActivityAndCollapse(android.content.Intent("af.shizuku.manager.action.START_SERVICE").apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            })
        }
    }
}
