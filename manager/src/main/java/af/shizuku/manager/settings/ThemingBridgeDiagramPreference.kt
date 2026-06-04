package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class ThemingBridgeDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_theming_bridge_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val card = holder.itemView as? com.google.android.material.card.MaterialCardView
        card?.setOnClickListener {
            val parentFragment = (context as? androidx.fragment.app.FragmentActivity)
                ?.supportFragmentManager?.findFragmentById(R.id.fragment_container)
            if (parentFragment is ShizukuPlusSettingsFragment) {
                parentFragment.findPreference<Preference>("overlay_manager_plus_enabled")?.let {
                    parentFragment.onPreferenceTreeClick(it)
                }
            }
        }

        val nodeSys = holder.findViewById(R.id.theme_node_sys) as? LinearLayout
        val nodeBridge = holder.findViewById(R.id.theme_node_bridge) as? LinearLayout
        val nodeCustom = holder.findViewById(R.id.theme_node_custom) as? LinearLayout

        val line1 = holder.findViewById(R.id.theme_line_1)
        val line2 = holder.findViewById(R.id.theme_line_2)

        val bridgeIcon = holder.findViewById(R.id.theme_bridge_icon) as? ImageView
        val statusText = holder.findViewById(R.id.theme_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodeSys?.setBackgroundResource(R.drawable.shape_node_active)
            nodeBridge?.setBackgroundResource(R.drawable.shape_node_active)
            nodeCustom?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            bridgeIcon?.setTint(activeColor)
            statusText?.text = "Theming Bridge is active. System UI and layout resources are actively overridden with personalized aesthetics."
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodeSys?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeBridge?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeCustom?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            bridgeIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "Theming Bridge is currently disabled. Stock interface resources are unmodified."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("overlay_manager_plus_enabled", true) ?: true
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
