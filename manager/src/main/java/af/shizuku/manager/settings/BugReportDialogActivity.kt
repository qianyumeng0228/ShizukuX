package af.shizuku.manager.settings

import android.os.Bundle
import af.shizuku.core.ui.AppActivity

// Extends AppActivity (not plain AppCompatActivity) so onApplyUserThemeResource/
// computeUserThemeKey actually run - see AdbPairingDialogActivity for the full explanation.
class BugReportDialogActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BugReportDialog().show(supportFragmentManager, "BugReportDialog")
    }
}
