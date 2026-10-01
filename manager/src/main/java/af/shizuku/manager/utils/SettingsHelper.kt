package af.shizuku.manager.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import android.os.PowerManager
import android.provider.Settings
import af.shizuku.manager.R
import af.shizuku.manager.utils.SettingsPage

object SettingsHelper {

    fun launchOrHighlightWirelessDebugging(context: Context) {
        if (EnvironmentUtils.isAdbEnabled()) {
            SettingsPage.Developer.WirelessDebugging.launch(context)
        } else SettingsPage.Developer.HighlightWirelessDebugging.launch(context)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    // MIUI/HyperOS 会把"省电策略-无限制"存到自己的电池管理里，不一定写入 AOSP 的
    // battery whitelist，PowerManager.isIgnoringBatteryOptimizations() 会一直返回 false，
    // 导致用户明明在系统里设了"无限制"，主界面/设置页的弹窗却照常出现（状态没有实时变化）。
    // 对这些 ROM：只要用户从本 app 进过电池设置页（点过"修复"）并返回，就视为已处理，
    // 不再反复打扰——这是 MIUI 上唯一可靠的"用户已响应"信号。
    private const val BATTERY_ACK_PREFS = "battery_optimization_ack"
    private const val KEY_BATTERY_ACK = "acknowledged"

    private fun batteryAckPrefs(context: Context) =
        context.getSharedPreferences(BATTERY_ACK_PREFS, Context.MODE_PRIVATE)

    fun isBatteryOptimizationAcknowledged(context: Context): Boolean =
        batteryAckPrefs(context).getBoolean(KEY_BATTERY_ACK, false)

    fun markBatteryOptimizationAcknowledged(context: Context) {
        batteryAckPrefs(context).edit().putBoolean(KEY_BATTERY_ACK, true).apply()
    }

    /**
     * 统一的"电池优化已处理"判定：非 MIUI 走标准 API；MIUI/HyperOS 上标准 API 不可靠，
     * 用户已从本 app 进过电池页即视为已处理。
     */
    fun isBatteryOptimizationEffectivelyDisabled(context: Context): Boolean {
        if (EnvironmentUtils.isXiaomi() && isBatteryOptimizationAcknowledged(context)) return true
        return isIgnoringBatteryOptimizations(context)
    }

    fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun promptWriteSecureSettings(context: Context) {
        val command = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"
        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle(R.string.wadb_permission_error_notification_title)
            .setMessage(context.getString(R.string.dialog_adb_pairing_accessibility_permission, "WRITE_SECURE_SETTINGS", command))
            .setPositiveButton(R.string.home_adb_dialog_view_command_copy_button) { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb command", command))
                android.widget.Toast.makeText(context, R.string.toast_copied_to_clipboard, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val expectedComponent = ComponentName(context, serviceClass).flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
    }

    fun requestIgnoreBatteryOptimizations(context: Context, launcher: ActivityResultLauncher<Intent>? = null) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            setData(Uri.parse("package:" + context.packageName))
        }
        try {
            launcher?.launch(intent) ?: context.startActivity(intent)
        } catch (e: IllegalStateException) {
            // Launcher may be unregistered if the fragment was detached — fall back to startActivity
            context.startActivity(intent)
        }
    }

    fun isSamsungAutoBlockerDisabled(context: Context): Boolean {
        return try {
            val rampart = Settings.Secure.getInt(context.contentResolver, "rampart_enabled", 0)
            rampart == 0
        } catch (e: Exception) {
            true
        }
    }

    fun isSamsungMaxRestrictionsDisabled(context: Context): Boolean {
        return try {
            val maxRestrictions = Settings.Secure.getInt(context.contentResolver, "rampart_max_restrictions_enabled", 0)
            maxRestrictions == 0
        } catch (e: Exception) {
            true
        }
    }
}
