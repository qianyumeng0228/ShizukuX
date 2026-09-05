package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat

open class GrayableIconSwitchPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SwitchPreferenceCompat(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<ImageView>(android.R.id.icon)
            ?.alpha = if (isChecked) 1.0f else 0.38f
    }
}
