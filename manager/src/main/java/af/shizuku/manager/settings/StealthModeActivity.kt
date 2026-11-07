package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import com.google.android.material.appbar.MaterialToolbar
import af.shizuku.manager.R

/**
 * Host for [StealthModeFragment] (P2-4). A bare PreferenceScreen container in
 * this repo's preference style — no Compose scaffolding needed.
 *
 * The page owns its own header (back + title) because it is a plain View/AppCompat host:
 * [SettingsActivity]'s Compose header does not apply here, and without one the list would run
 * straight under the status bar with no way back but the system gesture.
 */
class StealthModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        af.shizuku.manager.utils.GlassSkin.applyIfEnabled(this)

        val toolbar = MaterialToolbar(this).apply {
            setTitle(R.string.settings_stealth_tutorial_entry)
            setNavigationIcon(R.drawable.ic_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }

        val container = FrameLayout(this).apply { id = R.id.fragment_container }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                toolbar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                container,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        setContentView(root)

        // Edge-to-edge: the header sits below the status bar / cutout. Only the top inset is
        // consumed here; horizontal and bottom insets are already applied by
        // BaseSettingsFragment's list padding.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, 0)
            insets
        }

        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, StealthModeFragment())
            }
        }
    }
}
