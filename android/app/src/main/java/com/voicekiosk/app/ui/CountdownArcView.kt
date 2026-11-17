package com.voicekiosk.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Custom glowing circular countdown arc view.
 *
 * Requirements:
 * - Hardware-accelerated Canvas drawArc and ValueAnimator.
 * - MUST NOT trigger frequent layout passes (uses invalidate(), never requestLayout() on ticks).
 * - OLED pure-black minimal style: black background, amber glowing progress ring (#F59E0B), white digits (#FFFFFF).
 */
class CountdownArcView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Current progress: 1.0f (full countdown remaining) to 0.0f (completed)
    private var progress: Float = 1.0f
    private var timeText: String = "00:00"
    private var labelText: String? = null

    // Geometry cached across onDraw passes (calculated exclusively in onSizeChanged)
    private val arcBounds = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var diameter = 0f

    // Hardware-accelerated paint objects
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#27272A") // OLED subtle dark ring track
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.argb(80, 245, 158, 11) // Soft amber outer halo
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#F59E0B") // Core vibrant amber (#F59E0B)
    }

    private val digitsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8") // text_secondary
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private var progressAnimator: ValueAnimator? = null

    init {
        // Enforce hardware-accelerated drawing pipeline
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f

        val density = resources.displayMetrics.density
        val strokeWidth = 5.5f * density
        val glowStrokeWidth = 11.5f * density

        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        glowPaint.strokeWidth = glowStrokeWidth

        // Leave enough padding so the rounded glow stroke is not clipped
        val padding = glowStrokeWidth / 2f + 2f * density
        diameter = min(w, h) - padding * 2f
        val radius = diameter / 2f

        arcBounds.set(
            centerX - radius,
            centerY - radius,
            centerX + radius,
            centerY + radius
        )

        // Precompute typography proportional to component bounds
        digitsPaint.textSize = diameter * 0.24f
        labelPaint.textSize = diameter * 0.12f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (diameter <= 0f) return

        // 1. Draw subtle background track ring (360 degrees)
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)

        // 2. Draw remaining countdown progress arc (starts at 12 o'clock / -90 degrees)
        val clampedProgress = progress.coerceIn(0f, 1f)
        val sweepAngle = clampedProgress * 360f

        if (sweepAngle > 0f) {
            // Ambient outer glow pass
            canvas.drawArc(arcBounds, -90f, sweepAngle, false, glowPaint)
            // Vibrant amber core pass
            canvas.drawArc(arcBounds, -90f, sweepAngle, false, progressPaint)
        }

        // 3. Draw center remaining time digits
        val textYOffset = (digitsPaint.descent() + digitsPaint.ascent()) / 2f
        val hasLabel = !labelText.isNullOrEmpty()
        val digitsY = if (hasLabel) {
            centerY - textYOffset - diameter * 0.07f
        } else {
            centerY - textYOffset
        }
        canvas.drawText(timeText, centerX, digitsY, digitsPaint)

        // 4. Draw optional subtitle below digits if present
        if (hasLabel) {
            val labelY = centerY - textYOffset + diameter * 0.18f
            canvas.drawText(labelText ?: "", centerX, labelY, labelPaint)
        }
    }

    /**
     * Sets countdown progress (1.0 = 100% full, 0.0 = expired).
     * Strictly calls [invalidate] only; does NOT trigger view hierarchy layout passes.
     */
    fun setProgress(targetProgress: Float, animated: Boolean = true) {
        val clampedTarget = targetProgress.coerceIn(0f, 1f)
        if (!animated) {
            progressAnimator?.cancel()
            progress = clampedTarget
            invalidate()
            return
        }

        if (progressAnimator?.isRunning == true && progressAnimator?.animatedValue == clampedTarget) {
            return
        }

        progressAnimator?.cancel()
        progressAnimator = ValueAnimator.ofFloat(progress, clampedTarget).apply {
            duration = 300L
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                progress = animator.animatedValue as Float
                invalidate() // Redraw without measuring/layout
            }
            start()
        }
    }

    fun getProgress(): Float = progress

    /**
     * Sets formatted remaining time digits (e.g. "08:45").
     * Strictly calls [invalidate] only; does NOT trigger view hierarchy layout passes.
     */
    fun setTimeText(text: String) {
        if (timeText == text) return
        timeText = text
        invalidate()
    }

    fun getTimeText(): String = timeText

    /**
     * Sets optional subtitle label.
     * Strictly calls [invalidate] only; does NOT trigger view hierarchy layout passes.
     */
    fun setLabelText(label: String?) {
        if (labelText == label) return
        labelText = label
        invalidate()
    }

    fun getLabelText(): String? = labelText

    fun stopAnimation() {
        progressAnimator?.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        progressAnimator?.cancel()
        progressAnimator = null
    }
}
