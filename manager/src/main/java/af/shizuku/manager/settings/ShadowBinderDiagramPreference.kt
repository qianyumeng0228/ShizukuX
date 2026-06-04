package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class ShadowBinderDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_shadow_binder_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val card = holder.itemView as? com.google.android.material.card.MaterialCardView
        card?.setOnClickListener {
            val parentFragment = (context as? androidx.fragment.app.FragmentActivity)
                ?.supportFragmentManager?.findFragmentById(R.id.fragment_container)
            if (parentFragment is ShizukuPlusSettingsFragment) {
                parentFragment.findPreference<Preference>("shadow_binder_enabled")?.let {
                    parentFragment.onPreferenceTreeClick(it)
                }
            }
        }

        val nodeApp = holder.findViewById(R.id.shadow_node_app) as? LinearLayout
        val nodeMask = holder.findViewById(R.id.shadow_node_mask) as? LinearLayout
        val nodePackages = holder.findViewById(R.id.shadow_node_packages) as? LinearLayout

        val line1 = holder.findViewById(R.id.shadow_line_1)
        val line2 = holder.findViewById(R.id.shadow_line_2)

        val maskIcon = holder.findViewById(R.id.mask_icon) as? ImageView
        val statusText = holder.findViewById(R.id.shadow_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodeApp?.setBackgroundResource(R.drawable.shape_node_active)
            nodeMask?.setBackgroundResource(R.drawable.shape_node_active)
            nodePackages?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            maskIcon?.setTint(activeColor)
            statusText?.text = "Shadow Binder is active. Queried packages are cloaked and invisible to specific querying apps."
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodeApp?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeMask?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodePackages?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            maskIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "Shadow Binder is currently disabled. No packages are hidden."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("shadow_binder_enabled", false) ?: false
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
