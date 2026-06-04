package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R

class SUBridgeDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_su_bridge_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val card = holder.itemView as? com.google.android.material.card.MaterialCardView
        card?.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle("SU Bridge Integration")
                .setMessage("The SU Bridge intercepts classic root calls (the 'su' command) from older or unoptimized applications and routes them safely through Shizuku's secure daemon APIs.\n\nThis lets you run legacy root apps without full device root privileges.")
                .setPositiveButton("OK", null)
                .show()
        }

        val nodeClient = holder.findViewById(R.id.su_node_client) as? LinearLayout
        val nodeBridge = holder.findViewById(R.id.su_node_bridge) as? LinearLayout
        val nodeShizuku = holder.findViewById(R.id.su_node_shizuku) as? LinearLayout

        val line1 = holder.findViewById(R.id.su_line_1)
        val line2 = holder.findViewById(R.id.su_line_2)

        val bridgeIcon = holder.findViewById(R.id.su_bridge_icon) as? ImageView
        val statusText = holder.findViewById(R.id.su_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodeClient?.setBackgroundResource(R.drawable.shape_node_active)
            nodeBridge?.setBackgroundResource(R.drawable.shape_node_active)
            nodeShizuku?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            bridgeIcon?.setTint(activeColor)
            statusText?.text = "SU Bridge is active. Older root apps will be intercepted and routed through Shizuku."
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodeClient?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeBridge?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeShizuku?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            bridgeIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "SU Bridge is disabled. Apps demanding root will fail unless they support Shizuku natively."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("su_bridge_enabled", false) ?: false
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
