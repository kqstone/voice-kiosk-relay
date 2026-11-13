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
 * Requirements (Phase 4):
 * - Page 0 is always the Home screen.
 * - Pages 1..N are always board pages.
 * - Board positions must never shift when playback starts, updates, or stops.
 * - Page count must always equal 1 + boards.size, completely invariant to playback state.
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
    fun testItemCount_alwaysEqualsOnePlusBoardCount() {
        // Zero boards: only Home screen (count = 1)
        val adapterZero = MainPagerAdapter(emptyList())
        assertEquals(1, adapterZero.itemCount)

        // 3 boards: Home screen + 3 boards (count = 4)
        val boards3 = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2"),
            createFakeBoard("b3", "Board 3")
        )
        val adapter3 = MainPagerAdapter(boards3)
        assertEquals(4, adapter3.itemCount)

        // 5 boards: Home screen + 5 boards (count = 6)
        val boards5 = (1..5).map { createFakeBoard("b$it", "Board $it") }
        val adapter5 = MainPagerAdapter(boards5)
        assertEquals(6, adapter5.itemCount)
    }

    @Test
    fun testStableMapping_homeAtZero_boardsAtOneAndUp() {
        val boards = listOf(
            createFakeBoard("alpha", "Alpha"),
            createFakeBoard("beta", "Beta"),
            createFakeBoard("gamma", "Gamma")
        )
        val adapter = MainPagerAdapter(boards)

        // Position 0 is always Home
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))

        // Positions 1..3 are Boards in order
        assertEquals(MainPagerAdapter.VIEW_TYPE_BOARD, adapter.getItemViewType(1))
        assertEquals(MainPagerAdapter.VIEW_TYPE_BOARD, adapter.getItemViewType(2))
        assertEquals(MainPagerAdapter.VIEW_TYPE_BOARD, adapter.getItemViewType(3))

        assertEquals(1, adapter.getBoardPagePosition("alpha"))
        assertEquals(2, adapter.getBoardPagePosition("beta"))
        assertEquals(3, adapter.getBoardPagePosition("gamma"))
    }

    @Test
    fun testPageCount_doesNotChangeWhenPlaybackStartsOrStops() {
        val boards = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2"),
            createFakeBoard("b3", "Board 3")
        )
        val adapter = MainPagerAdapter(boards)

        // 1. Initial state: no playback
        assertEquals(4, adapter.itemCount)

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
            4,
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
            4,
            adapter.itemCount
        )

        // 4. Playback paused
        val pausedState = updatedState.copy(isPlaying = false)
        adapter.updateMusicState(pausedState)
        assertEquals(
            "Page count must not change when music pauses",
            4,
            adapter.itemCount
        )

        // 5. Playback stops / session destroyed
        val stoppedState = MusicState(isPlaying = false)
        adapter.updateMusicState(stoppedState)
        assertEquals(
            "Page count must not change when music stops",
            4,
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

        // Assert initial positions
        assertEquals(1, adapter.getBoardPagePosition("b1"))
        assertEquals(2, adapter.getBoardPagePosition("b2"))
        assertEquals(3, adapter.getBoardPagePosition("b3"))

        // Assert positions while playing
        adapter.updateMusicState(MusicState(isPlaying = true, title = "晴天", artist = "周杰伦"))
        assertEquals(1, adapter.getBoardPagePosition("b1"))
        assertEquals(2, adapter.getBoardPagePosition("b2"))
        assertEquals(3, adapter.getBoardPagePosition("b3"))

        // Assert positions while paused
        adapter.updateMusicState(MusicState(isPlaying = false, title = "晴天", artist = "周杰伦"))
        assertEquals(1, adapter.getBoardPagePosition("b1"))
        assertEquals(2, adapter.getBoardPagePosition("b2"))
        assertEquals(3, adapter.getBoardPagePosition("b3"))

        // Assert positions after stopping
        adapter.updateMusicState(MusicState(isPlaying = false))
        assertEquals(1, adapter.getBoardPagePosition("b1"))
        assertEquals(2, adapter.getBoardPagePosition("b2"))
        assertEquals(3, adapter.getBoardPagePosition("b3"))
    }

    @Test
    fun testStableIds_remainConsistentAcrossPlaybackChanges() {
        val boards = listOf(
            createFakeBoard("b1", "Board 1"),
            createFakeBoard("b2", "Board 2")
        )
        val adapter = MainPagerAdapter(boards)

        val homeIdInitial = adapter.getItemId(0)
        val b1IdInitial = adapter.getItemId(1)
        val b2IdInitial = adapter.getItemId(2)

        assertEquals(0L, homeIdInitial)
        assertNotEquals(RecyclerView.NO_ID, b1IdInitial)
        assertNotEquals(RecyclerView.NO_ID, b2IdInitial)
        assertNotEquals(b1IdInitial, b2IdInitial)

        // Switch to playing
        adapter.updateMusicState(MusicState(isPlaying = true, title = "花海"))
        assertEquals(homeIdInitial, adapter.getItemId(0))
        assertEquals(b1IdInitial, adapter.getItemId(1))
        assertEquals(b2IdInitial, adapter.getItemId(2))

        // Switch to stopped
        adapter.updateMusicState(MusicState(isPlaying = false))
        assertEquals(homeIdInitial, adapter.getItemId(0))
        assertEquals(b1IdInitial, adapter.getItemId(1))
        assertEquals(b2IdInitial, adapter.getItemId(2))
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
        assertEquals(RecyclerView.NO_ID, adapter.getItemId(5))
        assertEquals(RecyclerView.NO_ID, adapter.getItemId(-1))
    }

    @Test
    fun testEmptyBoards_onlyHomePage_countDoesNotChangeWithPlayback() {
        val adapter = MainPagerAdapter(emptyList())
        assertEquals(1, adapter.itemCount)
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))

        adapter.updateMusicState(MusicState(isPlaying = true))
        assertEquals(1, adapter.itemCount)

        adapter.updateMusicState(MusicState(isPlaying = false))
        assertEquals(1, adapter.itemCount)
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
        val initialB1Id = adapter.getItemId(1)
        val initialB2Id = adapter.getItemId(2)
        val initialB3Id = adapter.getItemId(3)

        // Simulate configuration change to landscape
        val landscapeConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        adapter.onConfigurationChanged(landscapeConfig)

        // Assert board list and page counts are preserved
        assertEquals("Page count must be preserved across orientation change", initialItemCount, adapter.itemCount)
        assertEquals(1, adapter.getBoardPagePosition("b1"))
        assertEquals(2, adapter.getBoardPagePosition("b2"))
        assertEquals(3, adapter.getBoardPagePosition("b3"))

        // Assert stable IDs are invariant
        assertEquals(initialHomeId, adapter.getItemId(0))
        assertEquals(initialB1Id, adapter.getItemId(1))
        assertEquals(initialB2Id, adapter.getItemId(2))
        assertEquals(initialB3Id, adapter.getItemId(3))

        // Assert music state is preserved
        assertEquals(playingState, adapter.getMusicState())

        // Simulate configuration change back to portrait
        val portraitConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        adapter.onConfigurationChanged(portraitConfig)

        // Assert state remains completely preserved
        assertEquals("Page count must be preserved after returning to portrait", initialItemCount, adapter.itemCount)
        assertEquals(1, adapter.getBoardPagePosition("b1"))
        assertEquals(2, adapter.getBoardPagePosition("b2"))
        assertEquals(3, adapter.getBoardPagePosition("b3"))
        assertEquals(initialHomeId, adapter.getItemId(0))
        assertEquals(initialB1Id, adapter.getItemId(1))
        assertEquals(initialB2Id, adapter.getItemId(2))
        assertEquals(initialB3Id, adapter.getItemId(3))
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

            assertEquals(3, adapter.itemCount)
            assertEquals(1, adapter.getBoardPagePosition("alpha"))
            assertEquals(2, adapter.getBoardPagePosition("beta"))
            assertEquals(musicState, adapter.getMusicState())
        }
    }

    @Test
    fun testOnConfigurationChanged_whenNoBoards_preservesSingleHomePage() {
        val adapter = MainPagerAdapter(emptyList())
        assertEquals(1, adapter.itemCount)

        val landConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        adapter.onConfigurationChanged(landConfig)
        assertEquals(1, adapter.itemCount)
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))

        val portConfig = Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
        }
        adapter.onConfigurationChanged(portConfig)
        assertEquals(1, adapter.itemCount)
        assertEquals(MainPagerAdapter.VIEW_TYPE_HOME, adapter.getItemViewType(0))
        assertEquals(0L, adapter.getItemId(0))
    }
}
