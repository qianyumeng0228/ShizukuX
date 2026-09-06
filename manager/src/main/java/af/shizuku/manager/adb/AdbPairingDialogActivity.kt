package af.shizuku.manager.adb

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.utils.SettingsPage

// Extends AppActivity (not plain AppCompatActivity) so onApplyUserThemeResource/
// computeUserThemeKey actually run - without it, the manifest's Theme.App.DialogHost
// (declared with a hardcoded Dark parent, same as GrantPermissions/RequestPermissionActivity)
// renders literally dark instead of being rebased to the user's actual theme preference.
class AdbPairingDialogActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port_number", -1)
        if (port == -1) {
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val margin = (24 * density).toInt()

        // The 6-digit code is shown in the system's "Pair with device" pop-up, which most OEM
        // skins dismiss as soon as the user pulls down the notification shade. Guide the user to
        // reopen it (or re-generate a code) from wireless-debugging settings instead of leaving
        // them staring at an empty input field.
        val guide = TextView(this).apply {
            text = getString(R.string.dialog_adb_pairing_guide)
            textSize = 13f
            setLineSpacing(0f, 1.2f)
            setPadding(margin, 0, margin, 0)
        }

        val editText = EditText(this).apply {
            hint = getString(R.string.dialog_adb_pairing_paring_code)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, (8 * density).toInt(), margin, 0) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, margin / 2, 0, 0)
            addView(guide)
            addView(editText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_adb_pairing_service_found_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = editText.text?.toString()?.trim() ?: ""
                if (code.isNotEmpty()) {
                    startForegroundService(AdbPairingService.dialogReplyIntent(this, port, code))
                }
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setNeutralButton(R.string.dialog_adb_pairing_open_settings) { _, _ ->
                // Reopen wireless-debugging settings so the user can tap "Pair device with
                // pairing code" again and read the freshly generated code. Keep this dialog
                // alive so they can come straight back and type it in.
                openWirelessDebuggingSettings()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun openWirelessDebuggingSettings() {
        // Reuses the project's multi-ROM launcher (MIUI/HyperOS special-casing, fragment
        // highlight fallback, developer-options fallback) instead of a raw Settings action.
        SettingsPage.Developer.WirelessDebugging.launch(this)
    }
}
