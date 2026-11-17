package af.shizuku.manager.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.database.DhizukuAppManager
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
 *  - otherwise show an agree/refuse dialog with a visible 15s countdown;
 *  - only a real user choice (allow / deny / dismiss) is persisted. A timeout, a lost
 *    window focus or a dead client must never write a denial into the database: the
 *    official client already aborts that request after 15s, and persisting the timeout
 *    used to wipe a permission the user had granted earlier, which is exactly what made
 *    authorized apps (e.g. InstallerX) look "authorized but unusable".
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
    /** True only when the user actually answered (allow / deny / dismiss). */
    private var persistDecision = false

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

    override fun finish() {
        super.finish()
        handler.removeCallbacksAndMessages(null)
        if (persistDecision && targetUid != -1) {
            val signature = DhizukuAppsAdapter.getAppSignature(this, targetUid) ?: ""
            val allowed = grantResult == PackageManager.PERMISSION_GRANTED
            DhizukuAppManager.setAllowed(targetUid, signature, allowed)
            Timber.tag(TAG).i("uid=$targetUid result=${if (allowed) "granted" else "denied"}")
        }
        // The client app may have died/unbound during the dialog; a dead binder would otherwise
        // throw DeadObjectException (a RuntimeException) and crash this activity.
        runCatching { listener?.onRequestPermission(grantResult) }
            .onFailure { Timber.tag(TAG).w(it, "onRequestPermission callback failed (client likely dead)") }
    }

    private fun parseIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val bundle = listOfNotNull(intent.extras, intent.getBundleExtra("bundle"))
            .find { it.containsKey(PARAM_CLIENT_UID) } ?: return false
        val uid = bundle.getInt(PARAM_CLIENT_UID, -1)
        if (uid == -1) return false
        // The uid inside the intent is attacker-controlled: without this check any app could
        // launch the dialog naming a *different* package and trick the user into authorizing
        // it. Require the launcher to be the app it claims to be; an unknown launcher (-1,
        // e.g. a PendingIntent) is tolerated so legitimate indirect launches keep working.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val launcherUid = launchedFromUid
            if (launcherUid >= 0 && launcherUid != uid && !isTrustedForwarder(launcherUid)) {
                Timber.tag(TAG).w("request uid=$uid does not match launcher uid=$launcherUid, rejecting")
                return false
            }
        }
        val binder = bundle.getBinder(PARAM_CLIENT_REQUEST_PERMISSION_BINDER) ?: return false
        listener = IDhizukuRequestPermissionListener.Stub.asInterface(binder)
        targetUid = uid
        return true
    }

    /**
     * A forwarder necessarily changes the launching uid, so a request relayed by the optional
     * Dhizuku compat add-on would otherwise fail the launchedFromUid == uid check.
     *
     * Only an APK signed with the manager's own certificate is trusted here: a third party
     * cannot produce such a signature, so this does not widen the attack surface that the
     * check was added to close — it only keeps our own relay working.
     */
    private fun isTrustedForwarder(launcherUid: Int): Boolean {
        if (launcherUid < 0 || launcherUid == android.os.Process.myUid()) return true
        val pm = packageManager
        val launcherPackages = try {
            pm.getPackagesForUid(launcherUid)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "getPackagesForUid($launcherUid) failed")
            null
        } ?: return false
        val ownSignatures = try {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "reading own signatures failed")
            null
        }
        if (ownSignatures == null || ownSignatures.isEmpty()) return false
        return launcherPackages.any { pkg ->
            val signatures = try {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            } catch (e: Exception) {
                null
            }
            signatures != null && signatures.any { candidate -> ownSignatures.any { it == candidate } }
        }
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
                persistDecision = true
                finish()
            }
            .setNegativeButton(R.string.dhizuku_request_permission_deny) { _, _ ->
                grantResult = PackageManager.PERMISSION_DENIED
                persistDecision = true
                finish()
            }
            .setOnCancelListener {
                grantResult = PackageManager.PERMISSION_DENIED
                persistDecision = true
                finish()
            }
            .show()

        // Report DENIED to the client after AUTO_DENY_SECONDS (its own requestPermission()
        // gives up at the same point), but keep whatever the user had granted before:
        // a timeout is not a decision and must not be written into the database.
        handler.postDelayed({
            if (dialog.isShowing) {
                grantResult = PackageManager.PERMISSION_DENIED
                persistDecision = false
                finish()
            }
        }, AUTO_DENY_SECONDS * 1000)
    }
}
