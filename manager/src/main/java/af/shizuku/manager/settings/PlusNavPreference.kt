package af.shizuku.manager.settings

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import af.shizuku.manager.R

class PlusNavPreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val titleView = holder.findViewById(android.R.id.title) as? TextView ?: return
        titleView.isSingleLine = false
        applyPlusBadge(titleView)
    }

    private fun applyPlusBadge(titleView: TextView) {
        val tv = TypedValue()
        val bgColor = if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimaryContainer, tv, true)) tv.data
                      else 0xFFE8DEF8.toInt()
        val fgColor = if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, tv, true)) tv.data
                      else 0xFF21005D.toInt()
        val original = titleView.text
        val spannable = SpannableStringBuilder(original).apply {
            append("  ")
            val start = length
            append(" PLUS ")
            val end = length
            setSpan(BackgroundColorSpan(bgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(fgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.65f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        titleView.text = spannable
    }
}
