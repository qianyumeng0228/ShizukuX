package af.shizuku.manager.legacy

import android.content.Intent
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

        val requestIntent = intent
        if (requestIntent.getBundleExtra("data")?.getBinder("binder") == null) {
            finish()
            return
        }

        showConsentDialog(requestIntent)
    }

    private fun showConsentDialog(requestIntent: Intent) {
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
            // handleRequest does a synchronous binder transact - keep it off Main.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        ShellBinderRequestHandler.handleRequest(this@ShellConsentActivity, requestIntent, false)
                    } catch (e: Exception) {
                        LOGGER.w(e, "ShellConsentActivity: handleRequest failed")
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
