package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.Toast
import androidx.annotation.Keep
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import timber.log.Timber
import af.shizuku.manager.database.AppContextManager

import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys.KEY_COMPANION_MODE
import androidx.preference.TwoStatePreference

@Keep
class HomeVisibilitySettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_home_visibility, rootKey)
        val simulatorPref = findPreference<HomeLayoutSimulatorPreference>("home_layout_simulator")
        simulatorPref?.setFragment(this)

        val switchKeys = listOf(
            "show_start_adb_home",
            "show_terminal_home",
            "show_automation_home",
            "show_activity_log_home",
            "show_learn_more_home"
        )

        for (prefKey in switchKeys) {
            findPreference<TwoStatePreference>(prefKey)?.setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) {
                    preferenceManager.sharedPreferences?.edit()?.putBoolean(prefKey, newValue)?.apply()
                    simulatorPref?.notifyChanged()
                }
                true
            }
        }

        val context = requireContext()

        findPreference<TwoStatePreference>(KEY_COMPANION_MODE)?.apply {
            isChecked = ShizukuSettings.isCompanionModeEnabled()
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue is Boolean) ShizukuSettings.setCompanionModeEnabled(newValue)
                true
            }
        }

        findPreference<Preference>("update_app_database")?.setOnPreferenceClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val url = java.net.URL("https://raw.githubusercontent.com/thejaustin/ShizukuPlus/master/database/apps.json")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    val content = try {
                        connection.instanceFollowRedirects = true
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 10_000
                        connection.readTimeout = 10_000
                        
                        val responseCode = connection.responseCode
                        if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                            throw java.io.IOException("HTTP $responseCode from GitHub")
                        }
                        
                        connection.inputStream.use { it.bufferedReader().readText() }
                    } finally {
                        connection.disconnect()
                    }
                    withContext(Dispatchers.Main) {
                        AppContextManager.updateDatabase(content)
                        Toast.makeText(context, R.string.settings_update_app_database_success, Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Timber.e("update app database failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, R.string.settings_update_app_database_error, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
    }
}
