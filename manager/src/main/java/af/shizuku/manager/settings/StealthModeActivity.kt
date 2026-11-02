package af.shizuku.manager.settings

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import af.shizuku.manager.R

/**
 * Host for [StealthModeFragment] (P2-4). A bare PreferenceScreen container in
 * this repo's preference style — no Compose scaffolding needed.
 */
class StealthModeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this).apply { id = R.id.fragment_container })
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, StealthModeFragment())
            }
        }
    }
}
