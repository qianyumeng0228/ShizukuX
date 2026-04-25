package af.shizuku.manager.settings

import android.os.Bundle
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.preference.Preference
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.CrashReporter
import af.shizuku.manager.utils.CustomTabsHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutSettingsFragment : BaseSettingsFragment() {

    private var versionClickCount = 0

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val context = requireContext()

        findPreference<Preference>("manual_report")?.apply {
            val hasLastCrash = af.shizuku.manager.utils.CrashHandler.getLastCrashReport(context) != null
            if (hasLastCrash) {
                setTitle("Report Last Crash")
                setSummary("A persistent crash was detected. Tap to generate a report with the saved stacktrace.")
            }

            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(if (hasLastCrash) "Report Last Crash" else context.getString(R.string.manual_report_title))
                    .setMessage(R.string.sentry_offline_notice_learn_more)
                    .setPositiveButton(R.string.manual_report_button_generate) { _, _ ->
                        val report = CrashReporter.generateReport(context)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Crash Report", report))
                        Toast.makeText(context, R.string.manual_report_toast_copied, Toast.LENGTH_SHORT).show()
                        
                        // Offer to clear
                        if (hasLastCrash) {
                            MaterialAlertDialogBuilder(context)
                                .setTitle("Clear Saved Crash?")
                                .setMessage("Now that the report is copied, would you like to clear the saved crash data?")
                                .setPositiveButton("Clear") { _, _ ->
                                    af.shizuku.manager.utils.CrashHandler.clearLastCrash(context)
                                }
                                .setNegativeButton("Keep", null)
                                .show()
                        }
                        
                        // Offer to open GitHub or Share as File
                        MaterialAlertDialogBuilder(context)
                            .setTitle("Report Copied")
                            .setMessage("The report has been copied to your clipboard. Would you like to open GitHub Issues now, or share the report as a file instead?")
                            .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                                CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/thejaustin/ShizukuPlus/issues/new")
                            }
                            .setNeutralButton("Share File") { _, _ ->
                                af.shizuku.manager.utils.CrashReporter.shareAsFile(context)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    .setNeutralButton(R.string.manual_report_button_github) { _, _ ->
                        CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/thejaustin/ShizukuPlus/issues/new")
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        findPreference<Preference>("version")?.apply {
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            setOnPreferenceClickListener {
                if (ShizukuSettings.isVectorEnabled()) {
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    return@setOnPreferenceClickListener true
                }
                
                versionClickCount++
                if (versionClickCount >= 7) {
                    ShizukuSettings.setVectorEnabled(true)
                    Toast.makeText(context, R.string.settings_developer_options_revealed, Toast.LENGTH_SHORT).show()
                    versionClickCount = 0
                } else if (versionClickCount > 2) {
                    Toast.makeText(context, context.getString(R.string.settings_developer_options_click_more, 7 - versionClickCount), Toast.LENGTH_SHORT).show()
                }
                true
            }
        }

        findPreference<Preference>("source_code")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(context, "https://github.com/thejaustin/ShizukuPlus")
            true
        }

        findPreference<Preference>("open_source_licenses")?.setOnPreferenceClickListener {
            CustomTabsHelper.launchUrlOrCopy(requireContext(), "https://github.com/thejaustin/ShizukuPlus/blob/main/OPEN_SOURCE_LICENSES.md")
            true
        }
    }
}
