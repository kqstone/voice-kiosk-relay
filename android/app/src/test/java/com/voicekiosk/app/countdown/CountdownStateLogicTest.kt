package com.voicekiosk.app.countdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for countdown timer state logic without device dependencies.
 *
 * Verifies:
 * - Soonest-expiry selection among concurrent timers.
 * - At most 3 concurrent timers cap.
 * - Replacement of the latest-expiring timer when adding beyond 3.
 * - Multi-timer badge count (+1, +2).
 * - Expiry, gentle completion state, and 30-second auto-dismiss timeout.
 * - Cancellation and pruning.
 */
class CountdownStateLogicTest {

    private var simulatedTimeMs: Long = 1000000L
    private lateinit var logic: CountdownStateLogic

    @Before
    fun setUp() {
        simulatedTimeMs = 1000000L
        logic = CountdownStateLogic(
            maxConcurrent = 3,
            timeProvider = { simulatedTimeMs }
        )
    }

    // =========================================================================
    // 1. Soonest-Expiry Selection Tests
    // =========================================================================

    @Test
    fun testSoonestExpirySelection_singleTimer_isPrimary() {
        logic.addTimer(durationSeconds = 60, label = "Egg Timer")
        val state = logic.currentState

        assertTrue("State should be Running", state is CountdownState.Running)
        val running = state as CountdownState.Running
        assertEquals("Egg Timer", running.primary.label)
        assertEquals(60, running.primary.durationSeconds)
        assertEquals(0, running.otherCount)
    }

    @Test
    fun testSoonestExpirySelection_multipleTimers_selectsEarliestExpiring() {
        // Add Timer 1: 10 minutes (expires at t + 600s)
        logic.addTimer(durationSeconds = 600, label = "Oven")

        // Add Timer 2: 3 minutes (expires at t + 180s) -> should become primary!
        logic.addTimer(durationSeconds = 180, label = "Boiling Tea")

        // Add Timer 3: 5 minutes (expires at t + 300s)
        logic.addTimer(durationSeconds = 300, label = "Microwave")

        val soonest = logic.soonestExpiringTimer()
        assertNotNull(soonest)
        assertEquals("Boiling Tea", soonest?.label)
        assertEquals(180, soonest?.durationSeconds)

        val state = logic.currentState as CountdownState.Running
        assertEquals("Boiling Tea", state.primary.label)
        assertEquals(2, state.otherCount) // 2 other timers running
    }

    // =========================================================================
    // 2. Maximum 3 Concurrent Timers & Replacement of Latest
    // =========================================================================

    @Test
    fun testConcurrentLimit_underCap_allKept() {
        val (item1, replaced1) = logic.addTimer(durationSeconds = 60, label = "First")
        assertNull(replaced1)
        assertEquals(1, logic.getActiveTimers().size)

        val (item2, replaced2) = logic.addTimer(durationSeconds = 120, label = "Second")
        assertNull(replaced2)
        assertEquals(2, logic.getActiveTimers().size)

        val (item3, replaced3) = logic.addTimer(durationSeconds = 180, label = "Third")
        assertNull(replaced3)
        assertEquals(3, logic.getActiveTimers().size)
    }

    @Test
    fun testConcurrentLimit_exceedingCap_replacesLatestExpiringTimer() {
        // Active timers:
        // Timer A: expires in 60s (target = 1000000 + 60000 = 1060000)
        // Timer B: expires in 300s (target = 1000000 + 300000 = 1300000) -> LATEST
        // Timer C: expires in 120s (target = 1000000 + 120000 = 1120000)
        logic.addTimer(durationSeconds = 60, label = "Timer A")
        logic.addTimer(durationSeconds = 300, label = "Timer B (Latest)")
        logic.addTimer(durationSeconds = 120, label = "Timer C")

        assertEquals(3, logic.getActiveTimers().size)
        assertEquals("Timer B (Latest)", logic.latestExpiringTimer()?.label)

        // Now add 4th Timer D: expires in 90s
        val (itemD, replaced) = logic.addTimer(durationSeconds = 90, label = "Timer D")

        // Verify that the replaced timer was strictly Timer B (the latest expiring one)
        assertNotNull("Replaced timer must not be null", replaced)
        assertEquals("Timer B (Latest)", replaced?.label)
        assertEquals("Timer D", itemD.label)

        // Active timers should still be capped at 3: Timer A (60s), Timer D (90s), Timer C (120s)
        val active = logic.getActiveTimers()
        assertEquals(3, active.size)
        assertTrue(active.any { it.label == "Timer A" })
        assertTrue(active.any { it.label == "Timer D" })
        assertTrue(active.any { it.label == "Timer C" })
        assertFalse("Timer B must have been replaced", active.any { it.label == "Timer B (Latest)" })

        // Primary is still Timer A (expires in 60s)
        val state = logic.currentState as CountdownState.Running
        assertEquals("Timer A", state.primary.label)
        assertEquals(2, state.otherCount)
    }

    // =========================================================================
    // 3. Multi-Timer Badge Count Tests
    // =========================================================================

