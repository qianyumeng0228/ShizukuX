package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import af.shizuku.manager.R

class PlusFeaturePreference(context: Context, attrs: AttributeSet) : SwitchPreferenceCompat(context, attrs) {

    private val infoTitle: Int
    private val infoDetail: Int
    private val badgeType: Int
    private var integrationPackage: String? = null
    private var integrationAppName: String? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PlusFeaturePreference)
        infoTitle = a.getResourceId(R.styleable.PlusFeaturePreference_infoTitle, 0)
        infoDetail = a.getResourceId(R.styleable.PlusFeaturePreference_infoDetail, 0)
        badgeType = a.getInt(R.styleable.PlusFeaturePreference_badgeType, 0)
        a.recycle()
    }

    fun setIntegration(packageName: String, appName: String) {
        this.integrationPackage = packageName
        this.integrationAppName = appName
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val titleView = holder.findViewById(android.R.id.title) as? TextView

        if (titleView != null && infoDetail != 0) {
            val iconRes = if (integrationPackage != null)
                R.drawable.ic_outline_open_in_new_24
            else
                R.drawable.ic_help_outline_24

            val drawable = context.getDrawable(iconRes)
            val iconSize = (18 * context.resources.displayMetrics.density).toInt()
            drawable?.setBounds(0, 0, iconSize, iconSize)
            titleView.setCompoundDrawablesRelative(null, null, drawable, null)
            titleView.compoundDrawablePadding =
                (4 * context.resources.displayMetrics.density).toInt()
        } else {
            titleView?.setCompoundDrawablesRelative(null, null, null, null)
        }

        // Dynamically add a beautiful Material You badge chip next to the title
        if (titleView != null && badgeType != 0) {
            val parent = titleView.parent as? android.view.ViewGroup
            if (parent != null && parent.tag != "badge_container_added") {
                val index = parent.indexOfChild(titleView)
                parent.removeView(titleView)

                // Create a horizontal container for title + badge
                val container = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    tag = "badge_container_added"
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                // Add titleView to container
                val titleParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
                titleView.layoutParams = titleParams
                container.addView(titleView)

                // Add beautiful Material CardView Badge
                val badgeView = com.google.android.material.card.MaterialCardView(context).apply {
                    radius = (6 * context.resources.displayMetrics.density)
                    strokeWidth = 0
                    cardElevation = 0f
                    val badgeMargin = (6 * context.resources.displayMetrics.density).toInt()
                    val params = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = badgeMargin
                    }
                    layoutParams = params
                }

                val badgeTextView = TextView(context).apply {
                    textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(
                        (8 * context.resources.displayMetrics.density).toInt(),
                        (2 * context.resources.displayMetrics.density).toInt(),
                        (8 * context.resources.displayMetrics.density).toInt(),
                        (2 * context.resources.displayMetrics.density).toInt()
                    )
                }

                when (badgeType) {
                    1 -> { // plus
                        badgeTextView.text = "PLUS"
                        badgeTextView.setTextColor(context.getColor(android.R.color.white))
                        badgeView.setCardBackgroundColor(context.getColor(R.color.system_accent1_600))
                    }
                    2 -> { // root
                        badgeTextView.text = "ROOT"
                        badgeTextView.setTextColor(context.getColor(android.R.color.white))
                        badgeView.setCardBackgroundColor(context.getColor(R.color.system_accent2_600))
                    }
                    3 -> { // experimental
                        badgeTextView.text = "BETA"
                        badgeTextView.setTextColor(context.getColor(android.R.color.white))
                        badgeView.setCardBackgroundColor(context.getColor(R.color.system_accent3_600))
                    }
                }

                badgeView.addView(badgeTextView)
                container.addView(badgeView)

                parent.addView(container, index)
            }
        }

        holder.itemView.setOnLongClickListener {
            if (integrationPackage != null) launchIntegration() else showHelp()
            true
        }
    }

    private fun launchIntegration() {
        val pkg = integrationPackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            android.widget.Toast.makeText(
                context, R.string.app_management_no_launcher, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val typedValue = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(attr, typedValue, true)
        return if (resolved) typedValue.data else fallback
    }

    private fun showHelp() {
        if (infoDetail != 0) {
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
            
            // Outer container
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(
                    (24 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (24 * context.resources.displayMetrics.density).toInt(),
                    (24 * context.resources.displayMetrics.density).toInt()
                )
                // Use theme surface background
                setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt()))
            }

            // Drag handle indicator
            val dragHandle = android.view.View(context).apply {
                val params = android.widget.LinearLayout.LayoutParams(
                    (36 * context.resources.displayMetrics.density).toInt(),
                    (4 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    bottomMargin = (16 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFFCCCCCC.toInt()))
            }
            container.addView(dragHandle)

            // Title
            val titleTextView = TextView(context).apply {
                text = context.getString(if (infoTitle != 0) infoTitle else R.string.settings_plus_learn_more)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt()))
                setPadding(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
            }
            container.addView(titleTextView)

            // Detail Card (container for content)
            val cardView = com.google.android.material.card.MaterialCardView(context).apply {
                radius = (16 * context.resources.displayMetrics.density)
                strokeWidth = 0
                cardElevation = 0f
                setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF5F5F5.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (20 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
            }

            val detailTextView = TextView(context).apply {
                text = context.getString(infoDetail)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF333333.toInt()))
                setLineSpacing(0f, 1.25f)
                setPadding(
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt()
                )
            }
            cardView.addView(detailTextView)
            container.addView(cardView)

            // Interactive Switch Card
            val switchCard = com.google.android.material.card.MaterialCardView(context).apply {
                radius = (16 * context.resources.displayMetrics.density)
                strokeWidth = 0
                cardElevation = 0f
                setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE0F2F1.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (24 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
            }

            val switchLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (12 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (12 * context.resources.displayMetrics.density).toInt()
                )
            }

            val switchText = TextView(context).apply {
                text = "Enable feature"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF004D40.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = params
            }
            switchLayout.addView(switchText)

            val mSwitch = com.google.android.material.materialswitch.MaterialSwitch(context).apply {
                isChecked = this@PlusFeaturePreference.isChecked
                setOnCheckedChangeListener { _, isCheckedVal ->
                    this@PlusFeaturePreference.isChecked = isCheckedVal
                    this@PlusFeaturePreference.callChangeListener(isCheckedVal)
                }
            }
            switchLayout.addView(mSwitch)
            switchCard.addView(switchLayout)
            container.addView(switchCard)

            // Dismiss Button
            val closeButton = com.google.android.material.button.MaterialButton(context).apply {
                text = "Close"
                cornerRadius = (24 * context.resources.displayMetrics.density).toInt()
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (48 * context.resources.displayMetrics.density).toInt()
                )
                layoutParams = params
                setOnClickListener { dialog.dismiss() }
            }
            container.addView(closeButton)

            dialog.setContentView(container)
            dialog.show()
        }
    }
}
