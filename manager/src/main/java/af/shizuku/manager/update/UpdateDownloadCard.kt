package af.shizuku.manager.update

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import af.shizuku.manager.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * In-app update card.
 *
 * Shows the live relay state (which source is being tried, progress, failure), and owns
 * the two actions the user has: abort everything, or keep going in the status bar.
 * The card never starts a download itself — [UpdateManager.downloadUpdate] must be called
 * first so the same card can also be reopened to watch an already-running relay.
 */
class UpdateDownloadCard(private val activity: ComponentActivity) {

    private var dialog: Dialog? = null
    private var renderJob: Job? = null

    fun show(versionName: String) {
        dismiss()

        val view: View = LayoutInflater.from(activity).inflate(R.layout.dialog_update_download, null)
        val d = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(view)
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.35f)
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        view.findViewById<TextView>(R.id.update_card_version).text =
            activity.getString(R.string.update_card_version, versionName)

        val status = view.findViewById<TextView>(R.id.update_card_status)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.update_card_progress)
        val closeButton = view.findViewById<MaterialButton>(R.id.update_card_close)
        val backgroundButton = view.findViewById<MaterialButton>(R.id.update_card_background)

        closeButton.setOnClickListener {
            UpdateManager(activity).cancelDownload()
            dismiss()
        }
        backgroundButton.setOnClickListener {
            UpdateManager(activity).moveToBackground()
            dismiss()
        }

        dialog = d
        d.setOnDismissListener {
            renderJob?.cancel()
            if (dialog === d) dialog = null
        }
        d.show()

        renderJob = activity.lifecycleScope.launch {
            UpdateManager.state.collect { state ->
                when (state) {
                    is UpdateManager.State.Idle -> {
                        bar.isIndeterminate = true
                        bar.visibility = View.VISIBLE
                        status.text = activity.getString(R.string.update_card_connecting, "Gitee", 1, 4)
                    }
                    is UpdateManager.State.Connecting -> {
                        bar.isIndeterminate = true
                        bar.visibility = View.VISIBLE
                        status.text = activity.getString(
                            R.string.update_card_connecting, state.source, state.attempt, state.total
                        )
                        closeButton.isEnabled = true
                        backgroundButton.visibility = View.VISIBLE
                    }
                    is UpdateManager.State.Downloading -> {
                        bar.visibility = View.VISIBLE
                        bar.isIndeterminate = false
                        bar.setProgressCompat(state.percent, true)
                        status.text = activity.getString(
                            R.string.update_card_downloading, state.source, state.percent,
                            state.attempt, state.total
                        )
                        closeButton.isEnabled = true
                        backgroundButton.visibility = View.VISIBLE
                    }
                    is UpdateManager.State.Ready -> {
                        bar.visibility = View.VISIBLE
                        bar.isIndeterminate = false
                        bar.setProgressCompat(100, true)
                        status.text = activity.getString(R.string.update_card_ready)
                        closeButton.isEnabled = false
                        backgroundButton.visibility = View.GONE
                        delay(1200)
                        dismiss()
                    }
                    is UpdateManager.State.Failed -> {
                        bar.visibility = View.GONE
                        status.text = activity.getString(R.string.update_card_failed)
                        closeButton.isEnabled = true
                        backgroundButton.visibility = View.GONE
                    }
                    is UpdateManager.State.Cancelled -> dismiss()
                }
            }
        }
    }

    fun dismiss() {
        renderJob?.cancel()
        renderJob = null
        dialog?.let { d ->
            if (d.isShowing) d.dismiss()
        }
        dialog = null
    }
}
