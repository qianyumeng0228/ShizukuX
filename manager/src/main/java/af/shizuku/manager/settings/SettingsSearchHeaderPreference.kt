package af.shizuku.manager.settings

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class SettingsSearchHeaderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_settings_search_header
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.findViewById(R.id.search_bar_card)?.setOnClickListener {
            val activity = context as? Activity
            // Programmatically trigger the search action item located in the toolbar
            activity?.findViewById<android.view.View>(R.id.action_search)?.performClick()
        }
    }
}
