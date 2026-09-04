package af.shizuku.manager.settings

import android.os.Bundle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.widget.ExternalRelayAdapter

/**
 * External relay authorization management screen.
 *
 * ShizukuX acts as a middle-man to grant ADB-level access to apps that do not declare Shizuku
 * permissions themselves. Currently supports Scene; the screen is structured so that more relayed
 * apps can be added later (see [ExternalRelayAdapter]).
 */
class ExternalRelayActivity : AppBarActivity() {

    override fun getLayoutId(): Int = R.layout.activity_external_relay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_external_relay_category_title)

        val recyclerView = findViewById<RecyclerView>(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, view.paddingTop, bars.right, view.paddingBottom)
            insets
        }

        recyclerView.adapter = ExternalRelayAdapter(this) {
            SceneRelayManager.startSceneAdbActivation(this, lifecycleScope)
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
