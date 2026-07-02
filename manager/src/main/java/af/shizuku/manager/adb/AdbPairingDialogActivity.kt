package af.shizuku.manager.adb

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R

class AdbPairingDialogActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port_number", -1)
        if (port == -1) {
            finish()
            return
        }

        val margin = (24 * resources.displayMetrics.density).toInt()
        val editText = EditText(this).apply {
            hint = getString(R.string.dialog_adb_pairing_paring_code)
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin, 0, margin, 0) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, margin / 2, 0, 0)
            addView(editText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notification_adb_pairing_service_found_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val code = editText.text?.toString()?.trim() ?: ""
                if (code.isNotEmpty()) {
                    startForegroundService(AdbPairingService.dialogReplyIntent(this, port, code))
                }
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
