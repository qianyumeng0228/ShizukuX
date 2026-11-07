package af.shizuku.manager.utils

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import af.shizuku.manager.R
import af.shizuku.manager.ktx.cardSurfaceColor
import af.shizuku.manager.ktx.glassStrokeColor
import af.shizuku.manager.ktx.themeColor
import af.shizuku.manager.ktx.themeCornerSizePx

/**
 * Base ItemDecoration for Material 3 Expressive card-style lists.
 * Handles background card drawing and dividers with consistent spacing.
 */
abstract class M3ECardItemDecoration(context: Context) : RecyclerView.ItemDecoration() {
    protected val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Liquid-glass skin: the card is a translucent pane - a vertical white gradient fill plus a
    // 1dp hairline stroke - instead of the stock opaque cardSurfaceColor().
    private val glassFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val isGlassSkin = af.shizuku.manager.ShizukuSettings.isGlassUi()
    private var darkGlass = false
    // Matches every other 28dp/ExtraLarge card in the app (see #333) and follows the Shape
    // Style setting (Modern/Classic/Squircle) instead of a fixed radius.
    protected val cornerRadius = context.themeCornerSizePx(com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge)
    protected val cardMargin = context.resources.getDimension(R.dimen.m3e_spacing_medium)
    protected val density = context.resources.displayMetrics.density

    init {
        cardPaint.color = context.cardSurfaceColor()
        dividerPaint.color = context.themeColor(R.attr.colorOutlineVariant)
        dividerPaint.strokeWidth = 1f * density

        if (isGlassSkin) {
            val dark = (context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
            glassFillPaint.color = context.cardSurfaceColor()
            glassStrokePaint.style = Paint.Style.STROKE
            glassStrokePaint.strokeWidth = 1f * density
            glassStrokePaint.color = context.glassStrokeColor()
            this.darkGlass = dark
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val count = parent.childCount
        if (count == 0) return

        var currentCardTop = Float.MIN_VALUE
        var lastItemBottom = Float.MIN_VALUE

        for (i in 0 until count) {
            val child = parent.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue

            if (isHeader(child)) {
                if (currentCardTop != Float.MIN_VALUE) {
                    drawCard(c, parent, currentCardTop, lastItemBottom)
                }
                currentCardTop = child.top.toFloat()
                lastItemBottom = child.bottom.toFloat()

                // Draw a divider under the header if it's expanded (i.e. has visible children)
                if (shouldDrawDivider(parent, i, count)) {
                    val left = child.left.toFloat() + getDividerInset(child)
                    val right = child.right.toFloat() - getDividerEndInset(child)
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, dividerPaint)
                }
            } else {
                if (currentCardTop == Float.MIN_VALUE) {
                    currentCardTop = child.top.toFloat()
                }
                lastItemBottom = child.bottom.toFloat()

                if (shouldDrawDivider(parent, i, count)) {
                    val left = child.left.toFloat() + getDividerInset(child)
                    val right = child.right.toFloat() - getDividerEndInset(child)
                    val y = child.bottom.toFloat()
                    c.drawLine(left, y, right, y, dividerPaint)
                }
            }
        }

        if (currentCardTop != Float.MIN_VALUE) {
            drawCard(c, parent, currentCardTop, lastItemBottom)
        }
    }

    protected open fun isHeader(view: View): Boolean = false

    protected open fun getDividerInset(view: View): Float = 56f * density

    protected open fun getDividerEndInset(view: View): Float = 16f * density

    protected open fun shouldDrawDivider(parent: RecyclerView, index: Int, count: Int): Boolean {
        return false
    }

    protected fun drawCard(c: Canvas, parent: RecyclerView, top: Float, bottom: Float) {
        if (isGlassSkin) {
            if (bottom > top) {
                glassFillPaint.shader = android.graphics.LinearGradient(
                    0f, top, 0f, bottom,
                    if (darkGlass) GLASS_TOP_DARK else GLASS_TOP_LIGHT,
                    if (darkGlass) GLASS_BOTTOM_DARK else GLASS_BOTTOM_LIGHT,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }
            val r = cornerRadius
            c.drawRoundRect(cardMargin, top, parent.width - cardMargin, bottom, r, r, glassFillPaint)
            glassFillPaint.shader = null
            c.drawRoundRect(cardMargin, top, parent.width - cardMargin, bottom, r, r, glassStrokePaint)
            return
        }
        c.drawRoundRect(cardMargin, top, parent.width - cardMargin, bottom, cornerRadius, cornerRadius, cardPaint)
    }

    private companion object {
        // Top of the glass pane: a touch brighter than its bottom edge, per the design spec
        // (rgba(255,255,255,.10) -> .045 on dark, .55 -> .42 on light).
        val GLASS_TOP_DARK = 0x1AFFFFFF
        val GLASS_BOTTOM_DARK = 0x0CFFFFFF
        val GLASS_TOP_LIGHT = 0x8CFFFFFF.toInt()
        val GLASS_BOTTOM_LIGHT = 0x6BFFFFFF.toInt()
    }
}
