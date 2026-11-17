package com.voicekiosk.app.countdown

/**
 * Lifecycle and presentation state for countdown timers.
 */
sealed class CountdownState {

    /**
     * No active countdown or completion card. Component should be hidden.
     */
    object Idle : CountdownState()

    /**
     * One or more countdowns actively ticking down.
     *
     * @property primary The countdown expiring soonest, displayed on the main ring.
     * @property otherCount Number of other concurrent countdowns running (0..2).
     * @property allActive All currently active countdowns.
     */
    data class Running(
        val primary: CountdownItem,
        val otherCount: Int,
        val allActive: List<CountdownItem>
    ) : CountdownState()

    /**
     * A countdown has completed. Displays completion card with gentle chime.
     *
     * @property completedItem The countdown that just completed.
     * @property remainingActive Any other countdowns that are still running concurrently.
     * @property autoDismissSeconds Time before this card is automatically dismissed (decided: 30s).
     */
    data class Completed(
        val completedItem: CountdownItem,
        val remainingActive: List<CountdownItem> = emptyList(),
        val autoDismissSeconds: Int = 30
    ) : CountdownState()
}
