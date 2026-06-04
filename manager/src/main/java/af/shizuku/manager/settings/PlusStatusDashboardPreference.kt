package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class PlusStatusDashboardPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_plus_status_dashboard
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val items = listOf(
            Triple("storage_proxy_enabled", R.id.dash_storage_bridge, R.id.dot_storage_bridge),
            Triple("activity_manager_plus_enabled", R.id.dash_process_control, R.id.dot_process_control),
            Triple("ai_core_plus_enabled", R.id.dash_ai_bridge, R.id.dot_ai_bridge),
            Triple("avf_manager_enabled", R.id.dash_vm_manager, R.id.dot_vm_manager),
            Triple("network_governor_plus_enabled", R.id.dash_dns_governor, R.id.dot_dns_governor),
            Triple("shell_interceptor_enabled", R.id.dash_shell_accelerator, R.id.dot_shell_accelerator)
        )

        for ((prefKey, containerId, dotId) in items) {
            val container = holder.findViewById(containerId) as? LinearLayout
            val dot = holder.findViewById(dotId)

            val isEnabled = isPrefEnabled(prefKey)

            if (isEnabled) {
                container?.setBackgroundResource(R.drawable.shape_node_active)
                container?.alpha = 1.0f
                dot?.setBackgroundResource(R.drawable.shape_status_indicator)
                dot?.backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.system_accent1_600))
            } else {
                container?.setBackgroundResource(R.drawable.shape_node_inactive)
                container?.alpha = 0.45f
                dot?.setBackgroundResource(R.drawable.shape_status_indicator)
                dot?.backgroundTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.system_neutral_outline))
            }
        }
    }

    private fun isPrefEnabled(key: String): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean(key, true) ?: true
    }
}
