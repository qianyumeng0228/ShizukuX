package af.shizuku.manager.home

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsPage

/**
 * Guide dialog shown when the device manufacturer restricts ADB permissions (Xiaomi "USB
 * debugging (secure settings)", OPPO permission monitor, etc.). Lists exactly which switches
 * to open, then jumps to the developer options page with USB debugging highlighted.
 */
fun Context.showAdbPermissionGuide() {
    val message = when {
        EnvironmentUtils.isXiaomi() -> getString(R.string.adb_permission_guide_xiaomi)
        EnvironmentUtils.isOppo() || EnvironmentUtils.isOnePlus() -> getString(R.string.adb_permission_guide_oppo)
        else -> getString(R.string.adb_permission_guide_generic)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.adb_permission_guide_title)
        .setMessage(message)
        .setPositiveButton(R.string.adb_permission_guide_go_developer) { _, _ ->
            SettingsPage.Developer.HighlightUsbDebugging.launch(this)
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}
