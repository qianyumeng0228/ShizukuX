package af.shizuku.manager.home

import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        StockShizukuCompat.startViaStockShizuku()
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        android.widget.Toast.makeText(activity, "Starting ShizukuX via original Shizuku...", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onBind() {
        start.isEnabled = true
        start.text = "Start via original Shizuku"
        binding.title.text = "Setup via Original Shizuku"
        binding.text1.text = "The original Shizuku is currently running. You can use its root privileges to automatically start ShizukuX and stop the original service."
        binding.icon.setImageResource(R.drawable.ic_bolt_24)
    }
}
