package af.shizuku.manager.home

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.core.content.asActivity
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartRootBinding
import af.shizuku.manager.utils.StockShizukuCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class StartStockShizukuViewHolder(
    private val binding: HomeStartRootBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Boolean>(containerBinding.root) {

    companion object {
        val CREATOR = Creator<Boolean> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartRootBinding.inflate(inflater, outer.cardContent, true)
            StartStockShizukuViewHolder(inner, outer)
        }
    }

    private inline val start get() = binding.button1
    private inline val restart get() = binding.button2

    init {
        val listener = View.OnClickListener { v: View -> onStartClicked(v) }
        start.setOnClickListener(listener)
        restart.visibility = View.GONE
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun onStartClicked(v: View) {
        if (af.shizuku.manager.migration.MigrationHelper.isRootAvailable()) {
            val starterCmd = af.shizuku.manager.starter.Starter.internalCommand
            val cmd = "am force-stop moe.shizuku.privileged.api && am force-stop af.shizuku.plus.api && nohup sh -c 'sleep 1 && $starterCmd' >/dev/null 2>&1 &"
            val activity = v.context.asActivity<android.app.Activity>() ?: return
            start.isEnabled = false
            // Shell.cmd().exec() runs the su/shell invocation synchronously; on the calling
            // (main) thread that risks jank or an ANR if root takes a moment to attach.
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    com.topjohnwu.superuser.Shell.cmd(cmd).exec()
                } catch (e: Exception) {
                    // Ignore
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    start.isEnabled = true
                    android.widget.Toast.makeText(activity, "Restarting via Root...", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            val activity = v.context.asActivity<android.app.Activity>() ?: return
            com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle("Incompatible Server Running")
                .setMessage("The original Shizuku server is currently running in the background. Because of Android security constraints, ShizukuX cannot communicate with or kill the original server.\n\nPlease completely restart your device to kill the original server, then start ShizukuX using ADB or Root.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    override fun onBind() {
        start.isEnabled = true
        start.text = "Fix Conflict"
        binding.title.text = "Incompatible Server Detected"
        binding.text1.text = "The original Shizuku server is running in the background. It is incompatible with ShizukuX and blocks it from starting."
        binding.icon.setImageResource(R.drawable.ic_warning_24)

        // Use a warning color for the icon if possible
        binding.icon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
    }
}
