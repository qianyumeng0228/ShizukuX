package af.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeDeviceItemBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.device.DeviceInfoActivity
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * Home card for device information.
 *
 * Opens whether or not the service is running: the framework half of the page works without it,
 * and the shell half is what explains the missing service.
 */
class DeviceInfoViewHolder(
    private val binding: HomeDeviceItemBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<ServiceStatus>(containerBinding.root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeDeviceItemBinding.inflate(inflater, outer.cardContent, true)
            DeviceInfoViewHolder(inner, outer)
        }
    }

    init {
        containerBinding.root.setOnClickListener(this)
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@DeviceInfoViewHolder)
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
        IconStyleHelper.applyToCardIcon(iconView, originalIcon, "home_device")
        itemView.isEnabled = true
        title.setText(R.string.device_title)
        summary.setText(R.string.home_device_description)
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, DeviceInfoActivity::class.java),
            iconView,
            "icon_device"
        )
    }
}
