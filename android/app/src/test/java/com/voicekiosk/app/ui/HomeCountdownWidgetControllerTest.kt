package com.voicekiosk.app.ui

import com.voicekiosk.app.countdown.CountdownItem
import com.voicekiosk.app.countdown.CountdownState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeHomeCountdownWidgetView : HomeCountdownWidgetView {
    var visible: Boolean = false
    var lastAnimate: Boolean = false
    var lastTimeText: String = ""
    var lastProgressFraction: Float = 0f
    var lastLabel: String? = null
    var lastOtherCount: Int = 0
    var isShowingCompletion: Boolean = false
    var completionLabel: String? = null
    var completionAutoDismissSec: Int = 0

    var cancelClickListener: (() -> Unit)? = null
    var dismissClickListener: (() -> Unit)? = null

    override fun setVisible(visible: Boolean, animate: Boolean) {
        this.visible = visible
        this.lastAnimate = animate
    }

    override fun isVisible(): Boolean = visible

    override fun showRunning(timeText: String, progressFraction: Float, label: String?, otherCount: Int) {
        isShowingCompletion = false
        lastTimeText = timeText
        lastProgressFraction = progressFraction
        lastLabel = label
        lastOtherCount = otherCount
    }

    override fun showCompletion(label: String?, autoDismissSeconds: Int) {
        isShowingCompletion = true
        completionLabel = label
        completionAutoDismissSec = autoDismissSeconds
    }

    override fun setOnCancelClicked(listener: () -> Unit) {
        cancelClickListener = listener
    }

    override fun setOnDismissClicked(listener: () -> Unit) {
        dismissClickListener = listener
    }
}

class FakeTickerScheduler : TickerScheduler {
    val postedRunnables = mutableListOf<Runnable>()
    val delayedRunnables = mutableListOf<Pair<Runnable, Long>>()

    override fun post(runnable: Runnable) {
        postedRunnables.add(runnable)
    }

    override fun postDelayed(runnable: Runnable, delayMs: Long) {
        delayedRunnables.add(runnable to delayMs)
    }

    override fun removeCallbacks(runnable: Runnable) {
        postedRunnables.remove(runnable)
        delayedRunnables.removeAll { it.first === runnable }
    }

    fun triggerPosted() {
        val copy = ArrayList(postedRunnables)
        postedRunnables.clear()
        copy.forEach { it.run() }
    }
}

class HomeCountdownWidgetControllerTest {

    private lateinit var fakeView: FakeHomeCountdownWidgetView
    private lateinit var fakeScheduler: FakeTickerScheduler
    private var simulatedTimeMs: Long = 1000000L
    private var cancelCallbackCount: Int = 0
    private var dismissCallbackCount: Int = 0

    private lateinit var controller: HomeCountdownWidgetController

    @Before
    fun setUp() {
        fakeView = FakeHomeCountdownWidgetView()
        fakeScheduler = FakeTickerScheduler()
        simulatedTimeMs = 1000000L
        cancelCallbackCount = 0
        dismissCallbackCount = 0

        controller = HomeCountdownWidgetController(
            widgetView = fakeView,
            onCancel = { cancelCallbackCount++ },
            onDismiss = { dismissCallbackCount++ },
            timeProvider = { simulatedTimeMs },
            tickerScheduler = fakeScheduler
        )
    }

    @Test
    fun testInitialVisibility_hiddenByDefault() {
        assertFalse("Widget should initially be hidden", controller.isVisible())
        assertFalse("Fake view should initially be hidden", fakeView.isVisible())
    }

    @Test
    fun testUpdateState_running_expandsAndRendersPrimaryTimer() {
        val timer = CountdownItem(
            durationSeconds = 60,
            label = "Boil Egg",
            startTimeMillis = simulatedTimeMs,
            targetTimeMillis = simulatedTimeMs + 60000L
        )

        controller.updateState(
            CountdownState.Running(primary = timer, otherCount = 0, allActive = listOf(timer)),
            animate = true
        )

        assertTrue("Widget should become visible", controller.isVisible())
        assertTrue("View should be visible", fakeView.visible)
        assertTrue("Animate should be true", fakeView.lastAnimate)
        assertFalse("Should not be showing completion", fakeView.isShowingCompletion)
        assertEquals("Boil Egg", fakeView.lastLabel)
        assertEquals("01:00", fakeView.lastTimeText)
        assertEquals(0, fakeView.lastOtherCount)
    }

    @Test
    fun testUpdateState_running_withMultipleTimers_showsBadgeCount() {
        val timer1 = CountdownItem(durationSeconds = 30, label = "Soonest", startTimeMillis = simulatedTimeMs)
        val timer2 = CountdownItem(durationSeconds = 120, label = "Later", startTimeMillis = simulatedTimeMs)

        controller.updateState(
            CountdownState.Running(primary = timer1, otherCount = 1, allActive = listOf(timer1, timer2)),
            animate = true
        )

        assertEquals(1, fakeView.lastOtherCount)
        assertEquals("Soonest", fakeView.lastLabel)
    }

    @Test
    fun testUpdateState_completed_showsCompletionCard() {
        val completedItem = CountdownItem(durationSeconds = 300, label = "Tea Steeped", startTimeMillis = simulatedTimeMs)

        controller.updateState(
            CountdownState.Completed(completedItem = completedItem, autoDismissSeconds = 30),
            animate = true
        )

        assertTrue(controller.isVisible())
        assertTrue(fakeView.isShowingCompletion)
        assertEquals("Tea Steeped", fakeView.completionLabel)
        assertEquals(30, fakeView.completionAutoDismissSec)
    }

    @Test
    fun testUpdateState_idle_hidesWidget() {
        // First show running
        val timer = CountdownItem(durationSeconds = 60, startTimeMillis = simulatedTimeMs)
        controller.updateState(CountdownState.Running(timer, 0, listOf(timer)), animate = false)
        assertTrue(controller.isVisible())

        // Then transition to idle
        controller.updateState(CountdownState.Idle, animate = true)
        assertFalse(controller.isVisible())
        assertFalse(fakeView.visible)
        assertTrue(fakeView.lastAnimate)
    }

    @Test
    fun testCancelButton_invokesCallback() {
        fakeView.cancelClickListener?.invoke()
        assertEquals(1, cancelCallbackCount)
    }

    @Test
    fun testDismissButton_invokesCallback() {
        fakeView.dismissClickListener?.invoke()
        assertEquals(1, dismissCallbackCount)
    }

    @Test
    fun testTicking_advancesTimeAndProgress() {
        val timer = CountdownItem(
            durationSeconds = 60,
            label = "Countdown",
            startTimeMillis = simulatedTimeMs,
            targetTimeMillis = simulatedTimeMs + 60000L
        )

        controller.updateState(CountdownState.Running(timer, 0, listOf(timer)), animate = false)
        assertEquals("01:00", fakeView.lastTimeText)

        // Advance simulated time by 10 seconds
        simulatedTimeMs += 10000L
        fakeScheduler.triggerPosted()

        assertEquals("00:50", fakeView.lastTimeText)
    }
}
