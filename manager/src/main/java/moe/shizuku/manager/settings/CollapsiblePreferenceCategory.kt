package moe.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import moe.shizuku.manager.R

class CollapsiblePreferenceCategory @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PreferenceCategory(context, attrs) {

    private var expanded = false
    var onExpansionChanged: ((Boolean) -> Unit)? = null

    private var defaultExpanded = false

    init {
        layoutResource = R.layout.collapsible_preference_category_card
        isSelectable = true

        val a = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.defaultValue))
        defaultExpanded = a.getBoolean(0, false)
        expanded = defaultExpanded
        a.recycle()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        holder.itemView.tag = "category_header"

        // Sync arrow to current state without animation on first bind
        val arrow = holder.findViewById(R.id.category_arrow)
        arrow?.rotation = if (expanded) 0f else 180f

        holder.itemView.setOnClickListener {
            expanded = !expanded
            if (shouldPersist()) persistBoolean(expanded)
            updateChildren()
            onExpansionChanged?.invoke(expanded)
            notifyChanged()
            // Animate arrow with M3E spring-style motion
            arrow?.animate()
                ?.rotation(if (expanded) 0f else 180f)
                ?.setDuration(300)
                ?.setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                ?.start()
        }
    }

    fun isExpanded() = expanded

    fun setExpanded(expanded: Boolean) {
        if (this.expanded != expanded) {
            this.expanded = expanded
            if (shouldPersist()) persistBoolean(expanded)
            updateChildren()
            onExpansionChanged?.invoke(expanded)
            notifyChanged()
        }
    }

    private fun updateChildren() {
        for (i in 0 until preferenceCount) {
            getPreference(i).isVisible = expanded
        }
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        // Restore persisted state if we have a key, otherwise use defaultValue
        if (shouldPersist()) {
            expanded = getPersistedBoolean(defaultExpanded)
        }
        updateChildren()
    }

    override fun addPreference(preference: Preference): Boolean {
        val result = super.addPreference(preference)
        if (result) {
            preference.isVisible = expanded
        }
        return result
    }
}
