package com.voicekiosk.app.ui

import android.content.res.Configuration
import androidx.recyclerview.widget.RecyclerView
import com.voicekiosk.app.data.BoardModel
import com.voicekiosk.app.data.TitleComponent
import com.voicekiosk.app.media.MusicState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit test for MainPagerAdapter verifying stable pager indexing and page counts.
 *
 * Requirements (Phase 5):
 * - Page 0 is always the Home screen.
 * - Page 1 is always the Schedule Board page.
 * - Pages 2..N+1 are dynamic board pages.
 * - Board positions must never shift when playback starts, updates, or stops.
 * - Page count must always equal 2 + boards.size, completely invariant to playback state.
 */
class MainPagerAdapterTest {

    private fun createFakeBoard(id: String, title: String = id): BoardModel {
        return BoardModel(
            boardId = id,
            titleComponent = TitleComponent(title = title),
            components = emptyList()
        )
    }

    @Test
    fun testItemCount_alwaysEqualsTwoPlusBoardCount() {
        // Zero boards: Home screen + Schedule Board (count = 2)
        val adapterZero = MainPagerAdapter(emptyList())
        assertEquals(2, adapterZero.itemCount)

        // 3 boards: Home screen + Schedule Board + 3 boards (count = 5)
        val boards3 = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2"),
            createFakeBoard("b3", "Board 3")
        )
        val adapter3 = MainPagerAdapter(boards3)
        assertEquals(5, adapter3.itemCount)

