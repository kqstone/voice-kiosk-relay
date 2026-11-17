package com.voicekiosk.app.countdown

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Singleton coordinator connecting Android platform timers, audio chime, and UI controllers.
 */
object CountdownManager {

    const val MAX_CONCURRENT_COUNTDOWNS = 3
    const val COMPLETION_AUTO_DISMISS_SECONDS = 30

    interface Listener {
        fun onCountdownStateChanged(state: CountdownState)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val chimePlayer = ChimePlayer(mainHandler)
    private val listeners = CopyOnWriteArrayList<Listener>()

    // Thread-safe map of active scheduled completion callbacks
    private val scheduledRunnables = ConcurrentHashMap<String, Runnable>()
    private var autoDismissRunnable: Runnable? = null

    internal var stateLogic = CountdownStateLogic(
        maxConcurrent = MAX_CONCURRENT_COUNTDOWNS
    )

    val currentState: CountdownState
        get() = stateLogic.currentState

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
        // Emit current state immediately to newly attached listener
        listener.onCountdownStateChanged(currentState)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val state = currentState
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onCountdownStateChanged(state)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Starts a countdown timer.
     *
     * - Capped at 3 concurrent timers.
     * - If beyond 3, replaces the one expiring latest.
     * - Schedules timer execution and notifies UI listeners immediately.
     * - On expiry, triggers [onFired], plays 3 gentle chimes, and shows completion card for 30s.
     */
    fun startTimer(
        seconds: Int,
        label: String?,
        onFired: (label: String?, seconds: Int) -> Unit
    ): Boolean {
        if (seconds <= 0) return false

        // Cancel any pending auto-dismiss from a previous completed card
        cancelAutoDismiss()

        val (newItem, replacedItem) = stateLogic.addTimer(seconds, label)

        // Cancel the scheduled callback of the replaced timer if any
        if (replacedItem != null) {
            scheduledRunnables.remove(replacedItem.id)?.let {
                mainHandler.removeCallbacks(it)
            }
        }

        // Schedule timer completion
        val runnable = Runnable {
            scheduledRunnables.remove(newItem.id)

            // 1. Maintain untouched in-app duration timer execution behavior
            try {
                onFired(label, seconds)
            } catch (_: Throwable) {}

            // 2. Play gentle chime 3 times
            chimePlayer.playChimes(3)

            // 3. Mark completed and schedule 30s auto-dismiss
            stateLogic.onTimerExpired(newItem.id)
            scheduleAutoDismiss()

            notifyListeners()
        }

        scheduledRunnables[newItem.id] = runnable
        mainHandler.postDelayed(runnable, seconds * 1000L)

        // 4. Notify UI controller immediately to trigger smooth expand animation
        notifyListeners()
        return true
    }

    /**
     * Cancels all running countdowns and any active completion card.
     */
    fun cancelTimers(): Int {
        for ((_, r) in scheduledRunnables) {
            mainHandler.removeCallbacks(r)
        }
        scheduledRunnables.clear()
        cancelAutoDismiss()

        val count = stateLogic.cancelAll()
        notifyListeners()
        return count
    }

    /**
     * Dismisses the completion card immediately (e.g. user taps "知道了").
     */
    fun dismissCompletion() {
        cancelAutoDismiss()
        stateLogic.dismissCompletion()
        notifyListeners()
    }

    private fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        val r = Runnable {
            stateLogic.dismissCompletion()
            notifyListeners()
        }
        autoDismissRunnable = r
        mainHandler.postDelayed(r, COMPLETION_AUTO_DISMISS_SECONDS * 1000L)
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let {
            mainHandler.removeCallbacks(it)
            autoDismissRunnable = null
        }
    }

    /**
     * Visible for testing to reset state cleanly.
     */
    internal fun resetForTesting(customLogic: CountdownStateLogic? = null) {
        cancelTimers()
        stateLogic = customLogic ?: CountdownStateLogic(maxConcurrent = MAX_CONCURRENT_COUNTDOWNS)
    }
}
