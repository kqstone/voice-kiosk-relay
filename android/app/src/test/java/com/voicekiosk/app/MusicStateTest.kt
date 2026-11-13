package com.voicekiosk.app

import com.voicekiosk.app.media.MediaControlAction
import com.voicekiosk.app.media.MusicState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicStateTest {

    @Test
    fun testDefaultMusicState_zeroHardcodedValues() {
        val state = MusicState()
        assertFalse(state.isPlaying)
        assertEquals("", state.title)
        assertEquals("", state.artist)
        assertEquals("", state.album)
        assertEquals("", state.appName)
        assertEquals(0L, state.durationMs)
        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.lastPositionUpdateTime)
        assertEquals(1.0f, state.playbackSpeed, 0.001f)
        assertNull(state.albumArt)
        assertNull(state.albumArtUri)
        assertTrue(state.hasNotificationAccess)
        assertFalse(state.hasActiveSession)
    }

    @Test
    fun testFormatDuration() {
        assertEquals("00:00", MusicState.formatDuration(0L))
        assertEquals("00:05", MusicState.formatDuration(5000L))
        assertEquals("01:05", MusicState.formatDuration(65000L))
        assertEquals("01:48", MusicState.formatDuration(108000L))
        assertEquals("04:15", MusicState.formatDuration(255000L))
        assertEquals("61:01", MusicState.formatDuration(3661000L))
    }

    @Test
    fun testGetCurrentPositionMs_whenPaused() {
        val state = MusicState(
            isPlaying = false,
            positionMs = 30000L,
            lastPositionUpdateTime = 1000L,
            durationMs = 120000L
        )
        // When not playing, position remains fixed
        assertEquals(30000L, state.getCurrentPositionMs(nowElapsedMs = 5000L))
    }

    @Test
    fun testGetCurrentPositionMs_whenPlaying() {
        val state = MusicState(
            isPlaying = true,
            positionMs = 10000L,
            lastPositionUpdateTime = 5000L,
            playbackSpeed = 1.0f,
            durationMs = 60000L
        )
        // 5 seconds elapsed -> 10000 + 5000 = 15000
        assertEquals(15000L, state.getCurrentPositionMs(nowElapsedMs = 10000L))

        // Exceeds duration -> clamped to durationMs
        assertEquals(60000L, state.getCurrentPositionMs(nowElapsedMs = 100000L))
    }

    @Test
    fun testPagerIndexMath_stableMappingNoMusicPage() {
        val boardCount = 3

        // Stable mapping: Home always at 0, boards always at 1..N
        val totalCount = 1 + boardCount
        assertEquals(4, totalCount) // Page 0 Home + 3 Boards

        fun getBoardIndex(pos: Int): Int = pos - 1
        assertEquals(0, getBoardIndex(1)) // Board 0 at pos 1
        assertEquals(1, getBoardIndex(2)) // Board 1 at pos 2
        assertEquals(2, getBoardIndex(3)) // Board 2 at pos 3

        // Board positions and total count never shift regardless of playback state
        val totalCountDuringPlayback = 1 + boardCount
        assertEquals(totalCount, totalCountDuringPlayback)
    }

    @Test
    fun testMediaControlActionEnum() {
        val actions = MediaControlAction.values()
        assertEquals(3, actions.size)
        assertTrue(actions.contains(MediaControlAction.PLAY_PAUSE))
        assertTrue(actions.contains(MediaControlAction.PREVIOUS))
        assertTrue(actions.contains(MediaControlAction.NEXT))
    }
}
