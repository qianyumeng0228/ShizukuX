package af.shizuku.manager.devicecontrol

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import af.shizuku.manager.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

        // Check if power operations are available (root only); disable buttons in ADB mode.
        // isPowerAvailable() is a binder IPC — do it off the main thread.
        lifecycleScope.launch {
            val powerAvailable = withContext(Dispatchers.IO) {
                runCatching { DeviceControlManager.isPowerAvailable() }.getOrDefault(false)
            }
            if (isFinishing) return@launch
            if (powerAvailable) {
                powerWarning.visibility = android.view.View.GONE
            } else {
                listOf(R.id.btn_reboot, R.id.btn_reboot_recovery, R.id.btn_shutdown).forEach { id ->
                    findViewById<Button>(id).isEnabled = false
                }
            }
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

        // Set initial volume / brightness. Both are binder reads; move off main thread.
        // These read-then-update-UI flows can't use runSafe (which runs the whole block on IO);
        // we need the IPC on IO but the SeekBar.progress write back on main.
        lifecycleScope.launch {
            val vol = withContext(Dispatchers.IO) {
                runCatching { DeviceControlManager.getStreamVolume(3) }.getOrDefault(-1)
            }
            if (vol >= 0) volumeSeek.progress = vol
        }
        lifecycleScope.launch {
            val bright = withContext(Dispatchers.IO) {
                runCatching { DeviceControlManager.getSetting("system", "screen_brightness")?.toIntOrNull() }.getOrNull()
            }
            if (bright != null) brightnessSeek.progress = bright
        }

        // ── System Appearance ─────────────────────────────────────────────────
        bindToggle(R.id.btn_animations_on, R.id.btn_animations_off) { DeviceControlManager.setAnimations(it) }

        // ── Power ─────────────────────────────────────────────────────────────
        findViewById<Button>(R.id.btn_reboot).setOnClickListener {
            runSafe("重启") { DeviceControlManager.reboot(null) }
        }
        findViewById<Button>(R.id.btn_reboot_recovery).setOnClickListener {
            runSafe("重启到 Recovery") { DeviceControlManager.reboot("recovery") }
        }
        findViewById<Button>(R.id.btn_shutdown).setOnClickListener {
            runSafe("关机") { DeviceControlManager.shutdown() }
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

    /**
     * Runs a [block] (which performs a Shizuku binder IPC) off the main thread and reports
     * the result via [statusText]. Previously every toggle click ran its binder call on the
     * main thread, freezing the UI on slow servers/OEM builds.
     */
    private fun runSafe(label: String, block: () -> Boolean) {
        lifecycleScope.launch {
            try {
                val alive = withContext(Dispatchers.IO) { Shizuku.pingBinder() }
                if (!alive) {
                    setStatus("错误：Shizuku Binder 不可用")
                    Toast.makeText(this@DeviceControlActivity, "Shizuku 服务未运行", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // The block itself may contain binder calls; run it on IO.
                val ok = withContext(Dispatchers.IO) { block() }
                setStatus("$label：${if (ok) "成功" else "失败（返回 false）"}")
            } catch (e: Exception) {
                setStatus("$label：错误 - ${e.message}")
            }
        }
    }

    private fun setStatus(msg: String) {
        statusText.text = msg
    }
}
