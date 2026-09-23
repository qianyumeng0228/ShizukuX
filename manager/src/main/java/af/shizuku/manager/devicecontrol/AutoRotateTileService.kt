package af.shizuku.manager.devicecontrol

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import af.shizuku.manager.R
import kotlin.concurrent.thread

/**
 * Quick Settings tile to toggle auto rotate.
 * Reads current state from Settings and toggles via Shizuku DeviceControl.
 * Works in ADB mode (shell UID has WRITE_SECURE_SETTINGS).
 */
class AutoRotateTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val tile = qsTile ?: return
        val currentlyOn = tile.state == Tile.STATE_ACTIVE
        val target = !currentlyOn

        thread(name = "autorotate-tile") {
            try {
                val ok = DeviceControlManager.setAutoRotate(target)
                if (ok) {
                    updateTileState(target)
                    Toast.makeText(
                        this@AutoRotateTileService,
                        if (target) R.string.device_control_autorotate_on else R.string.device_control_autorotate_off,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@AutoRotateTileService, R.string.device_control_operation_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@AutoRotateTileService, R.string.device_control_service_not_available, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshTile() {
        thread(name = "autorotate-tile_refresh") {
            try {
                val value = DeviceControlManager.getSetting("system", "accelerometer_rotation")?.toIntOrNull() ?: 1
                updateTileState(value != 0)
            } catch (_: Exception) {
                updateTileState(true)
            }
        }
    }

    private fun updateTileState(enabled: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.device_control_autorotate_tile)
        tile.contentDescription = getString(if (enabled) R.string.device_control_autorotate_on else R.string.device_control_autorotate_off)
        tile.updateTile()
    }
}
