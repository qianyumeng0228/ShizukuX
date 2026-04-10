package af.shizuku.manager.settings

import android.os.Bundle
import af.shizuku.manager.R

class AppManagementSettingsFragment : BaseSettingsFragment() {

    override fun onCreateSettingsPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_app_management, rootKey)
    }
}
