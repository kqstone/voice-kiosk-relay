package com.voicekiosk.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.voicekiosk.app.R

/**
 * Custom page indicator for ViewPager2.
 * Renders the active page as a white pill (18dp x 5dp) and inactive pages as grey dots (5dp x 5dp).
 * Dynamically interpolates width and color during horizontal swiping gestures for 60fps transitions.
 */
class PageIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    private var pillWidth = 18f * density
    private var dotWidth = 5f * density
    private var itemHeight = 5f * density
    private var cornerRadius = 2.5f * density
    private var dotSpacing = 6f * density

    private var activeColor = Color.WHITE
    private var inactiveColor = Color.parseColor("#475569")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var pageCount = 0
    private var selectedPosition = 0
    private var positionOffset = 0f

    init {
        try {
            pillWidth = context.resources.getDimension(R.dimen.indicator_pill_width)
            dotWidth = context.resources.getDimension(R.dimen.indicator_dot_width)
            itemHeight = context.resources.getDimension(R.dimen.indicator_height)
            cornerRadius = context.resources.getDimension(R.dimen.indicator_corner_radius)
            dotSpacing = context.resources.getDimension(R.dimen.indicator_spacing)
        } catch (_: Exception) {
            // Keep default density-scaled dimensions
        }

        try {
            activeColor = context.getColor(R.color.indicator_active)
            inactiveColor = context.getColor(R.color.indicator_inactive)
        } catch (_: Exception) {
            // Keep default hex colors
        }
    }

    fun setPageCount(count: Int) {
        val coerced = count.coerceAtLeast(0)
        if (pageCount != coerced) {
            pageCount = coerced
            requestLayout()
            invalidate()
        }
    }

    fun onPageScrolled(position: Int, offset: Float) {
        this.selectedPosition = position
        this.positionOffset = offset.coerceIn(0f, 1f)
        invalidate()
    }

    fun onPageSelected(position: Int) {
        this.selectedPosition = position
        this.positionOffset = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = if (pageCount <= 0) {
            0f
        } else if (pageCount == 1) {
            pillWidth
        } else {
            pillWidth + (pageCount - 1) * dotWidth + (pageCount - 1) * dotSpacing
        }
        val totalHeight = itemHeight

        val desiredWidth = (totalWidth + paddingLeft + paddingRight).toInt()
        val desiredHeight = (totalHeight + paddingTop + paddingBottom).toInt()

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageCount <= 1) return

        val totalWidth = pillWidth + (pageCount - 1) * dotWidth + (pageCount - 1) * dotSpacing
        var currentX = paddingLeft + (width - paddingLeft - paddingRight - totalWidth) / 2f
        val top = paddingTop + (height - paddingTop - paddingBottom - itemHeight) / 2f
        val bottom = top + itemHeight

        val pos = selectedPosition.coerceIn(0, pageCount - 1)
        val offset = positionOffset.coerceIn(0f, 1f)

        for (i in 0 until pageCount) {
            val currentItemWidth: Float
            val currentItemColor: Int

            if (i == pos) {
                // Shrinking from pill to dot
                currentItemWidth = pillWidth - (pillWidth - dotWidth) * offset
                currentItemColor = interpolateColor(activeColor, inactiveColor, offset)
            } else if (i == pos + 1) {
                // Expanding from dot to pill
                currentItemWidth = dotWidth + (pillWidth - dotWidth) * offset
                currentItemColor = interpolateColor(inactiveColor, activeColor, offset)
            } else {
                currentItemWidth = dotWidth
                currentItemColor = inactiveColor
            }

            paint.color = currentItemColor
            canvas.drawRoundRect(
                currentX,
                top,
                currentX + currentItemWidth,
                bottom,
                cornerRadius,
                cornerRadius,
                paint
            )
            currentX += currentItemWidth + dotSpacing
        }
    }

    private fun interpolateColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(startColor) + f * (Color.alpha(endColor) - Color.alpha(startColor))).toInt()
        val r = (Color.red(startColor) + f * (Color.red(endColor) - Color.red(startColor))).toInt()
        val g = (Color.green(startColor) + f * (Color.green(endColor) - Color.green(startColor))).toInt()
        val b = (Color.blue(startColor) + f * (Color.blue(endColor) - Color.blue(startColor))).toInt()
        return Color.argb(a, r, g, b)
    }
}
