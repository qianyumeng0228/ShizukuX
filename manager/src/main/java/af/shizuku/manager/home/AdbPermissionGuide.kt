package af.shizuku.manager.home

import android.app.Activity
import android.content.Context
import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsPage
import rikka.core.content.asActivity
import rikka.html.text.HtmlCompat

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

/**
 * One dialog for every "the server could not grant the OS permission" path: the request
 * prompt, the management list toggle/long-press, and the swipe action. Keeping a single
 * implementation means the OPPO-specific explanation cannot drift from the generic one.
 */
fun Context.showAdbLimitedDialog() {
    // MaterialAlertDialogBuilder requires an Activity theme; callers hand us a View context.
    val host = asActivity<Activity>() ?: this
    val oppo = EnvironmentUtils.isOppo() || EnvironmentUtils.isOnePlus()
    val message = if (oppo) {
        host.getString(R.string.app_management_dialog_adb_is_limited_oppo)
    } else {
        host.getString(R.string.app_management_dialog_adb_is_limited_message, Helps.ADB.get())
    }
    val dialog = MaterialAlertDialogBuilder(host)
        .setTitle(R.string.app_management_dialog_adb_is_limited_title)
        .setMessage(message.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE))
        .setPositiveButton(android.R.string.ok, null)
        .create()
    dialog.setOnShowListener {
        (it as AlertDialog).findViewById<TextView>(android.R.id.message)?.movementMethod =
            LinkMovementMethod.getInstance()
    }
    runCatching { dialog.show() }
}
