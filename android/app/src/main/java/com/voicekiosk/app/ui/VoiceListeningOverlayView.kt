package com.voicekiosk.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.voicekiosk.app.R

/**
 * Fullscreen Voice Listening Overlay:
 * - Breathing cyan-blue edge glow around the screen perimeter (Screenshots 3 & 6)
 * - Semi-transparent dark mask (#CC000000) over underlying pager
 * - Top status row (pulsing cyan dot + "正在聆听..." + close button "✕")
 * - Large centered transcription text area ("“请讲，我在听...”" -> live ASR partial / final text)
 * - 4 Generic suggestion command chips in a 2x2 grid (weather, homework toggle, todo list, music control)
 *   Zero hardcoding of board content, subjects, or person names.
 * - Tap action passes simulated utterance straight into VoiceKioskService
 * - Clean lifecycle: releases animators and handlers on dismiss
 */
class VoiceListeningOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var viewListeningDot: View? = null
    private var tvListeningStatus: TextView? = null
    private var btnCloseOverlay: View? = null
    private var tvListeningSpeech: TextView? = null
    private var tvListeningReply: TextView? = null
    private var tvSuggestionPrompt: TextView? = null
    private var chipCommand1: TextView? = null
    private var chipCommand2: TextView? = null
    private var chipCommand3: TextView? = null
    private var chipCommand4: TextView? = null

    // Breathing glow animation
    private var breathingAnimator: ValueAnimator? = null
    private var glowAlpha = 0.5f

    // Shaders and paints for the edge glow
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private var topShader: LinearGradient? = null
    private var bottomShader: LinearGradient? = null
    private var leftShader: LinearGradient? = null
    private var rightShader: LinearGradient? = null
    private var strokeShader: LinearGradient? = null

    private val glowDepth = dpToPx(28f)
    private val strokeWidth = dpToPx(2.5f)
    private val borderRect = RectF()

    private var onCloseAction: (() -> Unit)? = null
    private var onChipAction: ((phrase: String) -> Unit)? = null

    // Cached state for orientation changes
    private var currentStatus = "正在聆听..."
    private var isCurrentStatusAmber = false
    private var currentSpeech = "“请讲，我在听...”"
    private var currentReply: String? = null
    private var isCurrentReplyError = false

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        borderStrokePaint.strokeWidth = strokeWidth
        inflateLayout()
    }

    private fun inflateLayout() {
        removeAllViews()
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.view_listening_overlay, this, false)
        addView(contentView)
        bindViews(contentView)
        restoreState()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        inflateLayout()
    }

    private fun bindViews(root: View) {
        viewListeningDot = root.findViewById(R.id.viewListeningDot)
        tvListeningStatus = root.findViewById(R.id.tvListeningStatus)
        btnCloseOverlay = root.findViewById(R.id.btnCloseOverlay)
        tvListeningSpeech = root.findViewById(R.id.tvListeningSpeech)
        tvListeningReply = root.findViewById(R.id.tvListeningReply)
        tvSuggestionPrompt = root.findViewById(R.id.tvSuggestionPrompt)
        chipCommand1 = root.findViewById(R.id.chipCommand1)
        chipCommand2 = root.findViewById(R.id.chipCommand2)
        chipCommand3 = root.findViewById(R.id.chipCommand3)
        chipCommand4 = root.findViewById(R.id.chipCommand4)

        btnCloseOverlay?.setOnClickListener {
            dismiss()
        }

        val chips = listOf(chipCommand1, chipCommand2, chipCommand3, chipCommand4)
        chips.forEach { chip ->
            chip?.setOnClickListener { v ->
                val rawText = (v as? TextView)?.text?.toString().orEmpty()
                val cleanPhrase = rawText.removePrefix("“").removeSuffix("”").trim()
                if (cleanPhrase.isNotEmpty()) {
                    onChipAction?.invoke(cleanPhrase)
                }
            }
        }
    }

    private fun restoreState() {
        setStatus(currentStatus, isCurrentStatusAmber)
        setSpeechText(currentSpeech)
        currentReply?.let { setReply(it, isCurrentReplyError) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val glowCyan = Color.parseColor("#4D06B6D4") // 30% alpha cyan
        val glowBlue = Color.parseColor("#4D0284C7") // 30% alpha blue
        val transparent = Color.TRANSPARENT

        // 4 inner directional glow gradients
        topShader = LinearGradient(0f, 0f, 0f, glowDepth, intArrayOf(glowCyan, transparent), null, Shader.TileMode.CLAMP)
        bottomShader = LinearGradient(0f, h.toFloat(), 0f, h - glowDepth, intArrayOf(glowBlue, transparent), null, Shader.TileMode.CLAMP)
        leftShader = LinearGradient(0f, 0f, glowDepth, 0f, intArrayOf(glowCyan, transparent), null, Shader.TileMode.CLAMP)
        rightShader = LinearGradient(w.toFloat(), 0f, w - glowDepth, 0f, intArrayOf(glowBlue, transparent), null, Shader.TileMode.CLAMP)

        // Outer crisp border stroke gradient
        strokeShader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(
                Color.parseColor("#22D3EE"), // Bright cyan
                Color.parseColor("#0284C7"), // Cyan blue
                Color.parseColor("#38BDF8"), // Light sky blue
                Color.parseColor("#06B6D4")  // Cyan
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        borderStrokePaint.shader = strokeShader

        val halfStroke = strokeWidth / 2f
        borderRect.set(halfStroke, halfStroke, w.toFloat() - halfStroke, h.toFloat() - halfStroke)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Semi-transparent dark background mask over pager
        canvas.drawColor(Color.parseColor("#CC000000"))

        val alphaMultiplier = glowAlpha.coerceIn(0f, 1f)
        val paintAlpha = (alphaMultiplier * 255).toInt()

        // 2. Soft inner diffuse glow around the 4 edges
        glowPaint.alpha = paintAlpha

        topShader?.let {
            glowPaint.shader = it
            canvas.drawRect(0f, 0f, w, glowDepth, glowPaint)
        }
        bottomShader?.let {
            glowPaint.shader = it
            canvas.drawRect(0f, h - glowDepth, w, h, glowPaint)
        }
        leftShader?.let {
            glowPaint.shader = it
            canvas.drawRect(0f, 0f, glowDepth, h, glowPaint)
        }
        rightShader?.let {
            glowPaint.shader = it
            canvas.drawRect(w - glowDepth, 0f, w, h, glowPaint)
        }

        // 3. Crisp outer border glow stroke
        borderStrokePaint.alpha = paintAlpha
        canvas.drawRect(borderRect, borderStrokePaint)
    }

    fun show() {
        if (visibility == View.VISIBLE && alpha >= 0.99f) return
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(220L).start()
        startBreathingAnimation()
    }

    fun dismiss() {
        if (visibility != View.VISIBLE) return
        stopBreathingAnimation()
        animate()
            .alpha(0f)
            .setDuration(200L)
            .withEndAction {
                visibility = View.GONE
                onCloseAction?.invoke()
            }
            .start()
    }

    fun setStatus(status: String, isAmber: Boolean = false) {
        currentStatus = status
        isCurrentStatusAmber = isAmber
        tvListeningStatus?.text = status
        val colorRes = if (isAmber) R.color.accent_amber_light else R.color.accent_cyan_bright
        tvListeningStatus?.setTextColor(ContextCompat.getColor(context, colorRes))
    }

    fun setSpeechText(text: String) {
        currentSpeech = text
        currentReply = null
        tvListeningSpeech?.text = text
        tvListeningReply?.visibility = View.GONE
    }

    fun setReply(replyText: String, isError: Boolean = false) {
        currentReply = replyText
        isCurrentReplyError = isError
        tvListeningReply?.apply {
            text = replyText
            if (isError) {
                setTextColor(ContextCompat.getColor(context, R.color.accent_red_light))
            } else {
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(200L).start()
        }
    }

    fun setOnCloseListener(listener: () -> Unit) {
        this.onCloseAction = listener
    }

    fun setOnChipClickListener(listener: (phrase: String) -> Unit) {
        this.onChipAction = listener
    }

    fun release() {
        stopBreathingAnimation()
        onCloseAction = null
        onChipAction = null
        animate().cancel()
    }

    private fun startBreathingAnimation() {
        stopBreathingAnimation()
        breathingAnimator = ValueAnimator.ofFloat(0.35f, 0.95f).apply {
            duration = 1500L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                glowAlpha = anim.animatedValue as Float
                viewListeningDot?.alpha = glowAlpha
                invalidate()
            }
            start()
        }
    }

    private fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator?.removeAllUpdateListeners()
        breathingAnimator = null
    }

    override fun onDetachedFromWindow() {
        stopBreathingAnimation()
        super.onDetachedFromWindow()
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
