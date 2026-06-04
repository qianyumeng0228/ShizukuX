package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class StorageBridgeDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_storage_bridge_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val nodeApp = holder.findViewById(R.id.flow_node_app) as? LinearLayout
        val nodeBridge = holder.findViewById(R.id.flow_node_bridge) as? LinearLayout
        val nodeStorage = holder.findViewById(R.id.flow_node_storage) as? LinearLayout

        val line1 = holder.findViewById(R.id.flow_line_1)
        val line2 = holder.findViewById(R.id.flow_line_2)

        val bridgeIcon = holder.findViewById(R.id.bridge_icon) as? ImageView
        val statusText = holder.findViewById(R.id.flow_status_text) as? TextView

        val isEnabled = ShizukuSettings.isStorageProxyEnabled()

        if (isEnabled) {
            // Apply Active Theme Style
            nodeApp?.setBackgroundResource(R.drawable.shape_node_active)
            nodeBridge?.setBackgroundResource(R.drawable.shape_node_active)
            nodeStorage?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            bridgeIcon?.setTint(activeColor)
            statusText?.text = "Storage Bridge is active. File requests bypass slow SAF, routing directly through ShizukuX lightning-fast file proxy!"
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            // Apply Inactive Theme Style
            nodeApp?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeBridge?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeStorage?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            bridgeIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "Storage Bridge is currently disabled. File access routes directly via slow Android SAF API."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
