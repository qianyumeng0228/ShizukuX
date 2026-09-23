package af.shizuku.manager.devicecontrol

import android.service.quicksettings.Tile
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.widget.Toast
import af.shizuku.manager.R
import kotlin.concurrent.thread

/**
 * Quick Settings tile to toggle system animations (window/transition/animator scales).
 * Works in both ADB and root modes via Shizuku DeviceControl.
 * Tapping toggles between animations on (1.0x) and off (0.0x).
 */
class AnimationTileService : TileService() {
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

        thread(name = "anim-tile-toggle") {
            try {
                val ok = DeviceControlManager.setAnimations(target)
                if (ok) {
                    updateTileState(target)
                    val msg = if (target) R.string.device_control_animations_on
                              else R.string.device_control_animations_off
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.device_control_operation_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, R.string.device_control_service_not_available, Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    private fun refreshTile() {
        thread(name = "anim-tile-refresh") {
            try {
                val scale = DeviceControlManager.getSetting(
                    "global", "window_animation_scale"
                )?.toFloatOrNull() ?: 1f
                updateTileState(scale > 0f)
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
        tile.label = getString(R.string.device_control_animations_tile)
        tile.contentDescription = getString(
            if (enabled) R.string.device_control_animations_on
            else R.string.device_control_animations_off
        )
            tile.updateTile()
        }
    }
}
