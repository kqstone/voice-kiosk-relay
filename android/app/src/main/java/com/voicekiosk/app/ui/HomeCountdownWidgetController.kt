package com.voicekiosk.app.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.voicekiosk.app.R
import com.voicekiosk.app.countdown.CountdownItem
import com.voicekiosk.app.countdown.CountdownState

/**
 * Interface abstracting the countdown widget view operations for clean testing and decoupling.
 */
interface HomeCountdownWidgetView {
    fun setVisible(visible: Boolean, animate: Boolean)
    fun isVisible(): Boolean
    fun showRunning(timeText: String, progressFraction: Float, label: String?, otherCount: Int)
    fun showCompletion(label: String?, autoDismissSeconds: Int)
    fun setOnCancelClicked(listener: () -> Unit)
    fun setOnDismissClicked(listener: () -> Unit)
}

/**
 * Concrete implementation binding to real Android View hierarchy.
 */
class AndroidHomeCountdownWidgetView(rootView: View) : HomeCountdownWidgetView {
    val widgetView: View = rootView.findViewById(R.id.layoutCountdown) ?: rootView
    val layoutActive: View? = widgetView.findViewById(R.id.layoutCountdownActive)
    val countdownArcView: CountdownArcView? = widgetView.findViewById(R.id.countdownArcView)
    val tvCountdownLabel: TextView? = widgetView.findViewById(R.id.tvCountdownLabel)
    val tvCountdownBadge: TextView? = widgetView.findViewById(R.id.tvCountdownBadge)
    val btnCountdownCancel: View? = widgetView.findViewById(R.id.btnCountdownCancel)
    val layoutCompletion: View? = widgetView.findViewById(R.id.layoutCountdownCompletion)
    val tvCountdownCompleteTitle: TextView? = widgetView.findViewById(R.id.tvCountdownCompleteTitle)
    val btnCountdownDismiss: View? = widgetView.findViewById(R.id.btnCountdownDismiss)

    override fun setVisible(visible: Boolean, animate: Boolean) {
        if (visible) {
            if (widgetView.visibility == View.VISIBLE && widgetView.alpha == 1f) return
            widgetView.visibility = View.VISIBLE
            if (animate && widgetView.isAttachedToWindow) {
                widgetView.alpha = 0f
                widgetView.animate().alpha(1f).setDuration(300L).start()
            } else {
                widgetView.alpha = 1f
            }
        } else {
            if (widgetView.visibility == View.GONE) return
            if (animate && widgetView.isAttachedToWindow) {
                widgetView.animate().alpha(0f).setDuration(250L).withEndAction {
                    widgetView.visibility = View.GONE
                }.start()
            } else {
                widgetView.visibility = View.GONE
                widgetView.alpha = 0f
            }
        }
    }

    override fun isVisible(): Boolean = widgetView.visibility == View.VISIBLE

    override fun showRunning(timeText: String, progressFraction: Float, label: String?, otherCount: Int) {
        layoutActive?.visibility = View.VISIBLE
        layoutCompletion?.visibility = View.GONE

        countdownArcView?.setTimeText(timeText)
        countdownArcView?.setProgress(progressFraction, animated = true)

        tvCountdownLabel?.text = if (!label.isNullOrEmpty()) label else "倒计时"

        if (otherCount > 0) {
            tvCountdownBadge?.visibility = View.VISIBLE
            tvCountdownBadge?.text = "+$otherCount"
        } else {
            tvCountdownBadge?.visibility = View.GONE
        }
    }

    override fun showCompletion(label: String?, autoDismissSeconds: Int) {
        layoutActive?.visibility = View.GONE
        layoutCompletion?.visibility = View.VISIBLE

        val title = if (!label.isNullOrEmpty()) "$label · 计时完成" else "计时完成"
        tvCountdownCompleteTitle?.text = title
    }

    override fun setOnCancelClicked(listener: () -> Unit) {
        btnCountdownCancel?.setOnClickListener { listener() }
    }

    override fun setOnDismissClicked(listener: () -> Unit) {
        btnCountdownDismiss?.setOnClickListener { listener() }
        layoutCompletion?.setOnClickListener { listener() }
    }
}

/**
 * Controller managing the countdown UI widget on the Home page.
 *
 * Responsibilities:
 * - Listens for [CountdownState] updates.
 * - Expands with smooth fade-in when a countdown starts; hides when idle.
 * - Ticks remaining time and smoothly animates the circular progress ring.
 * - Displays "+1" or "+2" badge when concurrent countdowns are active.
 * - Displays gentle completion card upon expiry.
 */
class HomeCountdownWidgetController(
    private val widgetView: HomeCountdownWidgetView,
    private val onCancel: (() -> Unit)? = null,
    private val onDismiss: (() -> Unit)? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val tickerScheduler: TickerScheduler = DefaultTickerScheduler()
) {
    /**
     * Primary Android constructor wrapping a View hierarchy.
     */
    constructor(
        rootView: View,
        onCancel: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) : this(
        AndroidHomeCountdownWidgetView(rootView),
        onCancel,
        onDismiss
    )

    private var currentState: CountdownState = CountdownState.Idle
    private var isTicking = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            val state = currentState
            if (state is CountdownState.Running) {
                renderRunning(state)
                tickerScheduler.postDelayed(this, 500L)
            } else {
                isTicking = false
            }
        }
    }

    init {
        widgetView.setOnCancelClicked {
            onCancel?.invoke()
        }
        widgetView.setOnDismissClicked {
            onDismiss?.invoke()
        }
    }

    fun isVisible(): Boolean = widgetView.isVisible()

    fun updateState(state: CountdownState, animate: Boolean = true) {
        currentState = state
        when (state) {
            is CountdownState.Running -> {
                renderRunning(state)
                widgetView.setVisible(true, animate)
                startTicking()
            }
            is CountdownState.Completed -> {
                stopTicking()
                widgetView.showCompletion(state.completedItem.label, state.autoDismissSeconds)
                widgetView.setVisible(true, animate)
            }
            is CountdownState.Idle -> {
                stopTicking()
                widgetView.setVisible(false, animate)
            }
        }
    }

    private fun renderRunning(runningState: CountdownState.Running) {
        val now = timeProvider()
        val primary = runningState.primary
        val remSecs = primary.remainingSeconds(now)
        val fraction = primary.remainingFraction(now)
        val timeStr = CountdownItem.formatTime(remSecs)

        widgetView.showRunning(
            timeText = timeStr,
            progressFraction = fraction,
            label = primary.label,
            otherCount = runningState.otherCount
        )
    }

    private fun startTicking() {
        if (!isTicking) {
            isTicking = true
            tickerScheduler.post(tickRunnable)
        }
    }

    private fun stopTicking() {
        isTicking = false
        tickerScheduler.removeCallbacks(tickRunnable)
    }

    fun onAttached() {
        if (currentState is CountdownState.Running) {
            startTicking()
        }
    }

    fun onDetached() {
        stopTicking()
    }
}
