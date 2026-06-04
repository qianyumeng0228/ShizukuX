package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class VMManagerDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_vm_manager_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val nodeHost = holder.findViewById(R.id.vm_node_host) as? LinearLayout
        val nodeHypervisor = holder.findViewById(R.id.vm_node_hypervisor) as? LinearLayout
        val nodeSandbox = holder.findViewById(R.id.vm_node_sandbox) as? LinearLayout

        val line1 = holder.findViewById(R.id.vm_line_1)
        val line2 = holder.findViewById(R.id.vm_line_2)

        val vmIcon = holder.findViewById(R.id.vm_icon) as? ImageView
        val statusText = holder.findViewById(R.id.vm_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodeHost?.setBackgroundResource(R.drawable.shape_node_active)
            nodeHypervisor?.setBackgroundResource(R.drawable.shape_node_active)
            nodeSandbox?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            vmIcon?.setTint(activeColor)
            statusText?.text = "VM Manager is active. Isolated Virtual Machine execution sandboxes are successfully routing via the AVF Hypervisor."
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodeHost?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeHypervisor?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeSandbox?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            vmIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "VM Manager is currently disabled. Processes execute directly on the host system."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("avf_manager_enabled", false) ?: false
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
