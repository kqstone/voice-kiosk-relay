package com.voicekiosk.app.ui

import com.voicekiosk.app.media.MediaControlAction
import com.voicekiosk.app.media.MusicState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HomeMusicWidgetControllerTest {

    private lateinit var fakeView: FakeHomeMusicWidgetView
    private lateinit var fakeScheduler: FakeTickerScheduler
    private var simulatedTimeMs: Long = 1000L
    private val dispatchedMediaActions = mutableListOf<MediaControlAction>()
    private var notificationSettingsOpenedCount: Int = 0

    private lateinit var controller: HomeMusicWidgetController

    @Before
    fun setUp() {
        fakeView = FakeHomeMusicWidgetView()
        fakeScheduler = FakeTickerScheduler()
        simulatedTimeMs = 1000L
        dispatchedMediaActions.clear()
        notificationSettingsOpenedCount = 0

        controller = HomeMusicWidgetController(
            widgetView = fakeView,
            onMediaControl = { dispatchedMediaActions.add(it) },
            onOpenNotificationSettings = { notificationSettingsOpenedCount++ },
            timeProvider = { simulatedTimeMs },
            tickerScheduler = fakeScheduler
        )
    }

    // =========================================================================
    // 1. Show and Hide Behavior
    // =========================================================================

    @Test
    fun testInitialWidgetVisibility_hiddenByDefault() {
        assertFalse("Widget should initially be hidden", controller.isVisible())
        assertFalse("Fake view should initially be hidden", fakeView.isVisible())
    }

    @Test
    fun testShowAndHide_explicitCalls() {
        // Show with default animation
        controller.show(animate = true)
        assertTrue(controller.isVisible())
        assertTrue(fakeView.visible)
        assertTrue(fakeView.lastAnimate)

        // Hide with animation
        controller.hide(animate = true)
        assertFalse(controller.isVisible())
        assertFalse(fakeView.visible)
        assertTrue(fakeView.lastAnimate)

        // Show without animation
        controller.show(animate = false)
        assertTrue(controller.isVisible())
        assertTrue(fakeView.visible)
        assertFalse(fakeView.lastAnimate)

        // Hide without animation
        controller.hide(animate = false)
        assertFalse(controller.isVisible())
        assertFalse(fakeView.visible)
        assertFalse(fakeView.lastAnimate)
    }

    @Test
    fun testSetVisible_updatesViewState() {
        controller.setVisible(visible = true, animate = true)
        assertTrue(controller.isVisible())
        assertTrue(fakeView.visible)

        controller.setVisible(visible = false, animate = false)
        assertFalse(controller.isVisible())
        assertFalse(fakeView.visible)
    }

    // =========================================================================
    // 2. Track Title Formatting
    // =========================================================================

    @Test
    fun testFormatTrackTitle_playingWithValidTitle() {
        val state = MusicState(isPlaying = true, title = "晴天")
        assertEquals("晴天", HomeMusicWidgetController.formatTrackTitle(state))
    }

    @Test
    fun testFormatTrackTitle_pausedWithValidTitle() {
        val state = MusicState(isPlaying = false, title = "稻香")
        assertEquals("稻香", HomeMusicWidgetController.formatTrackTitle(state))
    }

    @Test
    fun testFormatTrackTitle_emptyTrackName_whenPlaying() {
        val state = MusicState(isPlaying = true, title = "")
        assertEquals("正在播放音频", HomeMusicWidgetController.formatTrackTitle(state))
    }

    @Test
    fun testFormatTrackTitle_emptyTrackName_whenPaused() {
        val state = MusicState(isPlaying = false, title = "")
        assertEquals("音频已暂停", HomeMusicWidgetController.formatTrackTitle(state))
    }

    // =========================================================================
    // 3. Artist and Source App Formatting
    // =========================================================================

    @Test
    fun testFormatArtistAndSource_bothArtistAndAppName() {
        val state = MusicState(
            artist = "周杰伦",
            appName = "网易云音乐",
            hasNotificationAccess = true
        )
        assertEquals("周杰伦 · 网易云音乐", HomeMusicWidgetController.formatArtistAndSource(state))
    }

    @Test
    fun testFormatArtistAndSource_onlyArtist() {
        val state = MusicState(
            artist = "陈奕迅",
            appName = "",
            hasNotificationAccess = true
        )
        assertEquals("陈奕迅", HomeMusicWidgetController.formatArtistAndSource(state))
    }

    @Test
    fun testFormatArtistAndSource_onlyAppName() {
        val state = MusicState(
            artist = "",
            appName = "QQ音乐",
            hasNotificationAccess = true
        )
        assertEquals("QQ音乐", HomeMusicWidgetController.formatArtistAndSource(state))
    }

    @Test
    fun testFormatArtistAndSource_missingNotificationPermission() {
        val state = MusicState(
            artist = "",
            appName = "",
            hasNotificationAccess = false
        )
        assertEquals("未授予通知使用权 · 点击开启", HomeMusicWidgetController.formatArtistAndSource(state))
    }

    @Test
    fun testFormatArtistAndSource_unknownArtistFallback() {
        val state = MusicState(
            artist = "",
            appName = "",
            hasNotificationAccess = true
        )
        assertEquals("未知艺术家", HomeMusicWidgetController.formatArtistAndSource(state))
    }

    // =========================================================================
    // 4. Time and Progress Formatting
    // =========================================================================

    @Test
    fun testFormatTime_values() {
        assertEquals("00:00", HomeMusicWidgetController.formatTime(0L))
        assertEquals("00:05", HomeMusicWidgetController.formatTime(5000L))
        assertEquals("00:59", HomeMusicWidgetController.formatTime(59000L))
        assertEquals("01:00", HomeMusicWidgetController.formatTime(60000L))
        assertEquals("03:35", HomeMusicWidgetController.formatTime(215000L))
        assertEquals("61:01", HomeMusicWidgetController.formatTime(3661000L))
        // Negative ms coerced to 0
        assertEquals("00:00", HomeMusicWidgetController.formatTime(-5000L))
    }

    @Test
    fun testFormatProgressText_values() {
        // Valid elapsed and duration
        assertEquals(
            "01:05 / 03:35",
            HomeMusicWidgetController.formatProgressText(65000L, 215000L)
        )
        // Zero or unknown duration
        assertEquals(
            "00:45 / --:--",
            HomeMusicWidgetController.formatProgressText(45000L, 0L)
        )
        // Negative duration
        assertEquals(
            "00:10 / --:--",
            HomeMusicWidgetController.formatProgressText(10000L, -1L)
        )
    }

    // =========================================================================
    // 5. Binding State & View Updates
    // =========================================================================

    @Test
    fun testBind_playingState_updatesAllViewElementsAndSchedulesTicker() {
        val playingState = MusicState(
            isPlaying = true,
            title = "夜曲",
            artist = "周杰伦",
            appName = "QQ音乐",
            positionMs = 30000L,
            lastPositionUpdateTime = 1000L,
            durationMs = 210000L,
            hasNotificationAccess = true
        )

        controller.bind(playingState)

        assertEquals("夜曲", fakeView.lastTitle)
        assertEquals("周杰伦 · QQ音乐", fakeView.lastArtistAndSource)
        assertTrue("Play/Pause icon should indicate playing", fakeView.isPlayingIcon)
        assertEquals(30000L, fakeView.currentProgressMs)
        assertEquals(210000L, fakeView.durationProgressMs)
        assertTrue(fakeView.isPlayingProgress)

        // 1-second progress ticker should be scheduled
        assertEquals(1, fakeScheduler.delayedTasks.size)
        assertEquals(1000L, fakeScheduler.delayedTasks.first().second)
    }

    @Test
    fun testBind_pausedState_updatesViewsAndCancelsTicker() {
        // First bind playing
        val playingState = MusicState(
            isPlaying = true,
            title = "夜曲",
            durationMs = 210000L
        )
        controller.bind(playingState)
        assertEquals(1, fakeScheduler.delayedTasks.size)

        // Now bind paused
        val pausedState = MusicState(
            isPlaying = false,
            title = "夜曲",
            durationMs = 210000L
        )
        controller.bind(pausedState)

        assertEquals("夜曲", fakeView.lastTitle)
        assertFalse("Play/Pause icon should indicate paused", fakeView.isPlayingIcon)
        // Ticker should be removed
        assertEquals(0, fakeScheduler.delayedTasks.size)
    }

    @Test
    fun testBind_missingNotificationPermission_wiresSettingsJump() {
        val noPermState = MusicState(
            isPlaying = false,
            title = "",
            artist = "",
            appName = "",
            hasNotificationAccess = false
        )

        controller.bind(noPermState)

        assertEquals("未授予通知使用权 · 点击开启", fakeView.lastArtistAndSource)
        assertEquals(0, notificationSettingsOpenedCount)

        // Trigger settings click callback
        fakeView.openNotificationSettingsListener?.invoke()
        assertEquals(1, notificationSettingsOpenedCount)
    }

    // =========================================================================
    // 6. Button Debounce & Media Control Routing
    // =========================================================================

    @Test
    fun testMediaControlActions_dispatchesSuccessfully() {
        // Previous
        fakeView.triggerMediaControl(MediaControlAction.PREVIOUS)
        assertEquals(listOf(MediaControlAction.PREVIOUS), dispatchedMediaActions)

        // Advance simulated time past debounce threshold (300ms)
        simulatedTimeMs += 350L

        // Play/Pause
        fakeView.triggerMediaControl(MediaControlAction.PLAY_PAUSE)
        assertEquals(
            listOf(MediaControlAction.PREVIOUS, MediaControlAction.PLAY_PAUSE),
            dispatchedMediaActions
        )

        // Advance time again
        simulatedTimeMs += 350L

        // Next
        fakeView.triggerMediaControl(MediaControlAction.NEXT)
        assertEquals(
            listOf(MediaControlAction.PREVIOUS, MediaControlAction.PLAY_PAUSE, MediaControlAction.NEXT),
            dispatchedMediaActions
        )
    }

    @Test
    fun testButtonDebounce_ignoresRapidClicksWithin300ms() {
        // First click at t=1000ms
        fakeView.triggerMediaControl(MediaControlAction.PLAY_PAUSE)
        assertEquals(1, dispatchedMediaActions.size)

        // Rapid second click at t=1100ms (+100ms, within 300ms debounce interval)
        simulatedTimeMs = 1100L
        fakeView.triggerMediaControl(MediaControlAction.PLAY_PAUSE)
        assertEquals("Second rapid click within 300ms should be debounced", 1, dispatchedMediaActions.size)

        // Rapid third click at t=1250ms (+250ms from first)
        simulatedTimeMs = 1250L
        fakeView.triggerMediaControl(MediaControlAction.PLAY_PAUSE)
        assertEquals("Third click within 300ms should be debounced", 1, dispatchedMediaActions.size)

        // Legitimate click at t=1301ms (> 300ms since last dispatched click at 1000ms)
        simulatedTimeMs = 1301L
        fakeView.triggerMediaControl(MediaControlAction.PLAY_PAUSE)
        assertEquals("Click after 300ms should be dispatched", 2, dispatchedMediaActions.size)
    }

    // =========================================================================
    // 7. Lifecycle & Ticker Management
    // =========================================================================

    @Test
    fun testLifecycle_onAttachedWhenPlaying_postsImmediateUpdate() {
        val playingState = MusicState(
            isPlaying = true,
            title = "晴天",
            durationMs = 180000L
        )
        controller.bind(playingState)

        controller.onAttached()
        assertEquals(1, fakeScheduler.postedTasks.size)
    }

    @Test
    fun testLifecycle_onDetached_cancelsTicker() {
        val playingState = MusicState(
            isPlaying = true,
            title = "晴天",
            durationMs = 180000L
        )
        controller.bind(playingState)
        assertEquals(1, fakeScheduler.delayedTasks.size)

        controller.onDetached()
        assertEquals(0, fakeScheduler.delayedTasks.size)
    }

    @Test
    fun testTickerExecution_advancesProgressAndReschedulesWhenPlaying() {
        val playingState = MusicState(
            isPlaying = true,
            title = "晴天",
            positionMs = 10000L,
            lastPositionUpdateTime = 1000L,
            playbackSpeed = 1.0f,
            durationMs = 180000L
        )
        controller.bind(playingState)
        assertEquals(10000L, fakeView.currentProgressMs)
        assertEquals(1, fakeScheduler.delayedTasks.size)

        // Advance simulated time by 1 second (1000ms)
        simulatedTimeMs += 1000L
        val runnable = fakeScheduler.delayedTasks.first().first

        // Execute scheduled tick
        runnable.run()

        // Progress should extrapolate: 10000 + 1000 = 11000ms
        assertEquals(11000L, fakeView.currentProgressMs)

        // Should reschedule itself for the next second
        assertTrue(fakeScheduler.delayedTasks.isNotEmpty())
        assertEquals(1000L, fakeScheduler.delayedTasks.last().second)
    }

    // =========================================================================
    // Test Doubles (Fakes)
    // =========================================================================

    private class FakeHomeMusicWidgetView : HomeMusicWidgetView {
        var visible: Boolean = false
        var lastAnimate: Boolean = false
        var lastTitle: String = ""
        var lastArtistAndSource: String = ""
        var currentProgressMs: Long = 0L
        var durationProgressMs: Long = 0L
        var isPlayingProgress: Boolean = false
        var isPlayingIcon: Boolean = false

        val mediaControlListeners = mutableMapOf<MediaControlAction, () -> Unit>()
        var openNotificationSettingsListener: (() -> Unit)? = null

        override fun setVisible(visible: Boolean, animate: Boolean) {
            this.visible = visible
            this.lastAnimate = animate
        }

        override fun isVisible(): Boolean = visible

        override fun setTitle(title: String) {
            this.lastTitle = title
        }

        override fun setArtistAndSource(text: String) {
            this.lastArtistAndSource = text
        }

        override fun setProgress(currentMs: Long, durationMs: Long, isPlaying: Boolean) {
            this.currentProgressMs = currentMs
            this.durationProgressMs = durationMs
            this.isPlayingProgress = isPlaying
        }

        override fun setPlayPauseIcon(isPlaying: Boolean) {
            this.isPlayingIcon = isPlaying
        }

        override fun setOnMediaControl(action: MediaControlAction, listener: () -> Unit) {
            mediaControlListeners[action] = listener
        }

        override fun setOnOpenNotificationSettings(listener: () -> Unit) {
            this.openNotificationSettingsListener = listener
        }

        fun triggerMediaControl(action: MediaControlAction) {
            mediaControlListeners[action]?.invoke()
        }
    }

    private class FakeTickerScheduler : TickerScheduler {
        val postedTasks = mutableListOf<Runnable>()
        val delayedTasks = mutableListOf<Pair<Runnable, Long>>()
        val removedTasks = mutableListOf<Runnable>()

        override fun post(runnable: Runnable) {
            postedTasks.add(runnable)
        }

        override fun postDelayed(runnable: Runnable, delayMs: Long) {
            delayedTasks.add(Pair(runnable, delayMs))
        }

        override fun removeCallbacks(runnable: Runnable) {
            removedTasks.add(runnable)
            delayedTasks.removeAll { it.first == runnable }
            postedTasks.removeAll { it == runnable }
        }
    }
}
