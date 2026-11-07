package af.shizuku.manager.home

import android.content.Intent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeModulesItemBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.module.ModulesActivity
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * Home card for the ADB module system.
 *
 * The module manager itself does NOT require a running service for install / enable /
 * delete (those are plain file operations under filesDir/adb_modules). Only action.sh /
 * service.sh need the Shizuku binder, so this card stays clickable while the service is
 * stopped - it just opens a page that explains what is unavailable.
 */
class ModulesViewHolder(
    private val binding: HomeModulesItemBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<ServiceStatus>(containerBinding.root), View.OnClickListener {

    companion object {
        val CREATOR = Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeModulesItemBinding.inflate(inflater, outer.cardContent, true)
            ModulesViewHolder(inner, outer)
        }
    }

    init {
        containerBinding.root.setOnClickListener(this)
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@ModulesViewHolder)
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
        IconStyleHelper.applyToCardIcon(iconView, originalIcon, "home_modules")
        itemView.isEnabled = true
        title.setText(af.shizuku.manager.R.string.modules_title)
        summary.setText(af.shizuku.manager.R.string.home_modules_description)
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, ModulesActivity::class.java),
            iconView,
            "icon_modules"
        )
    }
}
