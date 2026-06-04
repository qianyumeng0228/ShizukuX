package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class AICoreBridgeDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_ai_bridge_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val nodeShell = holder.findViewById(R.id.ai_node_shell) as? LinearLayout
        val nodeCore = holder.findViewById(R.id.ai_node_core) as? LinearLayout
        val nodeNpu = holder.findViewById(R.id.ai_node_npu) as? LinearLayout

        val line1 = holder.findViewById(R.id.ai_line_1)
        val line2 = holder.findViewById(R.id.ai_line_2)

        val aiIcon = holder.findViewById(R.id.ai_icon) as? ImageView
        val statusText = holder.findViewById(R.id.ai_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodeShell?.setBackgroundResource(R.drawable.shape_node_active)
            nodeCore?.setBackgroundResource(R.drawable.shape_node_active)
            nodeNpu?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            aiIcon?.setTint(activeColor)
            statusText?.text = "AI Bridge is active. Automation requests route via high-priority NPU processing units."
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodeShell?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeCore?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeNpu?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            aiIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "AI Bridge is currently disabled. Shell task optimizations route via standard execution threads."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("ai_core_plus_enabled", false) ?: false
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