        // 5 boards: Home screen + Schedule Board + 5 boards (count = 7)
        val boards5 = (1..5).map { createFakeBoard("b$it", "Board $it") }
        val adapter5 = MainPagerAdapter(boards5)
        assertEquals(7, adapter5.itemCount)
    }

    @Test
    fun testStableMapping_homeAtZero_scheduleAtOne_boardsAtTwoAndUp() {
        val boards = listOf(
            createFakeBoard("alpha", "Alpha"),
            createFakeBoard("beta", "Beta"),
            createFakeBoard("gamma", "Gamma")
        )
        val adapter = MainPagerAdapter(boards)

        // Position 0 is always Home
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))

        // Position 1 is always Schedule Board
        assertEquals(MainPagerAdapter.VIEW_TYPE_SCHEDULE, adapter.getItemViewType(1))
        assertEquals(1L, adapter.getItemId(1))

        // Positions 2..4 are Boards in order
        assertEquals(MainPagerAdapter.VIEW_TYPE_BOARD, adapter.getItemViewType(2))
        assertEquals(MainPagerAdapter.VIEW_TYPE_BOARD, adapter.getItemViewType(3))
        assertEquals(MainPagerAdapter.VIEW_TYPE_BOARD, adapter.getItemViewType(4))

        assertEquals(2, adapter.getBoardPagePosition("alpha"))
        assertEquals(3, adapter.getBoardPagePosition("beta"))
        assertEquals(4, adapter.getBoardPagePosition("gamma"))
    }

    @Test
    fun testPageCount_doesNotChangeWhenPlaybackStartsOrStops() {
        val boards = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2"),
            createFakeBoard("b3", "Board 3")
        )
        val adapter = MainPagerAdapter(boards)

        // 1. Initial state: no playback (Home + Schedule + 3 Boards = 5)
        assertEquals(5, adapter.itemCount)

        // 2. Playback starts
        val playingState = MusicState(
            isPlaying = true,
            title = "夜曲",
            artist = "周杰伦",
            appName = "网易云音乐"
        )
        adapter.updateMusicState(playingState)
        assertEquals(
            "Page count must not increase when music starts playing",
            5,
            adapter.itemCount
        )

        // 3. Playback updates with progress/metadata
        val updatedState = playingState.copy(
            positionMs = 30000L,
            durationMs = 240000L
        )
        adapter.updateMusicState(updatedState)
        assertEquals(
            "Page count must not change on playback metadata update",
            5,
            adapter.itemCount
        )

        // 4. Playback paused
        val pausedState = updatedState.copy(isPlaying = false)
        adapter.updateMusicState(pausedState)
        assertEquals(
            "Page count must not change when music pauses",
            5,
            adapter.itemCount
        )

        // 5. Playback stops / session destroyed
        val stoppedState = MusicState(isPlaying = false)
        adapter.updateMusicState(stoppedState)
        assertEquals(
            "Page count must not change when music stops",
            5,
            adapter.itemCount
        )
    }

    @Test
    fun testBoardPositions_neverShiftWhenPlaybackStartsOrStops() {
        val boards = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2"),
            createFakeBoard("b3", "Board 3")
        )
        val adapter = MainPagerAdapter(boards)

        // Assert initial positions (Schedule board is at index 1, so boards start at index 2)
        assertEquals(2, adapter.getBoardPagePosition("b1"))
        assertEquals(3, adapter.getBoardPagePosition("b2"))
        assertEquals(4, adapter.getBoardPagePosition("b3"))

        // Assert positions while playing
        adapter.updateMusicState(MusicState(isPlaying = true, title = "晴天", artist = "周杰伦"))
        assertEquals(2, adapter.getBoardPagePosition("b1"))
        assertEquals(3, adapter.getBoardPagePosition("b2"))
        assertEquals(4, adapter.getBoardPagePosition("b3"))

        // Assert positions while paused
        adapter.updateMusicState(MusicState(isPlaying = false, title = "晴天", artist = "周杰伦"))
        assertEquals(2, adapter.getBoardPagePosition("b1"))
        assertEquals(3, adapter.getBoardPagePosition("b2"))
        assertEquals(4, adapter.getBoardPagePosition("b3"))

        // Assert positions after stopping
        adapter.updateMusicState(MusicState(isPlaying = false))
        assertEquals(2, adapter.getBoardPagePosition("b1"))
        assertEquals(3, adapter.getBoardPagePosition("b2"))
        assertEquals(4, adapter.getBoardPagePosition("b3"))
    }

    @Test
    fun testStableIds_remainConsistentAcrossPlaybackChanges() {
        val boards = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2")
        )
        val adapter = MainPagerAdapter(boards)

        val homeIdInitial = adapter.getItemId(0)
        val scheduleIdInitial = adapter.getItemId(1)
        val b1IdInitial = adapter.getItemId(2)
        val b2IdInitial = adapter.getItemId(3)

        assertEquals(0L, homeIdInitial)
        assertEquals(1L, scheduleIdInitial)
        assertNotEquals(RecyclerView.NO_ID, b1IdInitial)
        assertNotEquals(RecyclerView.NO_ID, b2IdInitial)
        assertNotEquals(b1IdInitial, b2IdInitial)

        // Switch to playing
        adapter.updateMusicState(MusicState(isPlaying = true, title = "花海"))
        assertEquals(homeIdInitial, adapter.getItemId(0))
        assertEquals(scheduleIdInitial, adapter.getItemId(1))
        assertEquals(b1IdInitial, adapter.getItemId(2))
        assertEquals(b2IdInitial, adapter.getItemId(3))

        // Switch to stopped
        adapter.updateMusicState(MusicState(isPlaying = false))
        assertEquals(homeIdInitial, adapter.getItemId(0))
        assertEquals(scheduleIdInitial, adapter.getItemId(1))
        assertEquals(b1IdInitial, adapter.getItemId(2))
        assertEquals(b2IdInitial, adapter.getItemId(3))
    }

    @Test
    fun testGetBoardPagePosition_nonExistentBoard_returnsMinusOne() {
        val boards = listOf(createFakeBoard("b1"))
        val adapter = MainPagerAdapter(boards)
        assertEquals(-1, adapter.getBoardPagePosition("non-existent"))
    }

    @Test
    fun testGetItemId_outOfBounds_returnsNoId() {
        val boards = listOf(createFakeBoard("b1"))
        val adapter = MainPagerAdapter(boards)
        assertEquals(RecyclerView.NO_ID, adapter.getItemId(10))
        assertEquals(RecyclerView.NO_ID, adapter.getItemId(-1))
    }

    @Test
    fun testEmptyBoards_homeAndSchedulePages_countDoesNotChangeWithPlayback() {
        val adapter = MainPagerAdapter(emptyList())
        assertEquals(2, adapter.itemCount)
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))
        assertEquals(MainPagerAdapter.VIEW_TYPE_SCHEDULE, adapter.getItemViewType(1))
        assertEquals(1L, adapter.getItemId(1))

        adapter.updateMusicState(MusicState(isPlaying = true))
        assertEquals(2, adapter.itemCount)

        adapter.updateMusicState(MusicState(isPlaying = false))
        assertEquals(2, adapter.itemCount)
    }

    @Test
    fun testOnConfigurationChanged_preservesBoardListAndMusicState() {
        val boards = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2"),
            createFakeBoard("b3", "Board 3")
        )
        val adapter = MainPagerAdapter(boards)

        val playingState = MusicState(
            isPlaying = true,
            title = "七里香",
            artist = "周杰伦",
            appName = "网易云音乐",
            positionMs = 45000L,
            durationMs = 299000L
        )
        adapter.updateMusicState(playingState)

        val initialItemCount = adapter.itemCount
        val initialHomeId = adapter.getItemId(0)
        val initialScheduleId = adapter.getItemId(1)
        val initialB1Id = adapter.getItemId(2)
        val initialB2Id = adapter.getItemId(3)
        val initialB3Id = adapter.getItemId(4)

        // Simulate configuration change to landscape
        val landscapeConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        adapter.onConfigurationChanged(landscapeConfig)

        // Assert board list and page counts are preserved
        assertEquals("Page count must be preserved across orientation change", initialItemCount, adapter.itemCount)
        assertEquals(2, adapter.getBoardPagePosition("b1"))
        assertEquals(3, adapter.getBoardPagePosition("b2"))
        assertEquals(4, adapter.getBoardPagePosition("b3"))

        // Assert stable IDs are invariant
        assertEquals(initialHomeId, adapter.getItemId(0))
        assertEquals(initialScheduleId, adapter.getItemId(1))
        assertEquals(initialB1Id, adapter.getItemId(2))
        assertEquals(initialB2Id, adapter.getItemId(3))
        assertEquals(initialB3Id, adapter.getItemId(4))

        // Assert music state is preserved
        assertEquals(playingState, adapter.getMusicState())

        // Simulate configuration change back to portrait
        val portraitConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        adapter.onConfigurationChanged(portraitConfig)

        // Assert state remains completely preserved
        assertEquals("Page count must be preserved after returning to portrait", initialItemCount, adapter.itemCount)
        assertEquals(2, adapter.getBoardPagePosition("b1"))
        assertEquals(3, adapter.getBoardPagePosition("b2"))
        assertEquals(4, adapter.getBoardPagePosition("b3"))
        assertEquals(initialHomeId, adapter.getItemId(0))
        assertEquals(initialScheduleId, adapter.getItemId(1))
        assertEquals(initialB1Id, adapter.getItemId(2))
        assertEquals(initialB2Id, adapter.getItemId(3))
        assertEquals(initialB3Id, adapter.getItemId(4))
        assertEquals(playingState, adapter.getMusicState())
    }

    @Test
    fun testOnConfigurationChanged_repeatedRotations_preservesStateAndPageCount() {
        val boards = listOf(
            createFakeBoard("alpha", "Alpha"),
            createFakeBoard("beta", "Beta")
        )
        val adapter = MainPagerAdapter(boards)
        val musicState = MusicState(isPlaying = true, title = "青花瓷", artist = "周杰伦")
        adapter.updateMusicState(musicState)

        val landConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        val portConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }

        // Rotate 10 times in succession
        for (i in 0 until 10) {
            val config = if (i % 2 == 0) landConfig else portConfig
            adapter.onConfigurationChanged(config)

            assertEquals(4, adapter.itemCount)
            assertEquals(2, adapter.getBoardPagePosition("alpha"))
            assertEquals(3, adapter.getBoardPagePosition("beta"))
            assertEquals(musicState, adapter.getMusicState())
        }
    }

    @Test
    fun testOnConfigurationChanged_whenNoBoards_preservesHomeAndSchedulePages() {
        val adapter = MainPagerAdapter(emptyList())
        assertEquals(2, adapter.itemCount)

        val landConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        adapter.onConfigurationChanged(landConfig)
        assertEquals(2, adapter.itemCount)
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))
        assertEquals(MainPagerAdapter.VIEW_TYPE_SCHEDULE, adapter.getItemViewType(1))
        assertEquals(1L, adapter.getItemId(1))

        val portConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        adapter.onConfigurationChanged(portConfig)
        assertEquals(2, adapter.itemCount)
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))
        assertEquals(MainPagerAdapter.VIEW_TYPE_SCHEDULE, adapter.getItemViewType(1))
        assertEquals(1L, adapter.getItemId(1))
    }
}
