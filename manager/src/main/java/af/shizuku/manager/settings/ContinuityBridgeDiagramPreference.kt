package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class ContinuityBridgeDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_continuity_bridge_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val nodePhone = holder.findViewById(R.id.cont_node_phone) as? LinearLayout
        val nodeSync = holder.findViewById(R.id.cont_node_sync) as? LinearLayout
        val nodeTarget = holder.findViewById(R.id.cont_node_target) as? LinearLayout

        val line1 = holder.findViewById(R.id.cont_line_1)
        val line2 = holder.findViewById(R.id.cont_line_2)

        val contIcon = holder.findViewById(R.id.cont_icon) as? ImageView
        val statusText = holder.findViewById(R.id.cont_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodePhone?.setBackgroundResource(R.drawable.shape_node_active)
            nodeSync?.setBackgroundResource(R.drawable.shape_node_active)
            nodeTarget?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            contIcon?.setTint(activeColor)
            statusText?.text = "Continuity Bridge is active. UI bounds and layout changes are synchronized in real-time."
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodePhone?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeSync?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeTarget?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            contIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "Continuity Bridge is currently disabled. Layout changes are not synchronized."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("continuity_bridge_enabled", false) ?: false
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
