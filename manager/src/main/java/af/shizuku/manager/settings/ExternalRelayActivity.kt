package af.shizuku.manager.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.widget.ExternalRelayAdapter

/**
 * External relay authorization management screen.
 *
 * ShizukuX acts as a middle-man to grant ADB-level access to apps that do not declare Shizuku
 * permissions themselves. Currently supports Scene and Brevent; the screen is structured so that
 * more relayed apps can be added later (see [ExternalRelayAdapter]).
 *
 * The header toggle enables auto-activation: with the accessibility service on, tapping Scene's
 * ADB-authorize button or opening Brevent runs the relay chain automatically.
 */
class ExternalRelayActivity : AppBarActivity() {

    private lateinit var adapter: ExternalRelayAdapter

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

        adapter = ExternalRelayAdapter(
            this,
            onActivateScene = {
                SceneRelayManager.startSceneAdbActivation(this, lifecycleScope)
            },
            onActivateBrevent = {
                BreventRelayManager.activateBrevent(this, lifecycleScope)
            },
            onAutoToggle = { enable ->
                ShizukuSettings.setExternalRelayAuto(enable)
                if (enable && !isAutoAccessibilityEnabled()) {
                    Toast.makeText(
                        this,
                        R.string.external_relay_auto_need_accessibility,
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onOpenAccessibility = {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.external_relay_auto_open_accessibility_failed, Toast.LENGTH_SHORT).show()
                }
            },
            onEnableOwnerWireless = {
                // Device Owner writes system settings directly — no Wi-Fi, no switch click.
                val result = DeviceOwnerHelper.enableWirelessDebugging(this)
                val success = result.isEmpty()
                val holder = recyclerView.findViewHolderForAdapterPosition(1)
                    as? ExternalRelayAdapter.OwnerViewHolder
                if (holder != null) {
                    holder.showResult(success, if (success) null else result)
                } else {
                    val msg: CharSequence = if (success) {
                        getString(R.string.external_relay_owner_done)
                    } else {
                        getString(R.string.external_relay_owner_failed) + result
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            },
            onRequestIgnoreBatteryOptimization = {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")))
                    } catch (e: Exception) {
                        try {
                            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        } catch (e2: Exception) {
                            Toast.makeText(this, R.string.external_relay_auto_open_accessibility_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
        recyclerView.adapter = adapter
    }

    private fun isAutoAccessibilityEnabled(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = "$packageName/af.shizuku.manager.adb.ExternalRelayAutoService"
            enabled.split(':').any {
                it.equals(component, ignoreCase = true)
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the accessibility and battery status rows after the user comes back from Settings.
        if (::adapter.isInitialized) {
            adapter.notifyItemChanged(0)
            adapter.notifyItemChanged(1)
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
