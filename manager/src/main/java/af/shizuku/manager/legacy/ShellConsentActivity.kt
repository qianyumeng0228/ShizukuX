package af.shizuku.manager.legacy

import android.os.Bundle
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.core.ui.AppActivity
import af.shizuku.manager.R
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

        showConsentDialog(callbackBinder)
    }

    private fun showConsentDialog(callbackBinder: android.os.IBinder) {
        val binding = ConfirmationDialogBinding.inflate(layoutInflater).apply {
            title.text = getString(R.string.shell_consent_dialog_title)
            button1.text = getString(R.string.shell_consent_button_allow)
            button3.text = getString(R.string.grant_dialog_button_deny)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(binding.root)
            .setCancelable(false)
            .setOnDismissListener { finish() }
            .create()
        dialog.setCanceledOnTouchOutside(false)

        binding.button1.setOnClickListener {
            // deliverBinder does a synchronous binder transact - keep it off Main.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        ShellBinderRequestHandler.deliverBinder(this@ShellConsentActivity, callbackBinder)
                    } catch (e: Exception) {
                        LOGGER.w(e, "ShellConsentActivity: deliverBinder failed")
                    }
                }
                if (!isFinishing && !isDestroyed) dialog.dismiss()
            }
        }
        binding.button3.setOnClickListener {
            dialog.dismiss()
        }

        try {
            dialog.show()
        } catch (e: WindowManager.BadTokenException) {
            finish()
        }
    }
}
