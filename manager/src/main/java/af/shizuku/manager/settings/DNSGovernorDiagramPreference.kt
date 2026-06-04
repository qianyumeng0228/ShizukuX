package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class DNSGovernorDiagramPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_dns_governor_diagram
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val card = holder.itemView as? com.google.android.material.card.MaterialCardView
        card?.setOnClickListener {
            val parentFragment = (context as? androidx.fragment.app.FragmentActivity)
                ?.supportFragmentManager?.findFragmentById(R.id.fragment_container)
            if (parentFragment is ShizukuPlusSettingsFragment) {
                parentFragment.findPreference<Preference>("network_governor_plus_enabled")?.let {
                    parentFragment.onPreferenceTreeClick(it)
                }
            }
        }

        val nodeReq = holder.findViewById(R.id.dns_node_req) as? LinearLayout
        val nodeGov = holder.findViewById(R.id.dns_node_gov) as? LinearLayout
        val nodeResolved = holder.findViewById(R.id.dns_node_resolved) as? LinearLayout

        val line1 = holder.findViewById(R.id.dns_line_1)
        val line2 = holder.findViewById(R.id.dns_line_2)

        val govIcon = holder.findViewById(R.id.dns_gov_icon) as? ImageView
        val statusText = holder.findViewById(R.id.dns_status_text) as? TextView

        val isEnabled = isPrefEnabled()

        if (isEnabled) {
            nodeReq?.setBackgroundResource(R.drawable.shape_node_active)
            nodeGov?.setBackgroundResource(R.drawable.shape_node_active)
            nodeResolved?.setBackgroundResource(R.drawable.shape_node_active)

            val activeColor = context.getColor(R.color.system_accent1_600)
            line1?.setBackgroundColor(activeColor)
            line2?.setBackgroundColor(activeColor)

            govIcon?.setTint(activeColor)
            statusText?.text = "DNS Governor is active. Local requests are governed and speed-optimized."
            statusText?.setTextColor(context.getColor(R.color.system_accent1_700))
        } else {
            nodeReq?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeGov?.setBackgroundResource(R.drawable.shape_node_inactive)
            nodeResolved?.setBackgroundResource(R.drawable.shape_node_inactive)

            val inactiveColor = context.getColor(R.color.system_neutral_outline)
            line1?.setBackgroundColor(inactiveColor)
            line2?.setBackgroundColor(inactiveColor)

            govIcon?.setTint(context.getColor(R.color.system_neutral_variant40))
            statusText?.text = "DNS Governor is currently disabled. Stock local network settings are used."
            statusText?.setTextColor(context.getColor(R.color.system_neutral_variant60))
        }
    }

    private fun isPrefEnabled(): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean("network_governor_plus_enabled", true) ?: true
    }

    private fun ImageView.setTint(color: Int) {
        this.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }
}
