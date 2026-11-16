package af.shizuku.manager.settings

import android.content.Context
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.utils.SettingsHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Adapter for the external relay authorization screen ([af.shizuku.manager.settings.ExternalRelayActivity]).
 *
 * Lists an auto-authorization header (toggle + accessibility enable), then relay entries for
 * Scene and Brevent.
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

        /** Component name of the external-relay auto-activation accessibility service. */
        private fun autoServiceComponent(packageName: String): String =
            "$packageName/af.shizuku.manager.adb.ExternalRelayAutoService"
    }

    /** Latest probe results for the two relayed apps. null means "not probed yet": the chip stays
     *  hidden instead of claiming a state we have not actually measured. */
    private var sceneActive: Boolean? = null
    private var breventActive: Boolean? = null

    /** True while an activation is in flight. The card shows an "activating" line in that window;
     *  before this flag existed the line was written on click and nothing ever hid it again, so a
     *  finished (or failed) run left "正在激活…" on screen indefinitely. */
    private var sceneBusy = false
    private var breventBusy = false

    /** Marks Scene's activation as started/finished; re-binds the card so the status line tracks it. */
    fun setSceneBusy(busy: Boolean) {
        if (sceneBusy == busy) return
        sceneBusy = busy
        val position = items.indexOf(TYPE_SCENE)
        if (position >= 0) notifyItemChanged(position)
    }

    /** Same contract as [setSceneBusy], for Brevent. */
    fun setBreventBusy(busy: Boolean) {
        if (breventBusy == busy) return
        breventBusy = busy
        val position = items.indexOf(TYPE_BREVENT)
        if (position >= 0) notifyItemChanged(position)
    }

    /** Pushes the Scene probe result in. Call from the main thread after the IO probe returns. */
    fun updateSceneState(active: Boolean?) {
        if (sceneActive == active) return
        sceneActive = active
        val position = items.indexOf(TYPE_SCENE)
        if (position >= 0) notifyItemChanged(position)
    }

    /** Pushes the Brevent probe result in. Call from the main thread after the IO probe returns. */
    fun updateBreventState(active: Boolean?) {
        if (breventActive == active) return
        breventActive = active
        val position = items.indexOf(TYPE_BREVENT)
        if (position >= 0) notifyItemChanged(position)
    }

    /**
     * Paints the state chip. Active uses the M3 primary container pair, inactive the surface
     * variant pair, so the chip reads as "filled = working" in both light and dark themes and
     * never hard-codes green/grey outside the palette.
     */
    private fun renderStateChip(chip: TextView, active: Boolean?) {
        if (active == null) {
            chip.visibility = android.view.View.GONE
            return
        }
        chip.visibility = android.view.View.VISIBLE
        chip.text = context.getString(
            if (active) R.string.external_relay_state_active
            else R.string.external_relay_state_inactive
        )
        val bgAttr = if (active) com.google.android.material.R.attr.colorPrimaryContainer
        else com.google.android.material.R.attr.colorSurfaceVariant
        val fgAttr = if (active) com.google.android.material.R.attr.colorOnPrimaryContainer
        else com.google.android.material.R.attr.colorOnSurfaceVariant
        chip.setTextColor(themeColor(fgAttr))
        chip.backgroundTintList =
            android.content.res.ColorStateList.valueOf(themeColor(bgAttr))
    }

    private fun themeColor(attr: Int): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    /** Ordered list of relay entries; extend this when adding new relayed apps. */
    val items = mutableListOf<Int>()

    init {
        items.add(TYPE_HEADER)
        items.add(TYPE_OWNER)
        items.add(TYPE_SCENE)
        items.add(TYPE_BREVENT)
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
            else -> error("Unexpected view type $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val vh = holder) {
            is HeaderViewHolder -> vh.bind()
            is OwnerViewHolder -> vh.bind()
            is SceneViewHolder -> vh.bind()
            is BreventViewHolder -> vh.bind()
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
            // 统一判定：MIUI/HyperOS 的"无限制"不一定写入 AOSP whitelist，用户已进过电池页即视为已处理
            val isIgnoring = SettingsHelper.isBatteryOptimizationEffectivelyDisabled(context)
            batteryStatus.text = context.getString(
                if (isIgnoring) R.string.external_relay_auto_battery_ok
                else R.string.external_relay_auto_battery_off
            )
            batteryButton.text = context.getString(
                if (isIgnoring) R.string.external_relay_battery_settings
                else R.string.external_relay_auto_battery_optimize
            )
            // Always clickable: once exempt the button opens the system battery-settings list
            // instead of pretending to "disable" something that is already disabled.
            batteryButton.isEnabled = true
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
        private val stateChip: TextView = itemView.findViewById(R.id.state_chip)
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.action_button)

        fun bind() {
            renderStateChip(stateChip, sceneActive)
            if (sceneBusy) {
                statusText.text = context.getString(R.string.external_relay_scene_activating)
                statusText.visibility = android.view.View.VISIBLE
            } else {
                statusText.visibility = android.view.View.GONE
            }
            actionButton.setOnClickListener {
                setSceneBusy(true)
                onActivateScene()
            }
        }
    }

    inner class BreventViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private val stateChip: TextView = itemView.findViewById(R.id.state_chip)
        private val statusText: TextView = itemView.findViewById(R.id.status_text)
        private val actionButton: MaterialButton = itemView.findViewById(R.id.action_button)

        fun bind() {
            renderStateChip(stateChip, breventActive)
            if (breventBusy) {
                statusText.text = context.getString(R.string.external_relay_brevent_activating)
                statusText.visibility = android.view.View.VISIBLE
            } else {
                statusText.visibility = android.view.View.GONE
            }
            actionButton.setOnClickListener {
                setBreventBusy(true)
                onActivateBrevent()
            }
        }
    }
}
