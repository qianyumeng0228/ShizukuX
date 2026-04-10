package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.Toast
import androidx.preference.Preference
import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.utils.CustomTabsHelper

class AboutSettingsFragment : BaseSettingsFragment() {

    private var versionClickCount = 0

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        val context = requireContext()

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
            showLicensesDialog()
            true
        }
    }

    private fun showLicensesDialog() {
        val context = context ?: return
        val licenses = """
            <b>Shizuku (Core)</b> - Apache 2.0
            https://github.com/RikkaApps/Shizuku
            
            <b>Rikka Library</b> - Apache 2.0
            https://github.com/RikkaApps/rikkax
            
            <b>Material Components for Android</b> - Apache 2.0
            https://github.com/material-components/material-components-android
            
            <b>Kotlin Coroutines</b> - Apache 2.0
            https://github.com/Kotlin/kotlinx.coroutines
            
            <b>AndroidX Libraries</b> - Apache 2.0
            https://developer.android.com/jetpack/androidx
            
            <b>Sentry SDK</b> - MIT
            https://github.com/getsentry/sentry-java
            
            <b>HiddenApiRefine</b> - MIT
            https://github.com/RikkaApps/HiddenApiRefine

            <b>Timber</b> - Apache 2.0
            https://github.com/JakeWharton/timber

            <b>Coil</b> - Apache 2.0
            https://github.com/coil-kt/coil

            <b>LibSu</b> - Apache 2.0
            https://github.com/topjohnwu/libsu

            <b>Bouncy Castle</b> - MIT
            https://github.com/bcgit/bc-java

            <b>BoringSSL</b> - ISC
            https://boringssl.googlesource.com/boringssl

            <b>HiddenApiBypass</b> - Apache 2.0
            https://github.com/LSPosed/AndroidHiddenApiBypass

            <br/><b>Credits & Special Thanks</b>
            Community contributors and translators
            Inspired by <b>Iconify</b>, <b>DarQ</b>, and <b>LSPosed</b>
        """.trimIndent()

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_open_source_licenses)
            .setMessage(licenses.toHtml())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
