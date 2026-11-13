package com.voicekiosk.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Atmospheric bottom glow background view (soft colored arc).
 * Renders a subtle cyan-to-purple curved gradient arc at the bottom of the screen,
 * faithfully matching Screenshots 1 (Portrait) and 4 (Landscape).
 */
class AssistantGlowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * context.resources.displayMetrics.density
    }

    private val arcPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        // 1. Radial ambient glow originating from bottom-center
        val radialShader = RadialGradient(
            w / 2f,
            h.toFloat(),
            w * 0.55f,
            intArrayOf(
                Color.parseColor("#2E06B6D4"), // Soft Cyan
                Color.parseColor("#1A8B5CF6"), // Soft Purple
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = radialShader

        // 2. Arc curve path (subtle upwards arc at the bottom)
        arcPath.reset()
        arcPath.moveTo(0f, h.toFloat())
        arcPath.quadTo(w / 2f, h * 0.25f, w.toFloat(), h.toFloat())

        // 3. Subtle stroke gradient for the arc line
        val arcShader = LinearGradient(
            0f, h.toFloat(), w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#3822D3EE"), // Cyan highlight at center
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        arcPaint.shader = arcShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        canvas.drawPath(arcPath, arcPaint)
    }
}
