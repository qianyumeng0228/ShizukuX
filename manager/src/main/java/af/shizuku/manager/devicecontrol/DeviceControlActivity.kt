package af.shizuku.manager.devicecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import af.shizuku.manager.R
import rikka.shizuku.Shizuku

/**
 * Device control activity — provides quick toggles for connectivity, display,
 * audio, system appearance, and power operations via Shizuku shell privilege.
 *
 * This is a minimal functional UI (not the upstream Home card visual).
 * Ported from upstream ShizukuPlus DeviceControlScreen (r2647), adapted to
 * ShizukuX Extra API and traditional Activity layout.
 */
class DeviceControlActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var powerWarning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_control)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.device_control_title)

        statusText = findViewById(R.id.tv_status)
        powerWarning = findViewById(R.id.tv_power_warning)

        // Check if power operations are available (root only)
        try {
            if (DeviceControlManager.isPowerAvailable()) {
                powerWarning.visibility = android.view.View.GONE
            }
        } catch (_: Exception) {
            // binder not available, warning stays visible
        }

        // ── Connectivity ──────────────────────────────────────────────────────
        bindToggle(R.id.btn_airplane_on, R.id.btn_airplane_off) { DeviceControlManager.setAirplaneMode(it) }
        bindToggle(R.id.btn_wifi_on, R.id.btn_wifi_off) { DeviceControlManager.setWifi(it) }
        bindToggle(R.id.btn_bt_on, R.id.btn_bt_off) { DeviceControlManager.setBluetooth(it) }
        bindToggle(R.id.btn_mobile_on, R.id.btn_mobile_off) { DeviceControlManager.setMobileData(it) }
        bindToggle(R.id.btn_nfc_on, R.id.btn_nfc_off) { DeviceControlManager.setNfc(it) }

        // ── Display ───────────────────────────────────────────────────────────
        val brightnessSeek = findViewById<SeekBar>(R.id.seek_brightness)
        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) runSafe("setBrightness($progress)") { DeviceControlManager.setScreenBrightness(progress) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        bindToggle(R.id.btn_autobrightness_on, R.id.btn_autobrightness_off) { DeviceControlManager.setAutoBrightness(it) }
        bindToggle(R.id.btn_autorotate_on, R.id.btn_autorotate_off) { DeviceControlManager.setAutoRotate(it) }

        // ── Audio ─────────────────────────────────────────────────────────────
        val volumeSeek = findViewById<SeekBar>(R.id.seek_volume)
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) runSafe("setVolume($progress)") { DeviceControlManager.setStreamVolume(3, progress) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Set initial volume
        runSafe("getVolume") {
            val vol = DeviceControlManager.getStreamVolume(3)
            if (vol >= 0) volumeSeek.progress = vol
            true
        }

        // ── System Appearance ─────────────────────────────────────────────────
        bindToggle(R.id.btn_animations_on, R.id.btn_animations_off) { DeviceControlManager.setAnimations(it) }

        // ── Power ─────────────────────────────────────────────────────────────
        findViewById<Button>(R.id.btn_reboot).setOnClickListener {
            runSafe("reboot") { DeviceControlManager.reboot(null) }
        }
        findViewById<Button>(R.id.btn_reboot_recovery).setOnClickListener {
            runSafe("reboot(recovery)") { DeviceControlManager.reboot("recovery") }
        }
        findViewById<Button>(R.id.btn_shutdown).setOnClickListener {
            runSafe("shutdown") { DeviceControlManager.shutdown() }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindToggle(onId: Int, offId: Int, action: (Boolean) -> Boolean) {
        findViewById<Button>(onId).setOnClickListener {
            runSafe(findViewById<Button>(onId).text.toString()) { action(true) }
        }
        findViewById<Button>(offId).setOnClickListener {
            runSafe(findViewById<Button>(offId).text.toString()) { action(false) }
        }
    }

    private fun runSafe(label: String, block: () -> Boolean) {
        try {
            if (!Shizuku.pingBinder()) {
                setStatus("ERROR: Shizuku binder not available")
                Toast.makeText(this, "Shizuku service not running", Toast.LENGTH_SHORT).show()
                return
            }
            val ok = block()
            setStatus("$label: ${if (ok) "OK" else "FAILED (returned false)"}")
        } catch (e: Exception) {
            setStatus("$label: ERROR - ${e.message}")
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }
}
