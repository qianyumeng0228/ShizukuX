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

class BinderFirewallDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_binder_firewall_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val nodeApp = holder.findViewById(R.id.firewall_node_app) as? LinearLayout
        val nodeGate = holder.findViewById(R.id.firewall_node_gate) as? LinearLayout
        val nodeStatus = holder.findViewById(R.id.firewall_node_status) as? LinearLayout

        val line1 = holder.findViewById(R.id.firewall_line_1)
        val line2 = holder.findViewById(R.id.firewall_line_2)

        val gateIcon = holder.findViewById(R.id.gate_icon) as? ImageView
        val statusIcon = holder.findViewById(R.id.status_icon) as? ImageView
        val statusLabel = holder.findViewById(R.id.status_label) as? TextView
        val statusText = holder.findViewById(R.id.firewall_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodeApp?.setBackgroundResource(R.drawable.shape_node_active)
            nodeGate?.setBackgroundResource(R.drawable.shape_node_active)
            nodeStatus?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            gateIcon?.setTint(activeColor)
            statusIcon?.setImageResource(R.drawable.ic_admin_panel_settings_24)
            statusIcon?.setTint(activeColor)
            statusLabel?.text = "Blocked"
            statusLabel?.setTextColor(activeColor)
            statusText?.text = "Binder Firewall is active. Unverified connections are securely blocked at the gateway!"
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodeApp?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeGate?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeStatus?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            gateIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusIcon?.setImageResource(R.drawable.ic_close_24)
            statusIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusLabel?.text = "Bypassed"
            statusLabel?.setTextColor(context.getColor(R.color.system_neutral_variant60))
            statusText?.text = "Binder Firewall is currently disabled. All packages can connect unrestricted."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("binder_firewall_enabled", false) ?: false
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
