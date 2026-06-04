package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings

class UiSettingsHeaderPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    init {
        layoutResource = R.layout.layout_ui_settings_header
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val container1 = holder.findViewById(R.id.mock_icon_container_1) as? FrameLayout
        val container2 = holder.findViewById(R.id.mock_icon_container_2) as? FrameLayout
        val container3 = holder.findViewById(R.id.mock_icon_container_3) as? FrameLayout
        
        val descText = holder.findViewById(R.id.preview_description) as? TextView

        val shapeStyle = ShizukuSettings.getShapeStyle() // "zen", "modern", "classic", "squircle"
        val isExpressive = ShizukuSettings.isExpressiveShapesEnabled()

        val density = context.resources.displayMetrics.density
        // Use surface container color for contrast
        val accentColor = context.getColor(R.color.system_accent1_100)

        // Draw asymmetrical or rounded shapes dynamically
        fun applyShape(container: FrameLayout?, isDroplet: Boolean, isLeaf: Boolean) {
            container ?: return
            val shapeModel = if (!isExpressive) {
                // Classic standard rounded corners
                ShapeAppearanceModel.builder()
                    .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, 8f * density)
                    .build()
            } else {
                when (shapeStyle) {
                    "classic" -> ShapeAppearanceModel.builder()
                        .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, 8f * density)
                        .build()
                    "modern" -> ShapeAppearanceModel.builder()
                        .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, 18f * density)
                        .build()
                    "squircle" -> ShapeAppearanceModel.builder()
                        .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, 24f * density)
                        .build()
                    "zen", else -> {
                        // Asymmetrical Shape Treatments
                        if (isDroplet) {
                            ShapeAppearanceModel.builder()
                                .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 28f * density)
                                .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 28f * density)
                                .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 8f * density)
                                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 8f * density)
                                .build()
                        } else if (isLeaf) {
                            ShapeAppearanceModel.builder()
                                .setTopRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 28f * density)
                                .setBottomLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 28f * density)
                                .setTopLeftCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 8f * density)
                                .setBottomRightCorner(com.google.android.material.shape.CornerFamily.ROUNDED, 8f * density)
                                .build()
                        } else {
                            ShapeAppearanceModel.builder()
                                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, 18f * density)
                                .build()
                        }
                    }
                }
            }

            val drawable = MaterialShapeDrawable(shapeModel).apply {
                fillColor = android.content.res.ColorStateList.valueOf(accentColor)
            }
            container.background = drawable
        }

        applyShape(container1, isDroplet = true, isLeaf = false)
        applyShape(container2, isDroplet = false, isLeaf = true)
        applyShape(container3, isDroplet = false, isLeaf = false)

        descText?.text = if (!isExpressive) {
            "Classic standard 8dp container rounded corners."
        } else {
            when (shapeStyle) {
                "classic" -> "Classic Material 2 standard rounded shapes."
                "modern" -> "Modern Material 3 extra-large rounded containers."
                "squircle" -> "Organic squircle containers for smooth continuous rounding."
                "zen", else -> "Zen asymmetrical leaf and droplet icon container shapes."
            }
        }
    }
}
