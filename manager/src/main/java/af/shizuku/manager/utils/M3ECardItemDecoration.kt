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
    // Matches every other 28dp/ExtraLarge card in the app (see #333). The radius comes from the
    // active theme token (classic base / One UI / glass overlay); the user shape override was
    // removed from Appearance.
    protected val cornerRadius = context.themeCornerSizePx(com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge)
    // Same 16dp gutter as home and the Compose pages, so native list cards, terminal guide cards
    // and Compose cards share one edge. Rows add their own 16dp content padding.
    protected val cardMargin = context.resources.getDimension(R.dimen.glass_card_margin)
    protected val density = context.resources.displayMetrics.density

    init {
        cardPaint.color = context.cardSurfaceColor()
        dividerPaint.color = context.themeColor(R.attr.colorOutlineVariant)
        dividerPaint.strokeWidth = 1f * density

        if (isGlassSkin) {
            glassFillPaint.color = context.cardSurfaceColor()
            glassStrokePaint.style = Paint.Style.STROKE
            glassStrokePaint.strokeWidth = 1f * density
            glassStrokePaint.color = context.glassStrokeColor()
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
                // The header is a card of its own (MaterialCardView with its own fill and margin).
                // Starting the row card at the header's top made the two fills overlap, which in
                // the light glass skin showed as a second rounded edge under "toggle all".
                // Reset, so the row card starts at the first real row instead.
                currentCardTop = Float.MIN_VALUE
                lastItemBottom = Float.MIN_VALUE
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
            val r = cornerRadius
            c.drawRoundRect(cardMargin, top, parent.width - cardMargin, bottom, r, r, glassFillPaint)
            c.drawRoundRect(cardMargin, top, parent.width - cardMargin, bottom, r, r, glassStrokePaint)
            return
        }
        c.drawRoundRect(cardMargin, top, parent.width - cardMargin, bottom, cornerRadius, cornerRadius, cardPaint)
    }

    private companion object {
        // Edge light stays visible while the centre remains clear enough to show the backdrop.
        val GLASS_TOP_DARK = 0x24FFFFFF
        val GLASS_BOTTOM_DARK = 0x12FFFFFF
        val GLASS_TOP_LIGHT = 0xB8FFFFFF.toInt()
        val GLASS_BOTTOM_LIGHT = 0x8CFFFFFF.toInt()
    }
}
