package moe.shizuku.manager.utils

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.button.MaterialButton
import moe.shizuku.manager.R

/**
 * A reusable empty state view that displays an icon, title, description, and optional action button.
 * Theme-aware: works in both light and dark modes.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val iconView: ImageView
    private val titleView: TextView
    private val descriptionView: TextView
    private val actionButton: MaterialButton

    init {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.empty_state_view, this, true)

        iconView = view.findViewById(R.id.empty_state_icon)
        titleView = view.findViewById(R.id.empty_state_title)
        descriptionView = view.findViewById(R.id.empty_state_description)
        actionButton = view.findViewById(R.id.empty_state_action_button)

        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.EmptyStateView,
            0, 0
        ).apply {
            try {
                val iconRes = getResourceId(R.styleable.EmptyStateView_emptyIcon, R.drawable.ic_help_outline_24dp)
                val titleRes = getResourceId(R.styleable.EmptyStateView_emptyTitle, R.string.empty_state_title_no_results)
                val descriptionRes = getResourceId(R.styleable.EmptyStateView_emptyDescription, R.string.empty_state_description_no_results)
                val actionTextRes = getResourceId(R.styleable.EmptyStateView_emptyActionText, 0)

                setIcon(iconRes)
                setTitle(titleRes)
                setDescription(descriptionRes)
                if (actionTextRes != 0) {
                    setActionText(actionTextRes)
                }
            } finally {
                recycle()
            }
        }
    }

    /**
     * Set the icon to display.
     * @param iconRes Drawable resource ID for the icon
     */
    fun setIcon(@DrawableRes iconRes: Int) {
        iconView.setImageResource(iconRes)
    }

    /**
     * Set the title text.
     * @param titleRes String resource ID for the title
     */
    fun setTitle(@StringRes titleRes: Int) {
        titleView.setText(titleRes)
    }

    /**
     * Set the title text.
     * @param title Title string
     */
    fun setTitle(title: CharSequence) {
        titleView.text = title
    }

    /**
     * Set the description text.
     * @param descriptionRes String resource ID for the description
     */
    fun setDescription(@StringRes descriptionRes: Int) {
        descriptionView.setText(descriptionRes)
    }

    /**
     * Set the description text.
     * @param description Description string
     */
    fun setDescription(description: CharSequence) {
        descriptionView.text = description
    }

    /**
     * Set the action button text and make it visible.
     * @param actionTextRes String resource ID for the action button text
     */
    fun setActionText(@StringRes actionTextRes: Int) {
        actionButton.setText(actionTextRes)
        actionButton.visibility = View.VISIBLE
    }

    /**
     * Set the action button text and make it visible.
     * @param actionText Action button text
     */
    fun setActionText(actionText: CharSequence) {
        actionButton.text = actionText
        actionButton.visibility = View.VISIBLE
    }

    /**
     * Hide the action button.
     */
    fun hideActionButton() {
        actionButton.visibility = View.GONE
    }

    /**
     * Show the action button.
     */
    fun showActionButton() {
        if (actionButton.text.isNotEmpty()) {
            actionButton.visibility = View.VISIBLE
        }
    }

    /**
     * Set click listener for the action button.
     * @param listener Click listener
     */
    fun setActionClickListener(listener: OnClickListener?) {
        actionButton.setOnClickListener(listener)
    }

    /**
     * Set click listener for the action button.
     * @param listener Click listener
     */
    fun setActionClickListener(listener: () -> Unit) {
        actionButton.setOnClickListener { listener() }
    }
}
