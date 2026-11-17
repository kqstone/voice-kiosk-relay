package com.voicekiosk.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalAlarmStoreTest {

    @Test
    fun testSaveAndRetrieveAlarms() {
        val file = File.createTempFile("alarm_store_test", ".bin").apply {
            delete()
            deleteOnExit()
        }
        val store = LocalAlarmStore(file, PlainByteCodec)

        val item1 = AlarmItem(
            id = "sch_001",
            title = "Morning Coffee",
            targetTimeUtc = 1731884400.0,
            status = AlarmItem.STATUS_ACTIVE
        )
        val item2 = AlarmItem(
            id = "sch_002",
            title = "Evening Run",
            targetTimeUtc = 1731920000.0,
            status = AlarmItem.STATUS_ACTIVE
        )

        store.saveAlarm(item1)
        store.saveAlarm(item2)

        val all = store.getAllAlarms()
        assertEquals(2, all.size)
        assertEquals(item1, store.getAlarm("sch_001"))
        assertEquals(item2, store.getAlarm("sch_002"))

        // Update status
        store.updateStatus("sch_001", AlarmItem.STATUS_RINGING)
        assertEquals(AlarmItem.STATUS_RINGING, store.getAlarm("sch_001")?.status)

        // Delete item
        assertTrue(store.deleteAlarm("sch_002"))
        assertNull(store.getAlarm("sch_002"))
        assertEquals(1, store.getAllAlarms().size)
    }

    @Test
    fun testPersistenceAcrossStoreInstances() {
        val file = File.createTempFile("alarm_persist_test", ".bin").apply {
            delete()
            deleteOnExit()
        }

        val storeA = LocalAlarmStore(file, PlainByteCodec)
        val alarm = AlarmItem(
            id = "sch_persist",
            title = "Medicine Reminder",
            targetTimeUtc = 1731890000.0,
            status = AlarmItem.STATUS_ACTIVE,
            metadata = mapOf("label" to "daily")
        )
        storeA.saveAlarm(alarm)

        // New instance loading from the same file
        val storeB = LocalAlarmStore(file, PlainByteCodec)
        val loaded = storeB.getAlarm("sch_persist")

        assertNotNull(loaded)
        assertEquals(alarm.id, loaded!!.id)
        assertEquals(alarm.title, loaded.title)
        assertEquals(alarm.targetTimeUtc, loaded.targetTimeUtc, 0.001)
        assertEquals(alarm.status, loaded.status)
        assertEquals("daily", loaded.metadata["label"])
    }

    @Test
    fun testCorruptedFileStartsEmptyGracefully() {
        val file = File.createTempFile("alarm_corrupt_test", ".bin").apply {
            writeBytes("Invalid binary or corrupted JSON data".toByteArray())
            deleteOnExit()
        }

        val store = LocalAlarmStore(file, PlainByteCodec)
        assertEquals(0, store.getAllAlarms().size)
    }
}
