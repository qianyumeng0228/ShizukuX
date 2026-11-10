package af.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import af.shizuku.manager.R
import af.shizuku.manager.battery.BatteryWhitelistActivity
import af.shizuku.manager.databinding.HomeBatteryWhitelistItemBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * Home card for the platform's battery-optimisation whitelist.
 *
 * Like the autostart and firewall cards, this opens even when the service is stopped: the page
 * explains what it needs instead of the entry point disappearing.
 */
class BatteryWhitelistViewHolder(
    private val binding: HomeBatteryWhitelistItemBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<ServiceStatus>(containerBinding.root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeBatteryWhitelistItemBinding.inflate(inflater, outer.cardContent, true)
            BatteryWhitelistViewHolder(inner, outer)
        }
    }

    init {
        containerBinding.root.setOnClickListener(this)
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@BatteryWhitelistViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
    }

    private val originalIcon = binding.icon.drawable
    private inline val title get() = binding.text1
    private inline val summary get() = binding.text2
    private inline val iconView get() = binding.icon

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(iconView, originalIcon, "home_battery_whitelist")
        itemView.isEnabled = true
        title.setText(R.string.battery_whitelist_title)
        summary.setText(R.string.home_battery_whitelist_description)
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, BatteryWhitelistActivity::class.java),
            iconView,
            "icon_battery_whitelist"
        )
    }
}
