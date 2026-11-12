package com.voicekiosk.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * PetAvatarView renders an expressive AI Companion Pet using pure 2D Canvas vector drawing
 * (Paint, Path, RectF) and ValueAnimator.
 *
 * Supports 9 expressive states:
 * - AOD_IDLE: Minimalist OLED daytime standby mode (thin stroke outline, no solid fill or glow)
 * - AOD_SLEEP: Minimalist OLED night sleeping mode (ultra-dim outline, drooping ears, sleeping arcs, floating Zzz)
 * - WAKE_SURPRISE: Vibrant emerald illuminated mode (bouncing jump, perked ears, star eyes, pink blush, ripple rings)
 * - LISTENING: Illuminated emerald/cyan mode (attentive lean, audio-reactive wave rings and equalizer bars)
 * - THINKING: Illuminated indigo/violet mode (pupils looking up, 3 bouncing glowing dots above head)
 * - SPEAKING: Illuminated indigo/sky-blue mode (happy curved smiling eyes, rhythmic mouth, body sway, soundwave aura)
 * - FOLLOW_UP: Illuminated teal mode (7° head tilt, expectant eyes, dual breathing rings)
 * - CONFUSED: Amber/slate mode (12° head tilt, asymmetrical curious eyes, floating '?' question mark)
 * - ERROR: Soft coral/red mode (drooping ears, apologetic/sad eyes, forehead sweat drop)
 */
class PetAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class PetState {
        AOD_IDLE,
        AOD_SLEEP,
        WAKE_SURPRISE,
        LISTENING,
        THINKING,
        SPEAKING,
        FOLLOW_UP,
        CONFUSED,
        ERROR
    }

    companion object {
        private const val TOUCH_REACTION_DURATION_MS = 900L
        private const val WAKE_SURPRISE_DURATION_MS = 1400L
        private const val BLINK_CYCLE_MS = 3600L
        private const val BLINK_DURATION_MS = 160L

        // Palette
        private val COLOR_AOD_OUTLINE = Color.parseColor("#475569")
        private val COLOR_AOD_ACCENT = Color.parseColor("#38BDF8")
        private val COLOR_AOD_SLEEP = Color.parseColor("#334155")

        private val COLOR_EMERALD = Color.parseColor("#10B981")
        private val COLOR_CYAN = Color.parseColor("#06B6D4")
        private val COLOR_INDIGO = Color.parseColor("#6366F1")
        private val COLOR_VIOLET = Color.parseColor("#818CF8")
        private val COLOR_TEAL = Color.parseColor("#14B8A6")
        private val COLOR_AMBER = Color.parseColor("#F59E0B")
        private val COLOR_CORAL = Color.parseColor("#F87171")
        private val COLOR_BLUSH = Color.parseColor("#FB7185")
        private val COLOR_SKY = Color.parseColor("#38BDF8")

        private val COLOR_BODY_DARK = Color.parseColor("#0F172A")
        private val COLOR_BODY_SLATE = Color.parseColor("#1E293B")
        private val COLOR_WHITE = Color.WHITE
    }

    // Current state & targets
    private var currentState: PetState = PetState.AOD_IDLE
    private var stateStartTimeMs: Long = SystemClock.uptimeMillis()
    private var wakeSurpriseStartTimeMs: Long = 0L

    // Audio energy smoothing (0f..1f)
    private var targetAudioEnergy: Float = 0f
    private var currentAudioEnergy: Float = 0f

    // Touch reaction (~900ms happy bounce + blush)
    private var touchReactionActive: Boolean = false
    private var touchReactionStartTimeMs: Long = 0L
    private var onPetTappedListener: (() -> Unit)? = null

    // Master ticker animator
    private var tickerAnimator: ValueAnimator? = null
    private var isAttached: Boolean = false

    // Pre-allocated Paint objects to avoid any allocations in onDraw()
    private val bodyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val visorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val accentFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val accentStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val eyeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val eyeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val blushPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mouthFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val mouthStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val eqBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val sweatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // Pre-allocated reusable Path & RectF objects
    private val bodyBounds = RectF()
    private val visorBounds = RectF()
    private val tempRect = RectF()
    private val leftCheekRect = RectF()
    private val rightCheekRect = RectF()
    private val leftEyeRect = RectF()
    private val rightEyeRect = RectF()
    private val mouthRect = RectF()
    private val barRect = RectF()

    private val leftEarPath = Path()
    private val rightEarPath = Path()
    private val leftEarInnerPath = Path()
    private val rightEarInnerPath = Path()
    private val leftEyeArcPath = Path()
    private val rightEyeArcPath = Path()
    private val mouthPath = Path()
    private val starPath = Path()
    private val sweatPath = Path()

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    fun setPetState(newState: PetState) {
        if (currentState == newState) return
        currentState = newState
        val now = SystemClock.uptimeMillis()
        stateStartTimeMs = now
        if (newState == PetState.WAKE_SURPRISE) {
            wakeSurpriseStartTimeMs = now
        }
        postOrInvalidate()
    }

    fun getPetState(): PetState = currentState

    fun setAudioEnergy(energy: Float) {
        targetAudioEnergy = energy.coerceIn(0f, 1f)
        postOrInvalidate()
    }

    fun setOnPetTappedListener(listener: (() -> Unit)?) {
        this.onPetTappedListener = listener
    }

    fun triggerTouchReaction() {
        touchReactionStartTimeMs = SystemClock.uptimeMillis()
        touchReactionActive = true
        onPetTappedListener?.invoke()
        postOrInvalidate()
    }

    private fun postOrInvalidate() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invalidate()
        } else {
            postInvalidate()
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle & Visibility
    // -------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        if (visibility == VISIBLE) {
            startTicker()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        isAttached = false
        stopTicker()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttached) {
            if (visibility == VISIBLE) {
                startTicker()
            } else {
                stopTicker()
            }
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttached) {
            if (visibility == VISIBLE) {
                startTicker()
            } else {
                stopTicker()
            }
        }
    }

    private fun startTicker() {
        if (tickerAnimator?.isRunning == true) return
        tickerAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                updateAnimationTick()
                invalidate()
            }
            start()
        }
    }

    private fun stopTicker() {
        tickerAnimator?.cancel()
        tickerAnimator = null
    }

    private fun updateAnimationTick() {
        val now = SystemClock.uptimeMillis()

        // Smooth audio energy with exponential moving average
        val alpha = 0.22f
        currentAudioEnergy += (targetAudioEnergy - currentAudioEnergy) * alpha
        if (currentAudioEnergy < 0.005f && targetAudioEnergy == 0f) {
            currentAudioEnergy = 0f
        }

        // End touch reaction if duration exceeded
        if (touchReactionActive && now - touchReactionStartTimeMs >= TOUCH_REACTION_DURATION_MS) {
            touchReactionActive = false
        }
    }

    // -------------------------------------------------------------------------
    // Touch Handling & Accessibility
    // -------------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    performClick()
                    triggerTouchReaction()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // -------------------------------------------------------------------------
    // Measurement
    // -------------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (160 * resources.displayMetrics.density).toInt()
        val width = resolveSize(defaultSize, widthMeasureSpec)
        val height = resolveSize(defaultSize, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    // -------------------------------------------------------------------------
    // Canvas Vector Rendering
    // -------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val density = resources.displayMetrics.density
        val minDim = min(w, h)
        val petRadius = minDim * 0.28f
        val cx = w / 2f
        val cy = h / 2f

        val nowMs = SystemClock.uptimeMillis()
        val timeSec = (nowMs % 100_000L) / 1000f

        val isAod = (currentState == PetState.AOD_IDLE || currentState == PetState.AOD_SLEEP)

        // 1. Compute dynamic motion offsets (jump, bobbing, tilt, sway)
        val bobbingY = calculateBobbing(density, timeSec, isAod)
        val jumpOffset = calculateJumpOffset(density, nowMs)
        val leanY = if (currentState == PetState.LISTENING) 3f * density else 0f
        val totalCenterY = cy + bobbingY + jumpOffset + leanY

        val tiltAngle = calculateTiltAngle(timeSec)

        // 2. Draw surrounding / aura elements that appear BEHIND the pet body
        drawBackgroundAura(canvas, cx, totalCenterY, petRadius, density, timeSec, nowMs, isAod)

        // 3. Save canvas transform for head tilt & body sway
        canvas.save()
        if (tiltAngle != 0f) {
            canvas.rotate(tiltAngle, cx, totalCenterY)
        }

        // Compute body bounds
        val bodyW = petRadius * 2.05f
        val bodyH = petRadius * 1.70f
        bodyBounds.set(
            cx - bodyW / 2f,
            totalCenterY - bodyH / 2f,
            cx + bodyW / 2f,
            totalCenterY + bodyH / 2f
        )

        // 4. Draw Ears
        drawEars(canvas, cx, totalCenterY, petRadius, density, timeSec, isAod)

        // 5. Draw Body
        drawBody(canvas, petRadius, density, isAod)

        // 6. Draw Eyes
        drawEyes(canvas, cx, totalCenterY, petRadius, density, nowMs)

        // 7. Draw Cheeks (Blush)
        drawCheeks(canvas, cx, totalCenterY, petRadius, nowMs, isAod)

        // 8. Draw Mouth
        drawMouth(canvas, cx, totalCenterY, petRadius, density, timeSec)

        // Restore canvas transform
        canvas.restore()

        // 9. Draw Foreground floating elements (Thinking dots, Zzz, '?', Forehead sweat)
        drawForegroundElements(canvas, cx, totalCenterY, petRadius, density, timeSec)
    }

    // -------------------------------------------------------------------------
    // Motion Calculations
    // -------------------------------------------------------------------------

    private fun calculateBobbing(density: Float, timeSec: Float, isAod: Boolean): Float {
        return if (currentState == PetState.AOD_SLEEP) {
            // Very slow calm breathing
            sin(timeSec * 1.6f) * 1.8f * density
        } else if (isAod) {
            // Daytime standby gentle bobbing
            sin(timeSec * 2.2f) * 2.6f * density
        } else {
            // Natural active bobbing
            sin(timeSec * 2.8f) * 3.4f * density
        }
    }

    private fun calculateJumpOffset(density: Float, nowMs: Long): Float {
        var jump = 0f

        // Touch reaction jump (~900ms)
        if (touchReactionActive) {
            val touchElapsed = nowMs - touchReactionStartTimeMs
            if (touchElapsed < TOUCH_REACTION_DURATION_MS) {
                val p = touchElapsed.toFloat() / TOUCH_REACTION_DURATION_MS
                // Parabolic bounce curve
                jump += -sin(p * PI.toFloat()) * (22f * density)
            }
        }

        // Wake word surprise jump (~1400ms decaying bounce)
        if (currentState == PetState.WAKE_SURPRISE) {
            val wakeElapsed = nowMs - wakeSurpriseStartTimeMs
            if (wakeElapsed < WAKE_SURPRISE_DURATION_MS) {
                val p = wakeElapsed.toFloat() / WAKE_SURPRISE_DURATION_MS
                val decay = (1f - p)
                jump += -abs(sin(p * PI.toFloat() * 2.5f)) * (18f * density) * decay
            }
        }

        return jump
    }

    private fun calculateTiltAngle(timeSec: Float): Float {
        return when (currentState) {
            PetState.SPEAKING -> sin(timeSec * 3.2f) * 3.5f // Gentle left-right sway
            PetState.FOLLOW_UP -> 7.0f                      // Curious expectant tilt (~7°)
            PetState.CONFUSED -> 12.0f                      // Puzzled head tilt (~12°)
            else -> 0f
        }
    }

    // -------------------------------------------------------------------------
    // Drawing: Background Aura & Rings
    // -------------------------------------------------------------------------

    private fun drawBackgroundAura(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        petRadius: Float,
        density: Float,
        timeSec: Float,
        nowMs: Long,
        isAod: Boolean
    ) {
        if (isAod) return // Absolutely NO heavy glow or background rings in AOD to protect OLED and power

        when (currentState) {
            PetState.WAKE_SURPRISE -> {
                // Expanding emerald ripple rings
                val wakeElapsed = nowMs - wakeSurpriseStartTimeMs
                if (wakeElapsed < WAKE_SURPRISE_DURATION_MS) {
                    val progress = (wakeElapsed % 700L) / 700f
                    val ringRadius = petRadius * 1.05f + progress * petRadius * 0.75f
                    val ringAlpha = ((1f - progress) * 180).toInt().coerceIn(0, 255)
                    auraPaint.color = COLOR_EMERALD
                    auraPaint.alpha = ringAlpha
                    auraPaint.strokeWidth = 2.8f * density * (1f - progress * 0.4f)
                    canvas.drawCircle(cx, cy, ringRadius, auraPaint)
                }
            }

            PetState.LISTENING -> {
                // Audio-reactive concentric voice wave rings
                if (currentAudioEnergy > 0.04f) {
                    val ring1Radius = petRadius * 1.15f + currentAudioEnergy * 12f * density
                    val ring2Radius = petRadius * 1.35f + currentAudioEnergy * 24f * density

                    auraPaint.color = COLOR_CYAN
                    auraPaint.alpha = (100 * currentAudioEnergy).toInt().coerceIn(0, 255)
                    auraPaint.strokeWidth = 2.5f * density
                    canvas.drawCircle(cx, cy, ring1Radius, auraPaint)

                    auraPaint.color = COLOR_EMERALD
                    auraPaint.alpha = (70 * currentAudioEnergy).toInt().coerceIn(0, 255)
                    auraPaint.strokeWidth = 2f * density
                    canvas.drawCircle(cx, cy, ring2Radius, auraPaint)
                }

                // Left & Right mini equalizer bars
                drawEqualizerBars(canvas, cx, cy, petRadius, density, timeSec)
            }

            PetState.SPEAKING -> {
                // Pulsing soundwave aura
                val pulse = (sin(timeSec * 4f) + 1f) / 2f
                val ringRadius = petRadius * 1.20f + pulse * 8f * density
                auraPaint.color = COLOR_SKY
                auraPaint.alpha = (50 + pulse * 60).toInt().coerceIn(0, 255)
                auraPaint.strokeWidth = 2.2f * density
                canvas.drawCircle(cx, cy, ringRadius, auraPaint)
            }

            PetState.FOLLOW_UP -> {
                // Soft dual breathing rings
                val breath = (sin(timeSec * 2.4f) + 1f) / 2f
                val r1 = petRadius * 1.12f + breath * 5f * density
                val r2 = petRadius * 1.26f + (1f - breath) * 5f * density
                auraPaint.color = COLOR_TEAL
                auraPaint.strokeWidth = 1.8f * density

                auraPaint.alpha = (40 + breath * 50).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, r1, auraPaint)

                auraPaint.alpha = (30 + (1f - breath) * 45).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, r2, auraPaint)
            }

            else -> { /* No aura for other states */ }
        }
    }

    private fun drawEqualizerBars(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        petRadius: Float,
        density: Float,
        timeSec: Float
    ) {
        val barCount = 3
        val barW = 3.5f * density
        val barGap = 4.5f * density
        val baseH = 4f * density
        val maxExtraH = 22f * density

        eqBarPaint.color = COLOR_CYAN

        // Left 3 bars
        for (i in 0 until barCount) {
            val phase = (i * 0.9f) + (timeSec * 12f)
            val barFactor = (0.25f + 0.75f * abs(sin(phase))) * currentAudioEnergy
            val barH = baseH + maxExtraH * barFactor
            val barX = cx - petRadius - 14f * density - (barCount - 1 - i) * (barW + barGap)
            val barY = cy - barH / 2f
            barRect.set(barX, barY, barX + barW, barY + barH)
            canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, eqBarPaint)
        }

        // Right 3 bars
        for (i in 0 until barCount) {
            val phase = (i * 0.9f) + (timeSec * 12f) + 1.4f
            val barFactor = (0.25f + 0.75f * abs(sin(phase))) * currentAudioEnergy
            val barH = baseH + maxExtraH * barFactor
            val barX = cx + petRadius + 14f * density + i * (barW + barGap)
            val barY = cy - barH / 2f
            barRect.set(barX, barY, barX + barW, barY + barH)
            canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, eqBarPaint)
        }
    }

    // -------------------------------------------------------------------------
    // Drawing: Body & Ears
    // -------------------------------------------------------------------------

    private fun drawBody(
        canvas: Canvas,
        petRadius: Float,
        density: Float,
        isAod: Boolean
    ) {
        if (isAod) {
            outlinePaint.color = if (currentState == PetState.AOD_SLEEP) COLOR_AOD_SLEEP else COLOR_AOD_OUTLINE
            outlinePaint.strokeWidth = if (currentState == PetState.AOD_SLEEP) 1.8f * density else 2.4f * density
            outlinePaint.alpha = if (currentState == PetState.AOD_SLEEP) 90 else 190
            canvas.drawOval(bodyBounds, outlinePaint)
        } else {
            // Dark obsidian body
            bodyFillPaint.color = COLOR_BODY_DARK
            canvas.drawOval(bodyBounds, bodyFillPaint)

            // Inner visor plate
            val visorInsetX = petRadius * 0.16f
            val visorInsetY = petRadius * 0.14f
            visorBounds.set(
                bodyBounds.left + visorInsetX,
                bodyBounds.top + visorInsetY,
                bodyBounds.right - visorInsetX,
                bodyBounds.bottom - visorInsetY
            )
            visorPaint.color = COLOR_BODY_SLATE
            canvas.drawOval(visorBounds, visorPaint)

            // Outer glowing stroke
            outlinePaint.color = getStateThemeColor()
            outlinePaint.strokeWidth = 2.8f * density
            outlinePaint.alpha = 255
            canvas.drawOval(bodyBounds, outlinePaint)
        }
    }

    private fun drawEars(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        petRadius: Float,
        density: Float,
        timeSec: Float,
        isAod: Boolean
    ) {
        val earBaseOuterY = cy - petRadius * 0.28f
        val earBaseInnerY = cy - petRadius * 0.68f

        val leftBaseOuterX = cx - petRadius * 0.76f
        val leftBaseInnerX = cx - petRadius * 0.30f

        var leftTipX = cx - petRadius * 0.65f
        var leftTipY = cy - petRadius * 1.15f

        // State ear variations
        when (currentState) {
            PetState.AOD_SLEEP, PetState.ERROR -> {
                // Drooping ears
                leftTipX = cx - petRadius * 0.90f
                leftTipY = cy - petRadius * 0.18f
            }
            PetState.WAKE_SURPRISE -> {
                // Perked up high
                leftTipX = cx - petRadius * 0.60f
                leftTipY = cy - petRadius * 1.34f
            }
            PetState.LISTENING -> {
                // Perked & twitching dynamically with audio
                val twitch = currentAudioEnergy * 4f * density * sin(timeSec * 22f)
                leftTipX = cx - petRadius * 0.64f - twitch
                leftTipY = cy - petRadius * 1.20f - currentAudioEnergy * 6f * density
            }
            else -> { /* Default upright attentive angle */ }
        }

        // Build left ear path
        leftEarPath.reset()
        leftEarPath.moveTo(leftBaseOuterX, earBaseOuterY)
        leftEarPath.quadTo(cx - petRadius * 0.88f, cy - petRadius * 0.78f, leftTipX, leftTipY)
        leftEarPath.quadTo(cx - petRadius * 0.44f, cy - petRadius * 1.02f, leftBaseInnerX, earBaseInnerY)
        leftEarPath.close()

        // Symmetrically build right ear path across cx
        val rightBaseOuterX = cx + (cx - leftBaseOuterX)
        val rightBaseInnerX = cx + (cx - leftBaseInnerX)
        val rightTipX = cx + (cx - leftTipX)

        rightEarPath.reset()
        rightEarPath.moveTo(rightBaseOuterX, earBaseOuterY)
        rightEarPath.quadTo(cx + petRadius * 0.88f, cy - petRadius * 0.78f, rightTipX, leftTipY)
        rightEarPath.quadTo(cx + petRadius * 0.44f, cy - petRadius * 1.02f, rightBaseInnerX, earBaseInnerY)
        rightEarPath.close()

        if (isAod) {
            outlinePaint.color = if (currentState == PetState.AOD_SLEEP) COLOR_AOD_SLEEP else COLOR_AOD_OUTLINE
            outlinePaint.strokeWidth = if (currentState == PetState.AOD_SLEEP) 1.8f * density else 2.4f * density
            outlinePaint.alpha = if (currentState == PetState.AOD_SLEEP) 90 else 190
            canvas.drawPath(leftEarPath, outlinePaint)
            canvas.drawPath(rightEarPath, outlinePaint)
        } else {
            // Dark ear base fill
            bodyFillPaint.color = COLOR_BODY_DARK
            canvas.drawPath(leftEarPath, bodyFillPaint)
            canvas.drawPath(rightEarPath, bodyFillPaint)

            // Inner ear accent
            val themeColor = getStateThemeColor()
            accentFillPaint.color = themeColor
            accentFillPaint.alpha = 50
            canvas.drawPath(leftEarPath, accentFillPaint)
            canvas.drawPath(rightEarPath, accentFillPaint)

            // Ear outline stroke
            outlinePaint.color = themeColor
            outlinePaint.strokeWidth = 2.6f * density
            outlinePaint.alpha = 255
            canvas.drawPath(leftEarPath, outlinePaint)
            canvas.drawPath(rightEarPath, outlinePaint)
        }
    }

    // -------------------------------------------------------------------------
    // Drawing: Eyes
    // -------------------------------------------------------------------------

    private fun drawEyes(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        petRadius: Float,
        density: Float,
        nowMs: Long
    ) {
        val eyeSpacing = petRadius * 0.40f
        val eyeY = cy - petRadius * 0.05f
        val lx = cx - eyeSpacing
        val rx = cx + eyeSpacing

        val baseEyeW = petRadius * 0.17f
        val baseEyeH = petRadius * 0.22f

        // If touch reaction active: Joyous curved smile eyes (^ ^)
        if (touchReactionActive) {
            drawHappySmilingEyes(canvas, lx, rx, eyeY, baseEyeW, baseEyeH, density, COLOR_BLUSH)
            return
        }

        when (currentState) {
            PetState.AOD_SLEEP -> {
                // Closed sleeping arc eyes (︶ ︶)
                eyeStrokePaint.color = COLOR_AOD_SLEEP
                eyeStrokePaint.strokeWidth = 2.2f * density
                eyeStrokePaint.alpha = 110
                drawSleepingArcEyes(canvas, lx, rx, eyeY, baseEyeW, baseEyeH)
            }

            PetState.SPEAKING -> {
                // Happy curved smiling eyes (^ ^)
                drawHappySmilingEyes(canvas, lx, rx, eyeY, baseEyeW, baseEyeH, density, COLOR_SKY)
            }

            PetState.AOD_IDLE -> {
                // Daytime standby outline eyes with periodic blink
                val blink = calculateBlinkFraction(nowMs)
                eyeStrokePaint.color = COLOR_AOD_OUTLINE
                eyeStrokePaint.strokeWidth = 2.2f * density
                eyeStrokePaint.alpha = 210

                if (blink < 0.18f) {
                    canvas.drawLine(lx - baseEyeW, eyeY, lx + baseEyeW, eyeY, eyeStrokePaint)
                    canvas.drawLine(rx - baseEyeW, eyeY, rx + baseEyeW, eyeY, eyeStrokePaint)
                } else {
                    val curH = baseEyeH * blink
                    leftEyeRect.set(lx - baseEyeW, eyeY - curH, lx + baseEyeW, eyeY + curH)
                    rightEyeRect.set(rx - baseEyeW, eyeY - curH, rx + baseEyeW, eyeY + curH)
                    canvas.drawOval(leftEyeRect, eyeStrokePaint)
                    canvas.drawOval(rightEyeRect, eyeStrokePaint)

                    // Small pupil outline in AOD
                    accentStrokePaint.color = COLOR_AOD_ACCENT
                    accentStrokePaint.strokeWidth = 1.6f * density
                    accentStrokePaint.alpha = 90
                    canvas.drawCircle(lx, eyeY, baseEyeW * 0.45f * blink, accentStrokePaint)
                    canvas.drawCircle(rx, eyeY, baseEyeW * 0.45f * blink, accentStrokePaint)
                }
            }

            PetState.WAKE_SURPRISE -> {
                // Wide sparkling eyes with star highlights
                drawIlluminatedEyeBase(canvas, lx, rx, eyeY, baseEyeW * 1.18f, baseEyeH * 1.18f, COLOR_EMERALD)
                // Draw 4-point star highlights
                pupilPaint.color = COLOR_WHITE
                pupilPaint.alpha = 255
                val starR = 5f * density
                drawSparkleStar(canvas, lx, eyeY - 2f * density, starR)
                drawSparkleStar(canvas, rx, eyeY - 2f * density, starR)
            }

            PetState.THINKING -> {
                // Thoughtful pupils looking up-right
                val blink = calculateBlinkFraction(nowMs)
                val curH = baseEyeH * blink
                drawIlluminatedEyeBase(canvas, lx, rx, eyeY, baseEyeW, curH, COLOR_VIOLET)

                if (blink > 0.4f) {
                    // Pupils shifted up-right
                    val pupilOffX = 4f * density
                    val pupilOffY = -4f * density
                    pupilPaint.color = COLOR_BODY_DARK
                    canvas.drawCircle(lx + pupilOffX, eyeY + pupilOffY, baseEyeW * 0.42f, pupilPaint)
                    canvas.drawCircle(rx + pupilOffX, eyeY + pupilOffY, baseEyeW * 0.42f, pupilPaint)

                    // Light glint
                    pupilPaint.color = COLOR_WHITE
                    canvas.drawCircle(lx + pupilOffX + 1.5f * density, eyeY + pupilOffY - 1.5f * density, 1.8f * density, pupilPaint)
                    canvas.drawCircle(rx + pupilOffX + 1.5f * density, eyeY + pupilOffY - 1.5f * density, 1.8f * density, pupilPaint)
                }
            }

            PetState.CONFUSED -> {
                // Asymmetrical curious eyes: left round, right narrowed with raised eyebrow
                // Left eye: wide open curious
                drawSingleEyeBase(canvas, lx, eyeY, baseEyeW * 1.1f, baseEyeH * 1.1f, COLOR_AMBER)
                // Right eye: narrowed
                drawSingleEyeBase(canvas, rx, eyeY, baseEyeW * 0.95f, baseEyeH * 0.65f, COLOR_AMBER)

                // Raised eyebrow above right eye
                eyeStrokePaint.color = COLOR_AMBER
                eyeStrokePaint.strokeWidth = 2.4f * density
                eyeStrokePaint.alpha = 220
                canvas.drawLine(
                    rx - baseEyeW,
                    eyeY - baseEyeH * 0.8f - 4f * density,
                    rx + baseEyeW,
                    eyeY - baseEyeH * 0.8f - 9f * density,
                    eyeStrokePaint
                )
            }

            PetState.ERROR -> {
                // Apologetic / sad puppy eyes slanted outward
                eyeStrokePaint.color = COLOR_CORAL
                eyeStrokePaint.strokeWidth = 2.5f * density
                eyeStrokePaint.alpha = 230

                // Left eye sad slant (\)
                canvas.drawLine(lx - baseEyeW, eyeY - 3f * density, lx + baseEyeW, eyeY + 4f * density, eyeStrokePaint)
                // Right eye sad slant (/)
                canvas.drawLine(rx - baseEyeW, eyeY + 4f * density, rx + baseEyeW, eyeY - 3f * density, eyeStrokePaint)
            }

            PetState.LISTENING, PetState.FOLLOW_UP -> {
                // Bright attentive eyes with gentle blinking
                val blink = calculateBlinkFraction(nowMs)
                val curH = baseEyeH * blink
                val theme = if (currentState == PetState.LISTENING) COLOR_CYAN else COLOR_TEAL
                drawIlluminatedEyeBase(canvas, lx, rx, eyeY, baseEyeW, curH, theme)

                if (blink > 0.35f) {
                    pupilPaint.color = COLOR_BODY_DARK
                    canvas.drawCircle(lx, eyeY, baseEyeW * 0.40f, pupilPaint)
                    canvas.drawCircle(rx, eyeY, baseEyeW * 0.40f, pupilPaint)

                    // Small reflection glint
                    pupilPaint.color = COLOR_WHITE
                    canvas.drawCircle(lx + 2f * density, eyeY - 2f * density, 2f * density, pupilPaint)
                    canvas.drawCircle(rx + 2f * density, eyeY - 2f * density, 2f * density, pupilPaint)
                }
            }
        }
    }

    private fun drawSleepingArcEyes(
        canvas: Canvas,
        lx: Float,
        rx: Float,
        eyeY: Float,
        w: Float,
        h: Float
    ) {
        // Downward sleeping curve (︶ ︶)
        leftEyeArcPath.reset()
        leftEyeArcPath.moveTo(lx - w, eyeY - 2f)
        leftEyeArcPath.quadTo(lx, eyeY + h * 0.7f, lx + w, eyeY - 2f)
        canvas.drawPath(leftEyeArcPath, eyeStrokePaint)

        rightEyeArcPath.reset()
        rightEyeArcPath.moveTo(rx - w, eyeY - 2f)
        rightEyeArcPath.quadTo(rx, eyeY + h * 0.7f, rx + w, eyeY - 2f)
        canvas.drawPath(rightEyeArcPath, eyeStrokePaint)
    }

    private fun drawHappySmilingEyes(
        canvas: Canvas,
        lx: Float,
        rx: Float,
        eyeY: Float,
        w: Float,
        h: Float,
        density: Float,
        strokeColor: Int
    ) {
        // Upward smiling curve (^ ^)
        eyeStrokePaint.color = strokeColor
        eyeStrokePaint.strokeWidth = 2.8f * density
        eyeStrokePaint.alpha = 255

        leftEyeArcPath.reset()
        leftEyeArcPath.moveTo(lx - w, eyeY + 3f * density)
        leftEyeArcPath.quadTo(lx, eyeY - h * 0.75f, lx + w, eyeY + 3f * density)
        canvas.drawPath(leftEyeArcPath, eyeStrokePaint)

        rightEyeArcPath.reset()
        rightEyeArcPath.moveTo(rx - w, eyeY + 3f * density)
        rightEyeArcPath.quadTo(rx, eyeY - h * 0.75f, rx + w, eyeY + 3f * density)
        canvas.drawPath(rightEyeArcPath, eyeStrokePaint)
    }

    private fun drawIlluminatedEyeBase(
        canvas: Canvas,
        lx: Float,
        rx: Float,
        eyeY: Float,
        w: Float,
        h: Float,
        themeColor: Int
    ) {
        drawSingleEyeBase(canvas, lx, eyeY, w, h, themeColor)
        drawSingleEyeBase(canvas, rx, eyeY, w, h, themeColor)
    }

    private fun drawSingleEyeBase(
        canvas: Canvas,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        themeColor: Int
    ) {
        tempRect.set(x - w, y - h, x + w, y + h)
        // Eye glowing base
        eyeFillPaint.color = themeColor
        canvas.drawOval(tempRect, eyeFillPaint)

        // Soft white highlight base
        eyeFillPaint.color = COLOR_WHITE
        eyeFillPaint.alpha = 180
        tempRect.set(x - w * 0.8f, y - h * 0.8f, x + w * 0.8f, y + h * 0.8f)
        canvas.drawOval(tempRect, eyeFillPaint)
        eyeFillPaint.alpha = 255
    }

    private fun drawSparkleStar(canvas: Canvas, x: Float, y: Float, radius: Float) {
        starPath.reset()
        starPath.moveTo(x, y - radius)
        starPath.quadTo(x, y, x + radius, y)
        starPath.quadTo(x, y, x, y + radius)
        starPath.quadTo(x, y, x - radius, y)
        starPath.quadTo(x, y, x, y - radius)
        starPath.close()
        canvas.drawPath(starPath, pupilPaint)
    }

    private fun calculateBlinkFraction(nowMs: Long): Float {
        val phase = nowMs % BLINK_CYCLE_MS
        return if (phase < BLINK_DURATION_MS) {
            val p = phase.toFloat() / BLINK_DURATION_MS
            // 1f -> 0f -> 1f
            abs(p - 0.5f) * 2f
        } else {
            1f
        }
    }

    // -------------------------------------------------------------------------
    // Drawing: Cheeks (Blush)
    // -------------------------------------------------------------------------

    private fun drawCheeks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        petRadius: Float,
        nowMs: Long,
        isAod: Boolean
    ) {
        if (isAod) return // No blush in AOD

        val showBlush = touchReactionActive || currentState == PetState.WAKE_SURPRISE
        if (!showBlush) return

        val blushAlpha = if (touchReactionActive) {
            val p = (nowMs - touchReactionStartTimeMs).toFloat() / TOUCH_REACTION_DURATION_MS
            (sin(p * PI.toFloat()) * 200).toInt().coerceIn(0, 255)
        } else {
            170
        }

        blushPaint.color = COLOR_BLUSH
        blushPaint.alpha = blushAlpha

        val cheekW = petRadius * 0.18f
        val cheekH = petRadius * 0.09f
        val cheekY = cy + petRadius * 0.22f
        val cheekXDist = petRadius * 0.52f

        leftCheekRect.set(cx - cheekXDist - cheekW, cheekY - cheekH, cx - cheekXDist + cheekW, cheekY + cheekH)
        rightCheekRect.set(cx + cheekXDist - cheekW, cheekY - cheekH, cx + cheekXDist + cheekW, cheekY + cheekH)

        canvas.drawOval(leftCheekRect, blushPaint)
        canvas.drawOval(rightCheekRect, blushPaint)
    }

    // -------------------------------------------------------------------------
    // Drawing: Mouth
    // -------------------------------------------------------------------------

    private fun drawMouth(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        petRadius: Float,
        density: Float,
        timeSec: Float
    ) {
        val mouthY = cy + petRadius * 0.30f
        val mouthW = 6f * density

        if (touchReactionActive) {
            // Cheerful happy open smile
            mouthRect.set(cx - 8f * density, mouthY - 3f * density, cx + 8f * density, mouthY + 7f * density)
            mouthFillPaint.color = COLOR_BODY_DARK
            canvas.drawArc(mouthRect, 0f, 180f, true, mouthFillPaint)
            mouthStrokePaint.color = COLOR_BLUSH
            mouthStrokePaint.strokeWidth = 2.2f * density
            canvas.drawArc(mouthRect, 0f, 180f, false, mouthStrokePaint)
            return
        }

        when (currentState) {
            PetState.AOD_SLEEP -> {
                // Relaxed straight tiny line
                mouthStrokePaint.color = COLOR_AOD_SLEEP
                mouthStrokePaint.strokeWidth = 1.8f * density
                mouthStrokePaint.alpha = 90
                canvas.drawLine(cx - 4f * density, mouthY, cx + 4f * density, mouthY, mouthStrokePaint)
            }

            PetState.SPEAKING -> {
                // Rhythmic animated open/close mouth
                val open = abs(sin(timeSec * 8f))
                val openH = 2.5f * density + open * 7.5f * density
                mouthRect.set(cx - 7f * density, mouthY - openH / 2f, cx + 7f * density, mouthY + openH / 2f)

                mouthFillPaint.color = COLOR_BODY_DARK
                canvas.drawRoundRect(mouthRect, 3.5f * density, 3.5f * density, mouthFillPaint)

                mouthStrokePaint.color = COLOR_SKY
                mouthStrokePaint.strokeWidth = 2.2f * density
                mouthStrokePaint.alpha = 240
                canvas.drawRoundRect(mouthRect, 3.5f * density, 3.5f * density, mouthStrokePaint)
            }

            PetState.WAKE_SURPRISE -> {
                // Cute open 'o' mouth
                mouthFillPaint.color = COLOR_BODY_DARK
                canvas.drawCircle(cx, mouthY, 4f * density, mouthFillPaint)
                mouthStrokePaint.color = COLOR_EMERALD
                mouthStrokePaint.strokeWidth = 2.2f * density
                canvas.drawCircle(cx, mouthY, 4f * density, mouthStrokePaint)
            }

            PetState.CONFUSED -> {
                // Quizzical wavy line ~
                mouthStrokePaint.color = COLOR_AMBER
                mouthStrokePaint.strokeWidth = 2f * density
                mouthPath.reset()
                mouthPath.moveTo(cx - 6f * density, mouthY)
                mouthPath.quadTo(cx - 2f * density, mouthY - 3f * density, cx + 1f * density, mouthY)
                mouthPath.quadTo(cx + 4f * density, mouthY + 3f * density, cx + 7f * density, mouthY)
                canvas.drawPath(mouthPath, mouthStrokePaint)
            }

            PetState.ERROR -> {
                // Small sad frown arc
                mouthStrokePaint.color = COLOR_CORAL
                mouthStrokePaint.strokeWidth = 2f * density
                mouthPath.reset()
                mouthPath.moveTo(cx - 6f * density, mouthY + 3f * density)
                mouthPath.quadTo(cx, mouthY - 3f * density, cx + 6f * density, mouthY + 3f * density)
                canvas.drawPath(mouthPath, mouthStrokePaint)
            }

            PetState.AOD_IDLE -> {
                // Subtle clean smile arc outline
                mouthStrokePaint.color = COLOR_AOD_OUTLINE
                mouthStrokePaint.strokeWidth = 2f * density
                mouthStrokePaint.alpha = 180
                mouthPath.reset()
                mouthPath.moveTo(cx - mouthW, mouthY)
                mouthPath.quadTo(cx, mouthY + 3.5f * density, cx + mouthW, mouthY)
                canvas.drawPath(mouthPath, mouthStrokePaint)
            }

            else -> {
                // Subtle smile
                mouthStrokePaint.color = getStateThemeColor()
                mouthStrokePaint.strokeWidth = 2.2f * density
                mouthStrokePaint.alpha = 220
                mouthPath.reset()
                mouthPath.moveTo(cx - mouthW, mouthY)
                mouthPath.quadTo(cx, mouthY + 3.5f * density, cx + mouthW, mouthY)
                canvas.drawPath(mouthPath, mouthStrokePaint)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Drawing: Foreground Floating Elements
    // -------------------------------------------------------------------------

    private fun drawForegroundElements(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        petRadius: Float,
        density: Float,
        timeSec: Float
    ) {
        when (currentState) {
            PetState.AOD_SLEEP -> {
                // Floating "Z", "z", "z" drifting upward
                textPaint.color = COLOR_AOD_SLEEP
                val zOffsets = floatArrayOf(0f, 0.33f, 0.67f)
                val zSizes = floatArrayOf(15f * density, 12f * density, 9f * density)
                for (i in zOffsets.indices) {
                    val p = (timeSec * 0.30f + zOffsets[i]) % 1f
                    val zX = cx + petRadius * 0.55f + i * (5f * density) + sin(p * PI.toFloat() * 2f) * (4f * density)
                    val zY = cy - petRadius * 0.35f - p * (40f * density)
                    val alpha = (sin(p * PI.toFloat()) * 110).toInt().coerceIn(0, 255)
                    textPaint.textSize = zSizes[i]
                    textPaint.alpha = alpha
                    canvas.drawText("Z", zX, zY, textPaint)
                }
            }

            PetState.THINKING -> {
                // 3 glowing violet dots bouncing in sequence above head
                val dotSpacing = 13f * density
                val dotRadius = 3.6f * density
                val dotBaseY = cy - petRadius * 0.95f - 8f * density
                accentFillPaint.color = COLOR_VIOLET

                for (i in -1..1) {
                    val dotIndex = i + 1 // 0, 1, 2
                    val bounce = sin(timeSec * 5.5f + dotIndex * 1.1f) * (5f * density)
                    val dotX = cx + i * dotSpacing
                    val dotY = dotBaseY + bounce
                    accentFillPaint.alpha = (160 + 80 * sin(timeSec * 5.5f + dotIndex * 1.1f)).toInt().coerceIn(80, 255)
                    canvas.drawCircle(dotX, dotY, dotRadius, accentFillPaint)
                }
            }

            PetState.CONFUSED -> {
                // Floating '?' above head tilted playfully
                textPaint.textSize = 21f * density
                textPaint.color = COLOR_AMBER
                textPaint.alpha = (180 + 60 * sin(timeSec * 3.5f)).toInt().coerceIn(120, 255)
                val qX = cx + petRadius * 0.48f
                val qY = cy - petRadius * 0.95f + sin(timeSec * 2.5f) * (3f * density)

                canvas.save()
                canvas.rotate(14f, qX, qY)
                canvas.drawText("?", qX, qY, textPaint)
                canvas.restore()
            }

            PetState.ERROR -> {
                // Forehead sweat drop
                val sweatX = cx + petRadius * 0.46f
                val sweatY = cy - petRadius * 0.52f + sin(timeSec * 3f) * (1.8f * density)
                val dropW = 4f * density
                val dropH = 7f * density

                sweatPath.reset()
                sweatPath.moveTo(sweatX, sweatY - dropH / 2f)
                sweatPath.quadTo(sweatX + dropW, sweatY + dropH / 4f, sweatX, sweatY + dropH / 2f)
                sweatPath.quadTo(sweatX - dropW, sweatY + dropH / 4f, sweatX, sweatY - dropH / 2f)
                sweatPath.close()

                sweatPaint.color = COLOR_SKY
                sweatPaint.alpha = 210
                canvas.drawPath(sweatPath, sweatPaint)
            }

            else -> { /* No extra foreground elements */ }
        }
    }

    // -------------------------------------------------------------------------
    // State Color Mapping
    // -------------------------------------------------------------------------

    private fun getStateThemeColor(): Int {
        return when (currentState) {
            PetState.AOD_IDLE -> COLOR_AOD_OUTLINE
            PetState.AOD_SLEEP -> COLOR_AOD_SLEEP
            PetState.WAKE_SURPRISE -> COLOR_EMERALD
            PetState.LISTENING -> COLOR_CYAN
            PetState.THINKING -> COLOR_VIOLET
            PetState.SPEAKING -> COLOR_INDIGO
            PetState.FOLLOW_UP -> COLOR_TEAL
            PetState.CONFUSED -> COLOR_AMBER
            PetState.ERROR -> COLOR_CORAL
        }
    }
}
