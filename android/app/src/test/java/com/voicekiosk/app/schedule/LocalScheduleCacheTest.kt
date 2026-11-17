package com.voicekiosk.app.schedule

import com.voicekiosk.app.alarm.PlainByteCodec
import com.voicekiosk.app.memory.ByteCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalScheduleCacheTest {

    private val xorCodec = object : ByteCodec {
        override fun encode(plain: ByteArray): ByteArray =
            ByteArray(plain.size) { (plain[it].toInt() xor 0x3c).toByte() }

        override fun decode(stored: ByteArray): ByteArray = encode(stored)
    }

    @Test
    fun testSaveAndRetrieveItems() {
        val file = File.createTempFile("schedule_cache_test", ".bin").apply {
            delete()
            deleteOnExit()
        }
        val cache = LocalScheduleCache(file, PlainByteCodec)

        val item1 = ScheduleItem(
            id = "sch_item_01",
            title = "Morning Coffee",
            targetTimeUtc = 1731884400.0,
            status = ScheduleItem.STATUS_ACTIVE
        )
        val item2 = ScheduleItem(
            id = "sch_item_02",
            title = "Gym Session",
            targetTimeUtc = 1731890000.0,
            status = ScheduleItem.STATUS_ACTIVE
        )

        cache.saveItem(item1)
        cache.saveItem(item2)

        val all = cache.getAllItems()
        assertEquals(2, all.size)
        assertEquals(item1, cache.getItem("sch_item_01"))
        assertEquals(item2, cache.getItem("sch_item_02"))

        // Update item1
        val item1Updated = item1.copy(title = "Morning Tea")
        cache.saveItem(item1Updated)
        assertEquals("Morning Tea", cache.getItem("sch_item_01")?.title)

        // Delete item2
        assertTrue(cache.deleteItem("sch_item_02"))
        assertNull(cache.getItem("sch_item_02"))
        assertEquals(1, cache.getAllItems().size)
    }

    @Test
    fun testPersistenceAcrossCacheInstances() {
        val file = File.createTempFile("schedule_persist_test", ".bin").apply {
            delete()
            deleteOnExit()
        }

        val cacheA = LocalScheduleCache(file, xorCodec)
        cacheA.setCursor("1731884400.0_sch_01")
        cacheA.saveItem(
            ScheduleItem(
                id = "sch_p1",
                title = "Doctor Appointment",
                targetTimeUtc = 1731884400.0,
                status = ScheduleItem.STATUS_ACTIVE,
                metadata = mapOf("clinic" to "central")
            )
        )
        cacheA.queueCandidate(
            ScheduleItem(
                id = "sch_cand_1",
                title = "Pending Sync",
                targetTimeUtc = 1731885000.0
            )
        )

        // File is encoded, plain text is not visible
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains("Doctor Appointment"))

        // Load into new cache instance
        val cacheB = LocalScheduleCache(file, xorCodec)
        assertEquals("1731884400.0_sch_01", cacheB.getCursor())

        val loaded = cacheB.getItem("sch_p1")
        assertNotNull(loaded)
        assertEquals("Doctor Appointment", loaded!!.title)
        assertEquals("central", loaded.metadata["clinic"])

        val pending = cacheB.getPendingCandidates()
        assertEquals(1, pending.size)
        assertEquals("sch_cand_1", pending[0].id)
    }

    @Test
    fun testApplySnapshot_incrementalAndReset() {
        val cache = LocalScheduleCache(null, PlainByteCodec)

        // Initial snapshot
        val initialItems = listOf(
            ScheduleItem(id = "sch_1", title = "Task 1", targetTimeUtc = 100.0),
            ScheduleItem(id = "sch_2", title = "Task 2", targetTimeUtc = 200.0)
        )
        cache.applySnapshot(
            incoming = initialItems,
            deletedIds = emptyList(),
            newCursor = "100_sch_1",
            reset = false
        )
        assertEquals(2, cache.getAllItems().size)
        assertEquals("100_sch_1", cache.getCursor())

        // Incremental update: update task 1, delete task 2, add task 3
        val incrementalItems = listOf(
            ScheduleItem(id = "sch_1", title = "Task 1 Updated", targetTimeUtc = 100.0, updatedAtUtc = 150.0),
            ScheduleItem(id = "sch_3", title = "Task 3", targetTimeUtc = 300.0, updatedAtUtc = 150.0)
        )
        cache.applySnapshot(
            incoming = incrementalItems,
            deletedIds = listOf("sch_2"),
            newCursor = "150_sch_3",
            reset = false
        )

        val itemsAfterIncremental = cache.getAllItems()
        assertEquals(2, itemsAfterIncremental.size)
        assertEquals("150_sch_3", cache.getCursor())
        assertEquals("Task 1 Updated", cache.getItem("sch_1")?.title)
        assertNull(cache.getItem("sch_2"))
        assertNotNull(cache.getItem("sch_3"))

        // Full reset: replace all with task 4
        cache.applySnapshot(
            incoming = listOf(ScheduleItem(id = "sch_4", title = "Task 4", targetTimeUtc = 400.0)),
            deletedIds = emptyList(),
            newCursor = "400_sch_4",
            reset = true
        )
        assertEquals(1, cache.getAllItems().size)
        assertEquals("Task 4", cache.getAllItems()[0].title)
        assertEquals("400_sch_4", cache.getCursor())
    }

    @Test
    fun testPendingCandidatesQueue_cappedAtMaxQueue() {
        val cache = LocalScheduleCache(null, PlainByteCodec)

        for (i in 0 until LocalScheduleCache.MAX_QUEUE + 10) {
            cache.queueCandidate(
                ScheduleItem(
                    id = "cand_$i",
                    title = "Candidate $i",
                    targetTimeUtc = 1000.0 + i
                )
            )
        }

        val queue = cache.getPendingCandidates()
        assertEquals(LocalScheduleCache.MAX_QUEUE, queue.size)
        assertEquals("cand_10", queue.first().id)
        assertEquals("cand_${LocalScheduleCache.MAX_QUEUE + 9}", queue.last().id)

        // Remove batch
        cache.removePendingCandidates(listOf("cand_10", "cand_11"))
        assertEquals(LocalScheduleCache.MAX_QUEUE - 2, cache.getPendingCandidates().size)
    }

    @Test
    fun testCorruptedFileStartsEmptyGracefully() {
        val file = File.createTempFile("schedule_corrupt_test", ".bin").apply {
            writeBytes("corrupted byte array not valid json".toByteArray())
            deleteOnExit()
        }

        val cache = LocalScheduleCache(file, PlainByteCodec)
        assertEquals(0, cache.getAllItems().size)
        assertEquals("", cache.getCursor())
        assertEquals(0, cache.getPendingCandidates().size)
    }
}
