package af.shizuku.manager.widget

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Adapter for the external relay authorization screen ([af.shizuku.manager.settings.ExternalRelayActivity]).
 *
 * Lists an auto-authorization header (toggle + accessibility enable), relay entries for Scene and
 * Brevent, plus a placeholder for future apps.
 */
class ExternalRelayAdapter(
    private val context: Context,
    private val onActivateScene: () -> Unit,
    private val onActivateBrevent: () -> Unit,
    private val onAutoToggle: (Boolean) -> Unit,
    private val onOpenAccessibility: () -> Unit,
    private val onEnableOwnerWireless: () -> Unit,
    private val onRequestIgnoreBatteryOptimization: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_OWNER = 1
        private const val TYPE_SCENE = 2
        private const val TYPE_BREVENT = 3
        private const val TYPE_PLACEHOLDER = 4

        /** Component name of the external-relay auto-activation accessibility service. */
        private fun autoServiceComponent(packageName: String): String =
            "$packageName/af.shizuku.manager.adb.ExternalRelayAutoService"
    }

    /** Ordered list of relay entries; extend this when adding new relayed apps. */
    val items = mutableListOf<Int>()

    init {
        items.add(TYPE_HEADER)
        items.add(TYPE_OWNER)
        items.add(TYPE_SCENE)
        items.add(TYPE_BREVENT)
        items.add(TYPE_PLACEHOLDER)
    }

    override fun getItemViewType(position: Int): Int = items[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(
                inflater.inflate(R.layout.item_external_relay_header, parent, false)
            )
            TYPE_OWNER -> OwnerViewHolder(
                inflater.inflate(R.layout.item_external_relay_owner, parent, false)
            )
            TYPE_SCENE -> SceneViewHolder(
                inflater.inflate(R.layout.item_external_relay_scene, parent, false)
            )
            TYPE_BREVENT -> BreventViewHolder(
                inflater.inflate(R.layout.item_external_relay_brevent, parent, false)
            )
            else -> PlaceholderViewHolder(
                inflater.inflate(R.layout.item_external_relay_placeholder, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val vh = holder) {
            is HeaderViewHolder -> vh.bind()
            is OwnerViewHolder -> vh.bind()
            is SceneViewHolder -> vh.bind()
            is BreventViewHolder -> vh.bind()
            is PlaceholderViewHolder -> vh.bind()
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val autoSwitch: MaterialSwitch = itemView.findViewById(R.id.auto_switch)
        private val accessibilityStatus: TextView = itemView.findViewById(R.id.accessibility_status)
        private val openAccessibilityButton: MaterialButton = itemView.findViewById(R.id.open_accessibility_button)
        private val batteryStatus: TextView = itemView.findViewById(R.id.battery_status)
        private val batteryButton: MaterialButton = itemView.findViewById(R.id.battery_button)

        fun bind() {
            autoSwitch.isChecked = ShizukuSettings.getExternalRelayAuto()
            autoSwitch.setOnCheckedChangeListener { _, isChecked ->
                onAutoToggle(isChecked)
            }
            refreshAccessibilityStatus()
            openAccessibilityButton.setOnClickListener {
                onOpenAccessibility()
            }
            refreshBatteryStatus()
            batteryButton.setOnClickListener {
                onRequestIgnoreBatteryOptimization()
            }
        }

        fun refreshAccessibilityStatus() {
            val enabled = isAutoAccessibilityEnabled()
            accessibilityStatus.text = context.getString(
                if (enabled) R.string.external_relay_auto_accessibility_on
                else R.string.external_relay_auto_accessibility_off
            )
            openAccessibilityButton.isEnabled = !enabled
        }

        fun refreshBatteryStatus() {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)
            batteryStatus.text = context.getString(
                if (isIgnoring) R.string.external_relay_auto_battery_ok
                else R.string.external_relay_auto_battery_off
            )
            batteryButton.isEnabled = !isIgnoring
        }

        private fun isAutoAccessibilityEnabled(): Boolean {
            return try {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val component = autoServiceComponent(context.packageName)
                enabled.split(':').any { it.equals(component, ignoreCase = true) }
            } catch (e: Exception) {
                false
            }
        }
    }

    inner class OwnerViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val statusText: TextView = itemView.findViewById(R.id.owner_status)
        private val resultText: TextView = itemView.findViewById(R.id.owner_result)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.owner_action_button)

        fun bind() {
            val ownerActive = af.shizuku.manager.settings.DeviceOwnerHelper.isDeviceOwner(context)
            statusText.text = context.getString(
                if (ownerActive) R.string.external_relay_owner_enabled
                else R.string.external_relay_owner_disabled
            )
            actionButton.isEnabled = ownerActive
            actionButton.setOnClickListener {
                resultText.visibility = android.view.View.VISIBLE
                resultText.text = context.getString(R.string.external_relay_owner_activating)
                onEnableOwnerWireless()
            }
        }

        /** Called by the activity when the owner-enable attempt finishes. */
        fun showResult(success: Boolean, message: String?) {
            resultText.visibility = android.view.View.VISIBLE
            resultText.setTextColor(
                context.getColor(
                    if (success) android.R.color.holo_green_dark
                    else android.R.color.holo_red_dark
                )
            )
            resultText.text = message ?: context.getString(
                if (success) R.string.external_relay_owner_done
                else R.string.external_relay_owner_failed
            )
        }
    }

    inner class SceneViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.action_button)

        fun bind() {
            actionButton.setOnClickListener {
                statusText.text = context.getString(R.string.external_relay_scene_activating)
                statusText.visibility = android.view.View.VISIBLE
                onActivateScene()
            }
        }
    }

    inner class BreventViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.action_button)

        fun bind() {
            actionButton.setOnClickListener {
                statusText.text = context.getString(R.string.external_relay_brevent_activating)
                statusText.visibility = android.view.View.VISIBLE
                onActivateBrevent()
            }
        }
    }

    inner class PlaceholderViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind() {
            // Static placeholder card; nothing to bind today.
        }
    }
}
