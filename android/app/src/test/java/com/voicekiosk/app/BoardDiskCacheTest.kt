package com.voicekiosk.app

import com.voicekiosk.app.data.BoardComponent
import com.voicekiosk.app.data.BoardDiskCache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BoardDiskCacheTest {

    private lateinit var tempDir: File
    private lateinit var diskCache: BoardDiskCache

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("test_boards_cache").toFile()
        diskCache = BoardDiskCache(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testSaveAndLoadBoard() {
        val boardJson = """
            {
                "board_id": "test_board",
                "source": "muse",
                "data": {
                    "title": "Test Title",
                    "tasks": [
                        {"id": "1", "text": "Do task 1", "completed": false}
                    ]
                }
            }
        """.trimIndent()

        diskCache.saveBoard("test_board", boardJson)
        val loaded = diskCache.loadBoard("test_board")

        assertNotNull(loaded)
        assertEquals("test_board", loaded?.boardId)
        assertEquals("Test Title", loaded?.titleComponent?.title)
        assertEquals(1, loaded?.components?.size)
        val checklist = loaded?.components?.get(0) as BoardComponent.Checklist
        assertEquals("Do task 1", checklist.items[0].text)
    }

    @Test
    fun testManifestPersistenceAndLoadAll() {
        val b1 = """{"board_id": "b1", "data": {"title": "Board 1"}}"""
        val b2 = """{"board_id": "b2", "data": {"title": "Board 2"}}"""

        diskCache.saveBoard("b1", b1)
        diskCache.saveBoard("b2", b2)
        diskCache.saveManifest(listOf("b1", "b2"))

        val manifest = diskCache.loadManifest()
        assertEquals(listOf("b1", "b2"), manifest)

        val all = diskCache.loadAllBoards()
        assertEquals(2, all.size)
        assertEquals("b1", all[0].boardId)
        assertEquals("b2", all[1].boardId)
    }

    @Test
    fun testDeleteBoard() {
        val b1 = """{"board_id": "b1", "data": {"title": "Board 1"}}"""
        diskCache.saveBoard("b1", b1)
        assertNotNull(diskCache.loadBoard("b1"))

        diskCache.deleteBoard("b1")
        assertNull(diskCache.loadBoard("b1"))
    }
}
