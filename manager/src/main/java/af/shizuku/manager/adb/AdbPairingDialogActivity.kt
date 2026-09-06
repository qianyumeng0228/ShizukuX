package af.shizuku.manager.adb

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.utils.SettingsPage

// Extends AppActivity (not plain AppCompatActivity) so onApplyUserThemeResource/
// computeUserThemeKey actually run - without it, the manifest's Theme.App.DialogHost
// (declared with a hardcoded Dark parent, same as GrantPermissions/RequestPermissionActivity)
// renders literally dark instead of being rebased to the user's actual theme preference.
//
// This page is NOT a code-input dialog anymore. On most OEM skins (MIUI etc.) the system
// "Pair with device" pop-up ends the pairing session the moment it is dismissed — pulling
// down the shade or switching to another app kills it — so typing a remembered code later
// can never succeed. The page explains that constraint and offers the only reliable path:
// keep the pop-up open while Shizuku reads the code itself via the accessibility service.
class AdbPairingDialogActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port_number", -1)
        if (port == -1) {
            finish()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_adb_pairing_title)
            .setMessage(R.string.dialog_adb_pairing_legacy_guide)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setNeutralButton(R.string.dialog_adb_pairing_enable_auto) { _, _ ->
                // The accessibility service (auto-pair) must be switched on BEFORE the system
                // pairing pop-up is opened, otherwise opening Settings to enable it closes the
                // pop-up and with it the session. Launching the accessibility settings is the
                // right entry point here.
                SettingsPage.Accessibility.launch(this)
            }
            .setOnCancelListener { finish() }
            .show()
    }
}
