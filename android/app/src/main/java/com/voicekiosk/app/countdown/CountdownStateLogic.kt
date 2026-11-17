package com.voicekiosk.app.countdown

import java.util.UUID

/**
 * Pure state machine logic for countdown timers.
 *
 * Rules:
 * - At most 3 concurrent countdowns running at once.
 * - The main ring shows the one expiring soonest.
 * - Badge indicates how many others are running (0 others = hidden, 1 = "+1", 2 = "+2").
 * - A new countdown beyond 3 replaces the one expiring latest.
 * - On completion, shows completion card auto-dismissed after 30 seconds.
 */
class CountdownStateLogic(
    val maxConcurrent: Int = 3,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val activeTimers = mutableListOf<CountdownItem>()
    private var currentCompleted: CountdownState.Completed? = null

    val currentState: CountdownState
        get() {
            currentCompleted?.let { return it }
            val primary = soonestExpiringTimer()
            return if (primary != null) {
                CountdownState.Running(
                    primary = primary,
                    otherCount = (activeTimers.size - 1).coerceAtLeast(0),
                    allActive = activeTimers.toList()
                )
            } else {
                CountdownState.Idle
            }
        }

    fun getActiveTimers(): List<CountdownItem> = activeTimers.toList()

    fun getCompletedState(): CountdownState.Completed? = currentCompleted

    /**
     * Finds the active timer that will expire soonest.
     */
    fun soonestExpiringTimer(): CountdownItem? {
        val now = timeProvider()
        return activeTimers.filter { !it.isExpired(now) }
            .minByOrNull { it.targetTimeMillis }
    }

    /**
     * Finds the active timer that will expire latest.
     */
    fun latestExpiringTimer(): CountdownItem? {
        val now = timeProvider()
        return activeTimers.filter { !it.isExpired(now) }
            .maxByOrNull { it.targetTimeMillis }
    }

    /**
     * Adds a new timer.
     * If there are already [maxConcurrent] timers active, replaces the one expiring latest.
     *
     * @return Pair of (addedItem, replacedItem?)
     */
    fun addTimer(
        durationSeconds: Int,
        label: String?,
        id: String = UUID.randomUUID().toString()
    ): Pair<CountdownItem, CountdownItem?> {
        val now = timeProvider()
        // Prune expired timers
        activeTimers.removeAll { it.isExpired(now) }

        var replaced: CountdownItem? = null
        if (activeTimers.size >= maxConcurrent) {
            val latest = latestExpiringTimer()
            if (latest != null) {
                activeTimers.remove(latest)
                replaced = latest
            }
        }

        val newItem = CountdownItem(
            id = id,
            label = label,
            durationSeconds = durationSeconds,
            startTimeMillis = now,
            targetTimeMillis = now + durationSeconds * 1000L
        )
        activeTimers.add(newItem)

        // Reset completed state if a new countdown starts
        currentCompleted = null

        return newItem to replaced
    }

    /**
     * Marks a timer as completed.
     * Transitions state to [CountdownState.Completed] with 30s auto-dismiss.
     */
    fun onTimerExpired(timerId: String): CountdownState.Completed? {
        val index = activeTimers.indexOfFirst { it.id == timerId }
        val item = if (index >= 0) activeTimers.removeAt(index) else return null

        val state = CountdownState.Completed(
            completedItem = item,
            remainingActive = activeTimers.toList(),
            autoDismissSeconds = 30
        )
        currentCompleted = state
        return state
    }

    /**
     * Dismisses the completion state card (either by manual user click or auto-dismiss timeout).
     * @return Next [CountdownState] (either [CountdownState.Running] if other timers remain, or [CountdownState.Idle]).
     */
    fun dismissCompletion(): CountdownState {
        currentCompleted = null
        val now = timeProvider()
        activeTimers.removeAll { it.isExpired(now) }
        return currentState
    }

    /**
     * Cancels all timers and resets state to [CountdownState.Idle].
     * @return Number of active timers cancelled.
     */
    fun cancelAll(): Int {
        val count = activeTimers.size
        activeTimers.clear()
        currentCompleted = null
        return count
    }

    /**
     * Cancels a specific timer.
     */
    fun cancelTimer(timerId: String): Boolean {
        val removed = activeTimers.removeAll { it.id == timerId }
        if (currentCompleted?.completedItem?.id == timerId) {
            currentCompleted = null
        }
        return removed
    }
}
