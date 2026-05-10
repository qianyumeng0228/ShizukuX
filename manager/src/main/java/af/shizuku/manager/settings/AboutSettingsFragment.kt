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
                setTitle(R.string.manual_report_last_crash_title)
                setSummary(R.string.manual_report_last_crash_summary)
            }

            setOnPreferenceClickListener {
                showDialog(
                    MaterialAlertDialogBuilder(context)
                        .setTitle(if (hasLastCrash) R.string.manual_report_last_crash_title else R.string.manual_report_title)
                        .setMessage(R.string.sentry_offline_notice_learn_more)
                        .setPositiveButton(R.string.manual_report_button_github) { _, _ ->
                            val report = CrashReporter.generateReport(context)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                context.getString(R.string.manual_report_clipboard_label), report))
                            
                            Toast.makeText(context, R.string.manual_report_toast_copied, Toast.LENGTH_LONG).show()
                            
                            val url = CrashReporter.getGitHubReportUrl(context)
                            CustomTabsHelper.launchUrlOrCopy(context, url)
                            
                            if (hasLastCrash) {
                                af.shizuku.manager.utils.CrashHandler.clearLastCrash(context)
                            }
                        }
                        .setNeutralButton(R.string.manual_report_copied_dialog_share) { _, _ ->
                            af.shizuku.manager.utils.CrashReporter.shareAsFile(context)
                            if (hasLastCrash) {
                                af.shizuku.manager.utils.CrashHandler.clearLastCrash(context)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                )
                true
            }
        }

        findPreference<Preference>("version")?.apply {
            summary = BuildConfig.VERSION_NAME
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
