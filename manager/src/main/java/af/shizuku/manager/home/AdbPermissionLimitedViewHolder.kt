package af.shizuku.manager.home

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import af.shizuku.manager.Helps
import af.shizuku.manager.databinding.HomeExtraStepRequiredBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.IconStyleHelper
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class AdbPermissionLimitedViewHolder(private val binding: HomeExtraStepRequiredBinding, root: View) : BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeExtraStepRequiredBinding.inflate(inflater, outer.cardContent, true)
            AdbPermissionLimitedViewHolder(inner, outer.root)
        }
    }

    init {
        binding.button1.setOnClickListener { v: View -> CustomTabsHelper.launchUrlOrCopy(v.context, Helps.ADB_PERMISSION.get()) }
    }

    override fun onBind() {
        // This card was missed in the earlier icon-styling pass: its pill always used the static
        // shape_droplet_background XML drawable (via CardIcon.Droplet), ignoring the shape_style
        // setting (zen/modern/classic/squircle) that every other card's icon now follows. Keeps
        // the same semantic error colors the XML had, just routed through pillBackground so the
        // shape stays in sync with the rest of the app.
        val context = binding.icon.context
        val onErrorContainer = context.themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        val errorContainer = context.themeColor(com.google.android.material.R.attr.colorErrorContainer)
        binding.icon.background = IconStyleHelper.pillBackground(context, onErrorContainer)
        binding.icon.imageTintList = ColorStateList.valueOf(errorContainer)
    }
}
