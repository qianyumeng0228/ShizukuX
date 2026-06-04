package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.TwoStatePreference
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class HomeLayoutSimulatorPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_home_layout_simulator
        isSelectable = false
    }

    private var fragment: BaseSettingsFragment? = null

    fun setFragment(fragment: BaseSettingsFragment) {
        this.fragment = fragment
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val keys = listOf(
            "show_start_adb_home" to Pair(R.id.sim_start_adb, R.id.eye_start_adb),
            "show_terminal_home" to Pair(R.id.sim_terminal, R.id.eye_terminal),
            "show_automation_home" to Pair(R.id.sim_automation, R.id.eye_automation),
            "show_activity_log_home" to Pair(R.id.sim_activity_log, R.id.eye_activity_log),
            "show_learn_more_home" to Pair(R.id.sim_learn_more, R.id.eye_learn_more)
        )

        for ((prefKey, views) in keys) {
            val (blockId, eyeId) = views
            val block = holder.findViewById(blockId) as? LinearLayout
            val eye = holder.findViewById(eyeId) as? ImageView

            val isVisible = isPrefEnabled(prefKey)
            updateBlockUi(block, eye, isVisible)

            block?.setOnClickListener {
                val newVisible = !isPrefEnabled(prefKey)
                savePref(prefKey, newVisible)
                updateBlockUi(block, eye, newVisible)

                // Sync with the actual list switches below
                fragment?.findPreference<TwoStatePreference>(prefKey)?.let { switchPref ->
                    switchPref.isChecked = newVisible
                }
            }
        }
    }

    private fun isPrefEnabled(key: String): Boolean {
        return preferenceManager.sharedPreferences?.getBoolean(key, key != "show_start_adb_home") ?: true
    }

    private fun savePref(key: String, value: Boolean) {
        preferenceManager.sharedPreferences?.edit()?.putBoolean(key, value)?.apply()
    }

    private fun updateBlockUi(block: LinearLayout?, eye: ImageView?, isVisible: Boolean) {
        block ?: return
        eye ?: return

        if (isVisible) {
            block.setBackgroundResource(R.drawable.shape_node_active)
            block.alpha = 1.0f
            eye.setImageResource(R.drawable.ic_visibility_24)
            eye.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.system_accent1_600))
        } else {
            block.setBackgroundResource(R.drawable.shape_node_inactive)
            block.alpha = 0.5f
            eye.setImageResource(R.drawable.ic_visibility_off_24)
            eye.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(R.color.system_neutral_outline))
        }
    }
}
