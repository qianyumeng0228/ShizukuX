package af.shizuku.manager.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.DhizukuAppManager
import af.shizuku.manager.widget.DhizukuAppsAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener
import timber.log.Timber

/**
 * Handles the official Dhizuku request-permission protocol.
 *
 * A client app (e.g. Hail) that supports the Dhizuku API launches this activity through
 *   Intent(action = "<owner-package>.action.REQUEST_DHIZUKU_PERMISSION")
 *        .setPackage(ownerPackage)
 *        .putExtra(PARAM_CLIENT_UID, clientUid)
 *        .putExtra(PARAM_CLIENT_REQUEST_PERMISSION_BINDER, listener)
 *
 * We mirror dhizuku's RequestPermissionActivity:
 *  - grant immediately when the app is already authorized with a matching signature;
 *  - otherwise show an agree/refuse dialog (15s countdown, auto-deny on timeout);
 *  - persist the decision to DhizukuAppManager and report the result back to the listener.
 */
class RequestPermissionActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DhizukuRequestPermission"
        private const val PARAM_CLIENT_UID = "uid"
        private const val PARAM_CLIENT_REQUEST_PERMISSION_BINDER = "request_permission_binder"
        private const val AUTO_DENY_SECONDS = 15L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var targetUid = -1
    private var listener: IDhizukuRequestPermissionListener? = null
    private var grantResult = PackageManager.PERMISSION_DENIED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!parseIntent(intent)) {
            Timber.tag(TAG).w("Invalid request permission intent, finishing")
            finish()
            return
        }

        if (!ShizukuSettings.isDhizukuModeEnabled()) {
            Timber.tag(TAG).w("Dhizuku mode disabled, denying uid=$targetUid")
            finish()
            return
        }

        val entity = DhizukuAppManager.findByUid(targetUid)
        if (entity?.blocked == true) {
            Timber.tag(TAG).w("App uid=$targetUid is blocked, denying")
            finish()
            return
        }

        // Already granted with a matching signature -> grant without showing a dialog.
        val currentSignature = DhizukuAppsAdapter.getAppSignature(this, targetUid)
        if (entity?.allowApi == true && entity.signature == currentSignature) {
            grantResult = PackageManager.PERMISSION_GRANTED
            Timber.tag(TAG).i("uid=$targetUid already authorized, granting")
            finish()
            return
        }

        showPermissionDialog(currentSignature)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (parseIntent(intent)) return
        finish()
    }

    override fun onPause() {
        super.onPause()
        // Mirror dhizuku: a translucent singleInstance activity that loses focus is done.
        finish()
    }

    override fun finish() {
        super.finish()
        handler.removeCallbacksAndMessages(null)
        if (targetUid != -1) {
            val signature = DhizukuAppsAdapter.getAppSignature(this, targetUid) ?: ""
            val allowed = grantResult == PackageManager.PERMISSION_GRANTED
            DhizukuAppManager.setAllowed(targetUid, signature, allowed)
            Timber.tag(TAG).i("uid=$targetUid result=${if (allowed) "granted" else "denied"}")
        }
        listener?.onRequestPermission(grantResult)
    }

    private fun parseIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val bundle = listOfNotNull(intent.extras, intent.getBundleExtra("bundle"))
            .find { it.containsKey(PARAM_CLIENT_UID) } ?: return false
        val uid = bundle.getInt(PARAM_CLIENT_UID, -1)
        if (uid == -1) return false
        val binder = bundle.getBinder(PARAM_CLIENT_REQUEST_PERMISSION_BINDER) ?: return false
        listener = IDhizukuRequestPermissionListener.Stub.asInterface(binder)
        targetUid = uid
        return true
    }

    private fun showPermissionDialog(currentSignature: String?) {
        val packageName = runCatching {
            packageManager.getPackagesForUid(targetUid)?.firstOrNull()
        }.getOrNull() ?: run {
            Timber.tag(TAG).w("No package for uid=$targetUid")
            finish()
            return
        }
        val (label, icon) = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            (packageManager.getApplicationLabel(appInfo)?.toString() ?: packageName) to
                packageManager.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to load app info for $packageName")
            packageName to null
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setIcon(icon)
            .setTitle(getString(R.string.dhizuku_request_permission_title, label))
            .setMessage(getString(R.string.dhizuku_request_permission_message, label, AUTO_DENY_SECONDS))
            .setPositiveButton(R.string.dhizuku_request_permission_allow) { _, _ ->
                grantResult = PackageManager.PERMISSION_GRANTED
                finish()
            }
            .setNegativeButton(R.string.dhizuku_request_permission_deny) { _, _ ->
                grantResult = PackageManager.PERMISSION_DENIED
                finish()
            }
            .setOnCancelListener {
                grantResult = PackageManager.PERMISSION_DENIED
                finish()
            }
            .show()

        // Auto-deny after AUTO_DENY_SECONDS, like dhizuku.
        handler.postDelayed({
            if (dialog.isShowing) {
                grantResult = PackageManager.PERMISSION_DENIED
                finish()
            }
        }, AUTO_DENY_SECONDS * 1000)
    }
}