    @Test
    fun testBadgeCount_correctForAllCounts() {
        assertEquals(CountdownState.Idle, logic.currentState)

        // 1 timer -> otherCount = 0 (badge should be hidden)
        logic.addTimer(60, "First")
        var running = logic.currentState as CountdownState.Running
        assertEquals(0, running.otherCount)

        // 2 timers -> otherCount = 1 (badge should show +1)
        logic.addTimer(120, "Second")
        running = logic.currentState as CountdownState.Running
        assertEquals(1, running.otherCount)

        // 3 timers -> otherCount = 2 (badge should show +2)
        logic.addTimer(180, "Third")
        running = logic.currentState as CountdownState.Running
        assertEquals(2, running.otherCount)
    }

    // =========================================================================
    // 4. Expiry, Completion Card & 30-Second Auto-Dismiss
    // =========================================================================

    @Test
    fun testTimerCompletion_transitionsToCompletedWith30sDismiss() {
        val (item, _) = logic.addTimer(60, "Pasta")
        assertEquals(1, logic.getActiveTimers().size)

        // Simulate timer expiring
        val completedState = logic.onTimerExpired(item.id)
        assertNotNull(completedState)
        assertEquals(30, completedState?.autoDismissSeconds)
        assertEquals("Pasta", completedState?.completedItem?.label)

        // State is now Completed
        val state = logic.currentState
        assertTrue("State must be Completed", state is CountdownState.Completed)
        assertEquals(0, logic.getActiveTimers().size)
    }

    @Test
    fun testDismissCompletion_whenNoRemainingTimers_returnsToIdle() {
        val (item, _) = logic.addTimer(30, "Quick Alert")
        logic.onTimerExpired(item.id)
        assertTrue(logic.currentState is CountdownState.Completed)

        val nextState = logic.dismissCompletion()
        assertEquals(CountdownState.Idle, nextState)
        assertEquals(CountdownState.Idle, logic.currentState)
    }

    @Test
    fun testDismissCompletion_whenOtherTimersRemain_transitionsToNextRunningTimer() {
        val (item1, _) = logic.addTimer(30, "Soonest")
        val (item2, _) = logic.addTimer(120, "Later")

        // item1 expires
        logic.onTimerExpired(item1.id)
        val completed = logic.currentState as CountdownState.Completed
        assertEquals("Soonest", completed.completedItem.label)
        assertEquals(1, completed.remainingActive.size)

        // User or timeout dismisses the completion card
        val nextState = logic.dismissCompletion()
        assertTrue("Next state must be Running", nextState is CountdownState.Running)
        val running = nextState as CountdownState.Running
        assertEquals("Later", running.primary.label)
        assertEquals(0, running.otherCount) // only 1 remaining
    }

    // =========================================================================
    // 5. Cancellation Tests
    // =========================================================================

    @Test
    fun testCancelAll_resetsEverything() {
        logic.addTimer(60, "T1")
        logic.addTimer(120, "T2")
        assertEquals(2, logic.getActiveTimers().size)

        val cancelledCount = logic.cancelAll()
        assertEquals(2, cancelledCount)
        assertEquals(0, logic.getActiveTimers().size)
        assertEquals(CountdownState.Idle, logic.currentState)
    }

    @Test
    fun testCancelTimer_removesSpecificTimer() {
        val (item1, _) = logic.addTimer(60, "T1")
        val (item2, _) = logic.addTimer(120, "T2")

        val removed = logic.cancelTimer(item1.id)
        assertTrue(removed)
        assertEquals(1, logic.getActiveTimers().size)
        assertEquals("T2", logic.getActiveTimers()[0].label)
    }

    // =========================================================================
    // 6. Formatting & Fraction Tests
    // =========================================================================

    @Test
    fun testFormatTime_correctDigits() {
        assertEquals("00:00", CountdownItem.formatTime(0))
        assertEquals("00:05", CountdownItem.formatTime(5))
        assertEquals("00:59", CountdownItem.formatTime(59))
        assertEquals("01:00", CountdownItem.formatTime(60))
        assertEquals("09:45", CountdownItem.formatTime(585))
        assertEquals("1:00:00", CountdownItem.formatTime(3600))
        assertEquals("1:01:05", CountdownItem.formatTime(3665))
    }

    @Test
    fun testRemainingFraction_calculatesLinearProgress() {
        val item = CountdownItem(
            durationSeconds = 100,
            startTimeMillis = 1000L,
            targetTimeMillis = 101000L
        )

        // At start: 1.0f
        assertEquals(1.0f, item.remainingFraction(nowMillis = 1000L), 0.001f)

        // Halfway (after 50 seconds): 0.5f
        assertEquals(0.5f, item.remainingFraction(nowMillis = 51000L), 0.001f)

        // At end: 0.0f
        assertEquals(0.0f, item.remainingFraction(nowMillis = 101000L), 0.001f)

        // Past end: clamped at 0.0f
        assertEquals(0.0f, item.remainingFraction(nowMillis = 120000L), 0.001f)
    }
}
