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

    fun setIcon(@DrawableRes iconRes: Int) {
        binding.emptyStateIcon.setImageResource(iconRes)
    }

    var icon: Int
        get() = 0 // Not really usable as getter
        set(value) = setIcon(value)

    fun setTitle(@StringRes titleRes: Int) {
        binding.emptyStateTitle.setText(titleRes)
    }

    fun setTitle(title: CharSequence) {
        binding.emptyStateTitle.text = title
    }

    var title: CharSequence
        get() = binding.emptyStateTitle.text
        set(value) = setTitle(value)

    fun setDescription(@StringRes descriptionRes: Int) {
        binding.emptyStateDescription.setText(descriptionRes)
    }

    fun setDescription(description: CharSequence) {
        binding.emptyStateDescription.text = description
    }

    var description: CharSequence
        get() = binding.emptyStateDescription.text
        set(value) = setDescription(value)

    fun setActionText(@StringRes actionTextRes: Int) {
        binding.emptyStateActionButton.setText(actionTextRes)
        binding.emptyStateActionButton.visibility = View.VISIBLE
    }

    fun setActionText(actionText: CharSequence) {
        binding.emptyStateActionButton.text = actionText
        binding.emptyStateActionButton.visibility = View.VISIBLE
    }

    var actionText: CharSequence
        get() = binding.emptyStateActionButton.text
        set(value) = setActionText(value)

    fun hideActionButton() {
        binding.emptyStateActionButton.visibility = View.GONE
    }

    fun showActionButton() {
        if (binding.emptyStateActionButton.text.isNotEmpty()) {
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
