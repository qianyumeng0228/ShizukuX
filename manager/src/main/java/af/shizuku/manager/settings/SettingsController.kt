package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import af.shizuku.manager.R

/**
 * State and navigation of the settings destination, lifted out of [SettingsActivity].
 *
 * The settings screen itself is already a composable, but it talks to its host through the
 * activity's `supportFragmentManager` and `findViewById(R.id.fragment_container)`, so it could not
 * be hosted by the single-container shell. Both responsibilities now live here, keyed on the
 * hosting [FragmentActivity] - an activity or the shell are equally valid hosts.
 */
class SettingsController(private val host: FragmentActivity) {

    var currentTitle by mutableStateOf(host.getString(R.string.settings_title))
        private set
    var searchResults by mutableStateOf<List<SettingsSearchEngine.SettingItem>>(emptyList())
        private set

    /** True while a preference sub-page is open (then the back arrow is genuine). */
    var hasInnerPage by mutableStateOf(false)
        private set

    private val fragmentManager get() = host.supportFragmentManager

    fun observeBackStack() {
        fragmentManager.addOnBackStackChangedListener {
            hasInnerPage = fragmentManager.backStackEntryCount > 0
        }
    }

    fun search(query: String) {
        searchResults = if (query.isBlank()) {
            emptyList()
        } else {
            SettingsSearchEngine.search(host, query)
        }
    }

    fun navigateToSetting(item: SettingsSearchEngine.SettingItem) {
        openFragment(item.fragmentClass, Bundle().apply { putString("highlight_key", item.key) }, item.title)
    }

    fun openFragment(fragmentClass: String, arguments: Bundle?, title: String?) {
        val fragment = fragmentManager.fragmentFactory.instantiate(host.classLoader, fragmentClass)
        fragment.arguments = arguments

        fragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()

        title?.let { currentTitle = it }
    }

    fun updateTitle(title: String) {
        currentTitle = title
    }

    fun popInnerPage(): Boolean {
        if (fragmentManager.backStackEntryCount == 0) return false
        fragmentManager.popBackStack()
        return true
    }

    /**
     * Called once the compose tree has created the preference frame.
     *
     * On recreation after a theme or wallpaper switch (`setDefaultNightMode` triggers an activity
     * recreation) the FragmentManager restores the fragment but its view is left orphaned - the
     * container still has zero children, because Compose builds the container after the fragment
     * restoration phase. Re-parent the orphaned view so the settings modules do not vanish into a
     * blank page showing only the wallpaper.
     */
    fun attachContainerIfNeeded() {
        val frag = fragmentManager.findFragmentById(R.id.fragment_container)
        val container = host.findViewById<FrameLayout>(R.id.fragment_container)

        if (container == null || container.childCount == 0) {
            val orphaned = frag?.view
            if (container != null && orphaned != null) {
                (orphaned.parent as? android.view.ViewGroup)?.removeView(orphaned)
                container.addView(
                    orphaned,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else if (frag != null) {
                fragmentManager.beginTransaction().detach(frag).attach(frag).commit()
            } else {
                fragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, SettingsFragment())
                    .commit()
            }
        }
    }
}
