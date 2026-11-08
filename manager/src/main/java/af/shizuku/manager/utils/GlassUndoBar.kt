package af.shizuku.manager.utils

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.WeakHashMap

/**
 * The undo strip every "I just hid an app" result needs, drawn as a plain view instead of a
 * Snackbar.
 *
 * A Material Snackbar is not usable in this app: the container runs on the platform theme, so
 * inflating its action button dies on `Failed to resolve attribute ... textAllCaps` (k2113), and
 * the Compose SnackbarHost variant could not be tapped because the native list kept consuming the
 * touch (k2107/k2108). Both hosts - the pager container and the standalone activity - therefore
 * share this one builder, so hiding an app looks and behaves the same in either place.
 *
 * Lives in the window content view above the floating tab bar: [TAB_BAR_INSET_DP] + 12dp clears
 * the 138dp scrim so the strip lands just above the pill instead of behind it.
 */
object GlassUndoBar {

    /** Height of the container's bottom scrim; the strip sits on top of it. */
    const val TAB_BAR_INSET_DP = 138

    private val bars = WeakHashMap<Any, View>()
    private val timeouts = WeakHashMap<Any, Runnable>()

    /**
     * Shows [message] with an action labelled [actionLabel]. [owner] scopes the strip, so a page
     * that raises a second one replaces the first instead of stacking them.
     *
     * Falls back to a plain toast when [anchor] is null - a strip without an anchor would be
     * dropped silently, and losing the way back is worse than losing the button.
     */
    fun show(
        owner: Any,
        activity: Activity,
        anchor: ViewGroup?,
        message: String,
        actionLabel: String,
        action: () -> Unit
    ) {
        if (anchor == null) {
            android.widget.Toast.makeText(activity, message, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        dismiss(owner)

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(4), 0)
            minimumHeight = dp(52)
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0xFF2E2F38.toInt())
            }
            elevation = dp(6).toFloat()
        }
        bar.addView(TextView(activity).apply {
            text = message
            setTextColor(0xFFECECF1.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(TextView(activity).apply {
            text = actionLabel
            setTextColor(0xFFB9A7FF.toInt())
            textSize = 15f
            isClickable = true
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setOnClickListener {
                action()
                dismiss(owner)
            }
        })

        anchor.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(TAB_BAR_INSET_DP + 12)
            marginStart = dp(12)
            marginEnd = dp(12)
        })

        bars[owner] = bar
        val timeout = Runnable { dismiss(owner) }
        timeouts[owner] = timeout
        bar.postDelayed(timeout, 10_000L)
    }

    fun dismiss(owner: Any) {
        val bar = bars.remove(owner) ?: return
        timeouts.remove(owner)?.let { bar.removeCallbacks(it) }
        (bar.parent as? ViewGroup)?.removeView(bar)
    }
}
