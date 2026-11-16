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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.SettingsHelper

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
                pollRelayState(scene = true)
            },
            onActivateBrevent = {
                BreventRelayManager.activateBrevent(this, lifecycleScope)
                pollRelayState(scene = false)
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
                // Same predicate as ExternalRelayAdapter.refreshBatteryStatus. Using the raw
                // PowerManager check here made MIUI/HyperOS re-open the request dialog even though
                // the row already said "disabled" — the AOSP whitelist never reflects the OEM
                // "no limits" choice, so the two halves disagreed.
                if (SettingsHelper.isBatteryOptimizationEffectivelyDisabled(this)) {
                    // Already exempt: the row button reads "Battery settings" and lands on the list.
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, R.string.external_relay_auto_open_accessibility_failed, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // MIUI/HyperOS：进入电池页即标记已响应（标准 API 与省电策略不同步）
                    if (EnvironmentUtils.isXiaomi()) {
                        SettingsHelper.markBatteryOptimizationAcknowledged(this)
                    }
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
        refreshRelayStates()
    }

    /**
     * Probes both relays off the main thread and pushes the results into the adapter chips.
     * Two shell IPC round-trips, so it is cheap enough to run on every resume — which also keeps
     * the chips honest when the user kills the daemon in another app and comes back.
     */
    private fun refreshRelayStates() {
        if (!::adapter.isInitialized) return
        lifecycleScope.launch {
            val scene = withContext(Dispatchers.IO) { SceneRelayManager.isRelayActive() }
            val brevent = withContext(Dispatchers.IO) { BreventRelayManager.isRelayActive() }
            adapter.updateSceneState(scene)
            adapter.updateBreventState(brevent)
        }
    }

    /**
     * Activation is fire-and-forget (each manager owns its own result dialog), so without this
     * the chip would stay stale until the user leaves and re-enters. Poll for a bounded window
     * and flip the chip as soon as the daemon shows up.
     *
     * The window is ~30 s because a redeploy is not a quick bounce: the daemon image (~1.8 MB) is
     * copied to /data/local/tmp and busybox is linked before the process is spawned. Either way the
     * "activating" line is cleared when the poll ends (success or timeout) — it used to be left on
     * screen forever, which read as a hung activation.
     */
    private fun pollRelayState(scene: Boolean) {
        lifecycleScope.launch {
            try {
                repeat(24) {
                    delay(1250)
                    val active = withContext(Dispatchers.IO) {
                        if (scene) {
                            SceneRelayManager.isRelayActive()
                        } else {
                            BreventRelayManager.isRelayActive()
                        }
                    }
                    if (active) {
                        if (scene) adapter.updateSceneState(true) else adapter.updateBreventState(true)
                        return@launch
                    }
                }
            } finally {
                if (::adapter.isInitialized) {
                    try {
                        if (scene) adapter.setSceneBusy(false) else adapter.setBreventBusy(false)
                    } catch (ignored: Exception) {
                        // Adapter detached / activity finishing — nothing to repaint.
                    }
                    // Re-probe once: a run that never became active used to leave the chip on its
                    // previous value, which could still read "active" for a relay Scene locked out.
                    refreshRelayStates()
                }
            }
        }
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
            refreshRelayStates()
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
