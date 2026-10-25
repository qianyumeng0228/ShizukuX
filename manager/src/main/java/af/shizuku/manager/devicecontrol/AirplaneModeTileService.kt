package af.shizuku.manager.devicecontrol

import android.service.quicksettings.Tile
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.widget.Toast
import af.shizuku.manager.R
import kotlin.concurrent.thread

/**
 * Quick Settings tile to toggle airplane mode.
 * Reads current state from Settings and toggles via Shizuku DeviceControl.
 * Works in ADB mode (shell UID has WRITE_SECURE_SETTINGS).
 */
class AirplaneModeTileService : TileService() {
    @Volatile private var busy = false


    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        if (busy) return
        busy = true
        val tile = qsTile ?: run { busy = false; return }
        val currentlyOn = tile.state == Tile.STATE_ACTIVE
        val target = !currentlyOn

        thread(name = "airplane-tile") {
            try {
                val ok = DeviceControlManager.setAirplaneMode(target)
                if (ok) {
                    updateTileState(target)
                    showToast(
                        if (target) R.string.device_control_airplane_on else R.string.device_control_airplane_off
                    )
                } else {
                    showToast(R.string.device_control_operation_failed)
                }
            } catch (e: Exception) {
                // Toast cannot be constructed on a thread without a Looper (API < 30 throws
                // "Can't toast on a thread that has not called Looper.prepare()"); post it to
                // the main thread. The previous catch-block second Toast on this same bare
                // thread was itself throwing and killing the process.
                showToast(R.string.device_control_service_not_available)
            } finally {
                busy = false
            }
        }
    }

    /** Toast from a worker thread: always hop to the main looper first (API 24-29 Toast
     *  construction requires a Looper on the calling thread). */
    private fun showToast(resId: Int) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@AirplaneModeTileService, resId, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshTile() {
        thread(name = "airplane-tile_refresh") {
            try {
                val value = DeviceControlManager.getSetting("global", "airplane_mode_on")?.toIntOrNull() ?: 0
                updateTileState(value != 0)
            } catch (_: Exception) {
                // Shizuku not available — mark tile unavailable rather than falsely showing "on"
                val act = this
                Handler(Looper.getMainLooper()).post {
                    act.qsTile?.let { t ->
                        t.state = Tile.STATE_UNAVAILABLE
                        t.updateTile()
                    }
                }
            }
        }
    }

    private fun updateTileState(enabled: Boolean) {
        val act = this
        // Tile writes must happen on the main thread (some OEM SystemUIs discard
        // updates from background threads).
        Handler(Looper.getMainLooper()).post {
            val tile = act.qsTile ?: return@post
            tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.device_control_airplane_tile)
        tile.contentDescription = getString(if (enabled) R.string.device_control_airplane_on else R.string.device_control_airplane_off)
            tile.updateTile()
        }
    }
}
