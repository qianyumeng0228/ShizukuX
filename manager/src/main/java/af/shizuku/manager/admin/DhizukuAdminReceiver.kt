package af.shizuku.manager.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import af.shizuku.manager.R

class DhizukuAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_enabled, Toast.LENGTH_SHORT).show()
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(context, DhizukuAdminReceiver::class.java)
            dpm.setBackupServiceEnabled(componentName, true)
        } catch (e: Exception) {
            // Ignored, may not be device owner yet or method not available
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_disabled, Toast.LENGTH_SHORT).show()
    }

    // Relay lock-task transitions to Dhizuku clients that registered a listener through
    // IDhizuku.addLockTaskModeListener (server API v8, upstream 6c7865e).
    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        com.rosan.dhizuku.server_api.LockTaskModeListeners.notifyLockTaskModeChanged(true, pkg)
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        com.rosan.dhizuku.server_api.LockTaskModeListeners.notifyLockTaskModeChanged(false, null)
    }

    override fun onTransferOwnershipComplete(context: Context, bundle: PersistableBundle?) {
        super.onTransferOwnershipComplete(context, bundle)
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(context, DhizukuAdminReceiver::class.java)
            if (dpm.isDeviceOwnerApp(context.packageName)) {
                dpm.setBackupServiceEnabled(componentName, true)
            }
        } catch (e: Exception) {
            // Ignored, may not be device owner yet or method not available
        }
    }
}
