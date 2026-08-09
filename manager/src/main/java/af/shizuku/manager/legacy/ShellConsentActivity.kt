package af.shizuku.manager.legacy

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
import af.shizuku.manager.authorization.AuthorizationManager
import af.shizuku.manager.database.ActivityLogManager
import af.shizuku.manager.databinding.ConfirmationDialogBinding
import af.shizuku.manager.shell.PendingConsentStore
import af.shizuku.manager.shell.ShellBinderRequestHandler
import af.shizuku.manager.utils.Logger.LOGGER

// Reached only via an explicit in-process launch from BinderRequestReceiver when a
// REQUEST_BINDER broadcast carries a callback binder but no valid encrypted auth token
// (i.e. every rish/shell client - see GH #368/#372/#374, IntentCrypto is scoped to this
// app's own UID so a shell process can never produce one). This restores a first-time
// consent path instead of silently dropping the request.
class ShellConsentActivity : AppActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Take the callback binder eagerly to avoid a TOCTOU between existence-check and use.
        // BinderRequestReceiver stores it in PendingConsentStore and passes the key here so the
        // binder survives the PendingIntent round-trip without being embedded in the extras
        // (Android 15+/API 35 does not reliably deliver IBinder objects in PendingIntent extras,
        // #387).
        val consentKey = intent.getStringExtra(PendingConsentStore.EXTRA_CONSENT_KEY)
        val callbackBinder = consentKey?.let { PendingConsentStore.take(it) }

        if (callbackBinder == null) {
            // Binder is gone (rish timed out and died before the user tapped the notification).
            finish()
            return
        }

        val callingPackage = intent.getStringExtra("callingPackage")
        showConsentDialog(callbackBinder, callingPackage)
    }

    private fun showConsentDialog(callbackBinder: android.os.IBinder, callingPackage: String?) {
        // Resolve ApplicationInfo from PackageManager rather than trusting the extras directly,
        // so a spoofed broadcast can't grant a different UID than the named package actually has.
        // Also derive the human-readable app label here — the dialog should show "Talkman is
        // requesting…" not "com.nirenr.talkman is requesting…" (#398).
        val appInfo = callingPackage?.let { pkg ->
            try {
                packageManager.getApplicationInfo(pkg, 0)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                null
            }
        }
        val callingUid = appInfo?.uid
        val appLabel = appInfo?.let { packageManager.getApplicationLabel(it).toString() }
            ?: callingPackage  // fallback to package ID if the lookup fails

        val binding = ConfirmationDialogBinding.inflate(layoutInflater).apply {
            if (appLabel != null) {
                title.text = getString(R.string.shell_consent_dialog_title_identified, appLabel)
                // button1 keeps its layout-default "Allow all the time" text
            } else {
                title.text = getString(R.string.shell_consent_dialog_title)
                button1.text = getString(R.string.shell_consent_button_allow)
            }
            button3.text = getString(R.string.grant_dialog_button_deny)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(false)
            .setOnDismissListener { finish() }
            .create()
        dialog.setCanceledOnTouchOutside(false)

        binding.button1.setOnClickListener {
            // deliverBinder does synchronous binder transacts with retries - keep it off Main.
            lifecycleScope.launch {
                val delivered = withContext(Dispatchers.IO) {
                    try {
                        // Grant permanent authorization before delivering the binder so that
                        // Shell.java's attachApplication() sees allowed=true and skips the
                        // redundant second consent dialog (#391). Grant is persisted even if
                        // delivery fails below - the next rish invocation then hits the fast
                        // path in BinderRequestReceiver and succeeds without another dialog.
                        if (callingPackage != null && callingUid != null) {
                            AuthorizationManager.grant(callingPackage, callingUid)
                        }
                        ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: allowed always (dialog)")
                        ShellBinderRequestHandler.deliverBinder(this@ShellConsentActivity, callbackBinder)
                    } catch (e: Exception) {
                        LOGGER.w(e, "ShellConsentActivity: deliverBinder failed")
                        false
                    }
                }
                if (!delivered && !isFinishing && !isDestroyed) {
                    // Delivery failed even after retries: the rish process was frozen by Android's
                    // Cached Apps Freezer while waiting. Authorization is saved — rish will connect
                    // automatically on the next invocation without another prompt.
                    Toast.makeText(
                        this@ShellConsentActivity,
                        getString(R.string.shell_consent_retry_hint),
                        Toast.LENGTH_LONG
                    ).show()
                }
                if (!isFinishing && !isDestroyed) dialog.dismiss()
            }
        }
        binding.button3.setOnClickListener {
            ActivityLogManager.log(appLabel ?: "Shell", callingPackage ?: "", "Shell: denied (dialog)")
            dialog.dismiss()
        }

        try {
            dialog.show()
        } catch (e: WindowManager.BadTokenException) {
            finish()
        }
    }
}
