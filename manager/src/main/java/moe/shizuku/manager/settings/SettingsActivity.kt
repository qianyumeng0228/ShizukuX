package moe.shizuku.manager.settings

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarFragmentActivity

class SettingsActivity : AppBarFragmentActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    override fun createFragment(): Fragment = SettingsFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Listen for back stack changes to update title
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.backStackEntryCount == 0) {
                supportActionBar?.setTitle(R.string.settings_title)
            }
        }
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragmentName = pref.fragment ?: return false
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, fragmentName)
        fragment.arguments = pref.extras

        // MaterialSharedAxis transition for M3E 2026 feel
        val enterTransition = com.google.android.material.transition.MaterialSharedAxis(
            com.google.android.material.transition.MaterialSharedAxis.X, true
        )
        val exitTransition = com.google.android.material.transition.MaterialSharedAxis(
            com.google.android.material.transition.MaterialSharedAxis.X, true
        )
        val reenterTransition = com.google.android.material.transition.MaterialSharedAxis(
            com.google.android.material.transition.MaterialSharedAxis.X, false
        )
        val returnTransition = com.google.android.material.transition.MaterialSharedAxis(
            com.google.android.material.transition.MaterialSharedAxis.X, false
        )

        fragment.enterTransition = enterTransition
        fragment.returnTransition = returnTransition
        
        // Find existing fragment to set its exit transition
        supportFragmentManager.findFragmentById(R.id.fragment_container)?.apply {
            this.exitTransition = exitTransition
            this.reenterTransition = reenterTransition
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
        
        // Update title manually for immediate visual feedback
        supportActionBar?.title = pref.title
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
            return true
        }
        return super.onSupportNavigateUp()
    }
}
