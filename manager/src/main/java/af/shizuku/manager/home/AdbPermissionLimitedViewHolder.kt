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
import com.google.android.material.card.MaterialCardView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
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
        binding.button2.setOnClickListener { v: View ->
            v.context.showAdbPermissionGuide()
        }
    }

    override fun onBind() {
        // This card's status pill is a plain circle now that the icon-shape preferences are gone;
        // it keeps the semantic error colors by routing through pillBackground.
        val context = binding.icon.context
        val onErrorContainer = context.themeColor(com.google.android.material.R.attr.colorOnErrorContainer)
        val errorContainer = context.themeColor(com.google.android.material.R.attr.colorErrorContainer)
        IconStyleHelper.applyToStatusCardIcon(binding.icon, pillColor = onErrorContainer, tintColor = errorContainer)

        // The error fill used to be a plain XML colour on the inner ConstraintLayout. Because the
        // card content is inset by 16dp, that painted a square red block inside the rounded glass
        // card. Build the same fill as a MaterialShapeDrawable using the outer card's exact radius,
        // so the red panel keeps the same corner curvature as the glass frame.
        val card = itemView as? MaterialCardView
        val outerRadius = card?.radius ?: 0f
        val shape = card?.shapeAppearanceModel ?: ShapeAppearanceModel.builder()
            .setAllCornerSizes(outerRadius)
            .build()
        binding.root.background = MaterialShapeDrawable(shape).apply {
            fillColor = ColorStateList.valueOf(errorContainer)
        }
        // MaterialShapeDrawable only paints the rounded fill; children still lay out in a
        // rectangle. The 48dp warning tile sits in the panel's top-left corner, so without this
        // it pokes a square dark-red corner through the rounded fill. Clip children to the same
        // outline as the red panel.
        binding.root.clipToOutline = true
    }
}
