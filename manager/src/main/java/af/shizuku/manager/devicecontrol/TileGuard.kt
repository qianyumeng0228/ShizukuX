package af.shizuku.manager.devicecontrol

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.widget.Toast
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

/**
 * Quick tiles sit on top of the keyguard, so anyone holding a locked phone can flip them —
 * airplane mode, brightness, rotation and animation scale among them. When the user turns on
 * `secure_quick_tiles`, a locked device refuses the change and says why. Reading state is
 * deliberately untouched: the tile keeps showing the truth and only the write waits.
 *
 * @return true when the caller must stop, i.e. the change was refused.
 */
internal fun TileService.refuseWhileLocked(): Boolean {
    if (!ShizukuSettings.isSecureQuickTilesEnabled()) return false
    val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
    if (!km.isKeyguardLocked) return false
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(this, R.string.tile_blocked_while_locked, Toast.LENGTH_SHORT).show()
    }
    return true
}
