package moe.shizuku.manager.home

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.adb.AdbStarter
import moe.shizuku.manager.receiver.NotifCancelReceiver
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.core.content.asActivity

object WadbStarter {

    fun start(context: Context, scope: CoroutineScope) {
        if (ShizukuStateMachine.get() == ShizukuStateMachine.State.STARTING) {
            Toast.makeText(context, context.getString(R.string.toast_shizuku_already_starting), Toast.LENGTH_SHORT).show()
            return
        }

        context.sendBroadcast(Intent(context, NotifCancelReceiver::class.java))

        val cr = context.contentResolver
        if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) {
            Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
            Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        }
    
        val adbEnabled = Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)
        if (adbEnabled == 0) {
            WadbEnableUsbDebuggingDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
            return
        }

        val tcpPort = EnvironmentUtils.getAdbTcpPort()
        val tcpMode = ShizukuSettings.getTcpMode()

        // If ADB is NOT listening to a TCP port and the device doesn't support TLS, inform the user
        if (tcpPort <= 0 && !EnvironmentUtils.isTlsSupported()) {
            WadbNotEnabledDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        // If ADB IS NOT listening to a TCP port but the device supports TLS, start mDns discovery
        } else if (tcpPort <= 0) {
            AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        // If ADB IS listening to a TCP port but the user wants to close it and use TLS instead, close the TCP port and start mDns discovery
        } else if (!tcpMode) {
            scope.launch {
                AdbStarter.stopTcp(context, tcpPort)
            }
            AdbDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        // Otherwise ADB IS listening to a TCP port and the user wants to keep it open. Start Shizuku via TCP
        } else {
            val intent = Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_PORT, tcpPort)
            }
            context.startActivity(intent)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun pair(context: Context) {
        if (EnvironmentUtils.isTelevision()) {
            context.showAccessibilityDialog()
        } else if ((context.display?.displayId ?: -1) > 0 || ShizukuSettings.getLegacyPairing()) {
            // Running in a multi-display environment (e.g., Windows Subsystem for Android),
            // pairing dialog can be displayed simultaneously with Shizuku.
            AdbPairDialogFragment().show(context.asActivity<FragmentActivity>().supportFragmentManager)
        } else {
            context.startActivity(Intent(context, AdbPairingTutorialActivity::class.java))
        }
    }
}
