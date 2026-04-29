package af.shizuku.manager.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import af.shizuku.manager.R

class DhizukuAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_enabled, Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_disabled, Toast.LENGTH_SHORT).show()
    }
}