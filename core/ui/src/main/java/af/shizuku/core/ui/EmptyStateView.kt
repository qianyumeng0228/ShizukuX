package af.shizuku.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import af.shizuku.core.ui.databinding.EmptyStateViewBinding

/**
 * A reusable empty state view that displays an icon, title, description, and optional action button.
 * Theme-aware: works in both light and dark modes.
 */
class EmptyStateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: EmptyStateViewBinding

    init {
        binding = EmptyStateViewBinding.inflate(LayoutInflater.from(context), this, true)

        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.EmptyStateView,
            0, 0
        ).apply {
            try {
                val iconRes = getResourceId(R.styleable.EmptyStateView_emptyIcon, R.drawable.ic_help_outline_24)
                val titleStr = getString(R.styleable.EmptyStateView_emptyTitle) ?: context.getString(R.string.empty_state_title_no_results)
                val descriptionStr = getString(R.styleable.EmptyStateView_emptyDescription) ?: context.getString(R.string.empty_state_description_no_results)
                val actionTextStr = getString(R.styleable.EmptyStateView_emptyActionText)

                icon = iconRes
                title = titleStr
                description = descriptionStr
                if (!actionTextStr.isNullOrEmpty()) {
                    actionText = actionTextStr
                }
            } finally {
                recycle()
            }
        }
    }

    var icon: Int = 0
        set(@DrawableRes value) {
            field = value
            binding.emptyStateIcon.setImageResource(value)
        }

    var title: CharSequence = ""
        set(value) {
            field = value
            binding.emptyStateTitle.text = value
        }

    var description: CharSequence = ""
        set(value) {
            field = value
            binding.emptyStateDescription.text = value
        }

    var actionText: CharSequence = ""
        set(value) {
            field = value
            binding.emptyStateActionButton.text = value
            binding.emptyStateActionButton.visibility = if (value.isNotEmpty()) View.VISIBLE else View.GONE
        }

    fun setIconResource(@DrawableRes iconRes: Int) {
        icon = iconRes
    }

    fun setTitleResource(@StringRes titleRes: Int) {
        title = context.getText(titleRes)
    }

    fun setDescriptionResource(@StringRes descriptionRes: Int) {
        description = context.getText(descriptionRes)
    }

    fun setActionTextResource(@StringRes actionTextRes: Int) {
        actionText = context.getText(actionTextRes)
    }

    fun hideActionButton() {
        binding.emptyStateActionButton.visibility = View.GONE
    }

    fun showActionButton() {
        if (actionText.isNotEmpty()) {
            binding.emptyStateActionButton.visibility = View.VISIBLE
        }
    }

    fun setActionClickListener(listener: OnClickListener?) {
        binding.emptyStateActionButton.setOnClickListener(listener)
    }

    fun setActionClickListener(listener: () -> Unit) {
        binding.emptyStateActionButton.setOnClickListener { listener() }
    }
}
