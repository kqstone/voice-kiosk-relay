package com.voicekiosk.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmSchedulerLogicTest {

    @Test
    fun testBuildAlarmIntentDescriptor_containsExpectedExtrasAndFlags() {
        val item = AlarmItem(
            id = "sch_test123456",
            type = AlarmItem.TYPE_ALARM,
            title = "Morning Alarm",
            targetTimeUtc = 1731884400.0,
            status = AlarmItem.STATUS_ACTIVE
        )

        val descriptor = AlarmScheduler.buildAlarmIntentDescriptor(item, isMarshmallowOrHigher = true)

        assertEquals("com.voicekiosk.app.alarm.AlarmBroadcastReceiver", descriptor.targetClass)
        assertEquals(AlarmScheduler.ACTION_ALARM_FIRE, descriptor.action)
        assertEquals("sch_test123456", descriptor.scheduleId)
        assertEquals("sch_test123456_1731884400.0", descriptor.antiDuplicateToken)
        assertEquals("Morning Alarm", descriptor.title)
        assertEquals(1731884400.0, descriptor.targetTimeUtc, 0.001)
        assertEquals(item.id.hashCode(), descriptor.requestCode)
        // Verify PendingIntent flags include UPDATE_CURRENT and IMMUTABLE (0x04000000)
        val flagImmutable = 0x04000000
        val flagUpdateCurrent = 0x08000000
        assertEquals(flagUpdateCurrent or flagImmutable, descriptor.flags)
    }

    @Test
    fun testComputeSnooze_defaultFiveMinutes() {
        val nowSeconds = 1000.0
        val item = AlarmItem(
            id = "sch_snooze001",
            title = "Wake up",
            targetTimeUtc = 1000.0,
            status = AlarmItem.STATUS_RINGING,
            metadata = emptyMap()
        )

        val snoozed = AlarmScheduler.computeSnooze(item, nowSeconds = nowSeconds)

        assertEquals(1300.0, snoozed.targetTimeUtc, 0.001) // 1000 + 300s (5m)
        assertEquals(AlarmItem.STATUS_SNOOZED, snoozed.status)
        assertEquals(nowSeconds, snoozed.updatedAtUtc, 0.001)
        assertEquals(1, snoozed.metadata["snooze_count"])
        assertEquals(300, snoozed.metadata["snooze_interval_seconds"])
        assertEquals("sch_snooze001_1300.0", snoozed.antiDuplicateToken)
    }

    @Test
    fun testComputeSnooze_customTenMinutesAndIncrementCount() {
        val nowSeconds = 2000.0
        val item = AlarmItem(
            id = "sch_snooze002",
            title = "Cooking Timer",
            targetTimeUtc = 2000.0,
            status = AlarmItem.STATUS_RINGING,
            metadata = mapOf("snooze_count" to 2)
        )

        val snoozed = AlarmScheduler.computeSnooze(item, snoozeSeconds = 600, nowSeconds = nowSeconds)

        assertEquals(2600.0, snoozed.targetTimeUtc, 0.001) // 2000 + 600s (10m)
        assertEquals(AlarmItem.STATUS_SNOOZED, snoozed.status)
        assertEquals(3, snoozed.metadata["snooze_count"])
        assertEquals(600, snoozed.metadata["snooze_interval_seconds"])
    }

    @Test
    fun testComputeDismiss_marksTerminalAndRecordsCompletion() {
        val nowSeconds = 1500.0
        val item = AlarmItem(
            id = "sch_dismiss001",
            title = "Call Doctor",
            targetTimeUtc = 1400.0,
            status = AlarmItem.STATUS_RINGING
        )

        val dismissed = AlarmScheduler.computeDismiss(item, nowSeconds = nowSeconds)

        assertEquals(AlarmItem.STATUS_DISMISSED, dismissed.status)
        assertTrue(dismissed.isTerminal)
        assertEquals(nowSeconds, dismissed.updatedAtUtc, 0.001)
        assertEquals(nowSeconds, dismissed.metadata["completed_at_utc"])
    }

    @Test
    fun testSelectActiveAlarmsToReschedule_onlySelectsFutureActiveAlarms() {
        val nowMillis = 100_000L

        val futureActive = AlarmItem(id = "1", title = "A", targetTimeUtc = 150.0, status = AlarmItem.STATUS_ACTIVE)
        val pastActive = AlarmItem(id = "2", title = "B", targetTimeUtc = 50.0, status = AlarmItem.STATUS_ACTIVE)
        val futureDismissed = AlarmItem(id = "3", title = "C", targetTimeUtc = 180.0, status = AlarmItem.STATUS_DISMISSED)
        val futureCancelled = AlarmItem(id = "4", title = "D", targetTimeUtc = 200.0, status = AlarmItem.STATUS_CANCELLED)
        val farFutureActive = AlarmItem(id = "5", title = "E", targetTimeUtc = 300.0, status = AlarmItem.STATUS_ACTIVE)

        val items = listOf(futureActive, pastActive, futureDismissed, futureCancelled, farFutureActive)
        val rescheduled = AlarmScheduler.selectActiveAlarmsToReschedule(items, nowMillis)

        assertEquals(2, rescheduled.size)
        assertEquals(listOf("1", "5"), rescheduled.map { it.id })
    }

    @Test
    fun testIsDuplicateOrStale_validActiveMatchesToken() {
        val item = AlarmItem(
            id = "sch_valid",
            title = "Test",
            targetTimeUtc = 5000.0,
            status = AlarmItem.STATUS_ACTIVE
        )

        // Valid token matches
        assertFalse(AlarmScheduler.isDuplicateOrStale(item, "sch_valid_5000.0"))
    }

    @Test
    fun testIsDuplicateOrStale_rejectsNullOrTerminalOrMismatched() {
        val item = AlarmItem(
            id = "sch_test",
            title = "Test",
            targetTimeUtc = 5000.0,
            status = AlarmItem.STATUS_ACTIVE
        )

        // Null stored item rejected
        assertTrue(AlarmScheduler.isDuplicateOrStale(null, "sch_test_5000.0"))

        // Null or empty received token rejected
        assertTrue(AlarmScheduler.isDuplicateOrStale(item, null))
        assertTrue(AlarmScheduler.isDuplicateOrStale(item, ""))

        // Token mismatch (e.g. from prior time slot before snooze) rejected
        assertTrue(AlarmScheduler.isDuplicateOrStale(item, "sch_test_4500.0"))

        // Dismissed / cancelled / completed rejected
        val dismissedItem = item.copy(status = AlarmItem.STATUS_DISMISSED)
        assertTrue(AlarmScheduler.isDuplicateOrStale(dismissedItem, "sch_test_5000.0"))

        val cancelledItem = item.copy(status = AlarmItem.STATUS_CANCELLED)
        assertTrue(AlarmScheduler.isDuplicateOrStale(cancelledItem, "sch_test_5000.0"))

        // Already ringing item rejected (avoids double triggering)
        val ringingItem = item.copy(status = AlarmItem.STATUS_RINGING)
        assertTrue(AlarmScheduler.isDuplicateOrStale(ringingItem, "sch_test_5000.0"))
    }
}
