package moe.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R

class PlusFeaturePreference(context: Context, attrs: AttributeSet) : SwitchPreferenceCompat(context, attrs) {
    
    private val infoTitle: Int
    private val infoDetail: Int

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PlusFeaturePreference)
        infoTitle = a.getResourceId(R.styleable.PlusFeaturePreference_infoTitle, 0)
        infoDetail = a.getResourceId(R.styleable.PlusFeaturePreference_infoDetail, 0)
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        
        // Find the title view's parent container
        val titleView = holder.findViewById(android.R.id.title) as? TextView ?: return
        val parent = titleView.parent as? ViewGroup ?: return
        
        // Check if we've already added the info icon
        if (parent.findViewWithTag<ImageView>("help_info_icon") == null && infoDetail != 0) {
            val iconSize = (18 * context.resources.displayMetrics.density).toInt()
            val infoIcon = ImageView(context).apply {
                tag = "help_info_icon"
                setImageResource(R.drawable.ic_help_outline_24dp)
                val params = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    marginStart = (8 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setOnClickListener {
                    showHelp()
                }
            }
            parent.addView(infoIcon)
        }
        
        // Also support long-press on the whole preference for accessibility
        holder.itemView.setOnLongClickListener {
            showHelp()
            true
        }
    }

    private fun showHelp() {
        if (infoDetail != 0) {
            MaterialAlertDialogBuilder(context)
                .setTitle(if (infoTitle != 0) infoTitle else R.string.settings_plus_learn_more)
                .setMessage(infoDetail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
