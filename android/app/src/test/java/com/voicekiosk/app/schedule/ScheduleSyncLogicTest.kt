package com.voicekiosk.app.schedule

import com.voicekiosk.app.alarm.AlarmItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unit tests for schedule sync logic, cursor handling, full resync triggers,
 * candidate payload shape, terminal state domination, and today-plus-tomorrow filtering.
 */
class ScheduleSyncLogicTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun testCursorComparison_monotonicOrdering() {
        // Empty cursor is smaller than any non-empty cursor
        assertTrue(ScheduleSyncLogic.compareCursors("", "1000.0_sch_01") < 0)
        assertTrue(ScheduleSyncLogic.compareCursors("1000.0_sch_01", "") > 0)
        assertEquals(0, ScheduleSyncLogic.compareCursors("", ""))

        // Identical cursors
        assertEquals(0, ScheduleSyncLogic.compareCursors("1000.0_sch_01", "1000.0_sch_01"))

        // Earlier timestamp is smaller
        assertTrue(ScheduleSyncLogic.compareCursors("1000.0_sch_01", "2000.0_sch_01") < 0)
        assertTrue(ScheduleSyncLogic.compareCursors("2000.0_sch_01", "1000.0_sch_01") > 0)

        // Equal timestamp ordered by ID
        assertTrue(ScheduleSyncLogic.compareCursors("1000.0_sch_01", "1000.0_sch_02") < 0)
        assertTrue(ScheduleSyncLogic.compareCursors("1000.0_sch_02", "1000.0_sch_01") > 0)
    }

    @Test
    fun testFullResyncTrigger_onResetFlagOrStaleCursor() {
        // Reset flag true always triggers full resync
        assertTrue(ScheduleSyncLogic.shouldPerformFullResync(resetFlag = true))
        assertTrue(ScheduleSyncLogic.shouldPerformFullResync(resetFlag = true, httpStatusCode = 200, currentCursor = "123_sch_01"))

        // Reset flag false and 200 does not trigger full resync
        assertFalse(ScheduleSyncLogic.shouldPerformFullResync(resetFlag = false, httpStatusCode = 200, currentCursor = "123_sch_01"))

        // HTTP 400 (stale or unparseable cursor) triggers full resync
        assertTrue(ScheduleSyncLogic.shouldPerformFullResync(resetFlag = false, httpStatusCode = 400, currentCursor = "stale_cursor"))

        // HTTP 404 with cursor triggers full resync
        assertTrue(ScheduleSyncLogic.shouldPerformFullResync(resetFlag = false, httpStatusCode = 404, currentCursor = "some_cursor"))
    }

    @Test
    fun testCandidatePayloadShape_matchesPhase1Contract() {
        val item1 = ScheduleItem(
            id = "sch_cand_01",
            type = ScheduleItem.TYPE_SCHEDULE,
            title = "Review meeting notes",
            targetTimeUtc = 1731884400.0,
            durationSeconds = 0,
            recurrence = "",
            status = ScheduleItem.STATUS_ACTIVE,
            origin = ScheduleItem.ORIGIN_APK_UI,
            metadata = mapOf("label" to "work")
        )
        val item2 = ScheduleItem(
            id = "sch_cand_02",
            type = ScheduleItem.TYPE_ALARM,
            title = "Wake up",
            targetTimeUtc = 1731890000.0,
            durationSeconds = 0,
            recurrence = "FREQ=DAILY",
            status = ScheduleItem.STATUS_ACTIVE,
            origin = ScheduleItem.ORIGIN_APK_VOICE
        )

        val payload = ScheduleSyncLogic.buildCandidatePayload(listOf(item1, item2))

        // Contract: root must have key "candidates" and MUST NOT have "items" or "snapshot"
        assertTrue("Payload must contain 'candidates' key", payload.has("candidates"))
        assertFalse("Payload must NOT contain 'items' key (that's snapshot format)", payload.has("items"))
        assertFalse("Payload must NOT contain 'snapshot' key", payload.has("snapshot"))

        val arr = payload.getJSONArray("candidates")
        assertEquals(2, arr.length())

        val obj1 = arr.getJSONObject(0)
        assertEquals("sch_cand_01", obj1.getString("id"))
        assertEquals("schedule", obj1.getString("type"))
        assertEquals("Review meeting notes", obj1.getString("title"))
        assertEquals(1731884400.0, obj1.getDouble("target_time_utc"), 0.001)
        assertEquals("active", obj1.getString("status"))
        assertEquals("work", obj1.getJSONObject("metadata").getString("label"))

        val obj2 = arr.getJSONObject(1)
        assertEquals("sch_cand_02", obj2.getString("id"))
        assertEquals("alarm", obj2.getString("type"))
        assertEquals("FREQ=DAILY", obj2.getString("recurrence"))
    }

    @Test
    fun testTerminalStateDomination_activeCannotOverwriteCompleted() {
        val localCompleted = ScheduleItem(
            id = "sch_term_01",
            title = "Medicine Reminder",
            targetTimeUtc = 1731884400.0,
            status = ScheduleItem.STATUS_COMPLETED,
            updatedAtUtc = 1000.0,
            metadata = mapOf("completed_at_utc" to 1000.0)
        )

        val incomingActive = ScheduleItem(
            id = "sch_term_01",
            title = "Medicine Reminder Updated",
            targetTimeUtc = 1731884400.0,
            status = ScheduleItem.STATUS_ACTIVE,
            updatedAtUtc = 1100.0
        )

        val merged = ScheduleSyncLogic.mergeSnapshot(
            localItems = listOf(localCompleted),
            incomingItems = listOf(incomingActive),
            deletedIds = emptyList(),
            reset = false
        )

        assertEquals(1, merged.size)
        val result = merged.first()
        // Status MUST stay completed (Terminal State Domination)
        assertEquals(ScheduleItem.STATUS_COMPLETED, result.status)
        // Newer title can be accepted
        assertEquals("Medicine Reminder Updated", result.title)
    }

    @Test
    fun testSnapshotMerge_appliesDeletionsAndLastWriteWins() {
        val itemA = ScheduleItem(
            id = "sch_a",
            title = "Task A",
            targetTimeUtc = 1731880000.0,
            status = ScheduleItem.STATUS_ACTIVE,
            updatedAtUtc = 100.0
        )
        val itemB = ScheduleItem(
            id = "sch_b",
            title = "Task B",
            targetTimeUtc = 1731885000.0,
            status = ScheduleItem.STATUS_ACTIVE,
            updatedAtUtc = 100.0
        )

        // Incoming update for itemA with newer timestamp, and delete for itemB
        val incomingA = itemA.copy(title = "Task A New", updatedAtUtc = 200.0)
        val itemC = ScheduleItem(
            id = "sch_c",
            title = "Task C",
            targetTimeUtc = 1731890000.0,
            status = ScheduleItem.STATUS_ACTIVE,
            updatedAtUtc = 200.0
        )

        val merged = ScheduleSyncLogic.mergeSnapshot(
            localItems = listOf(itemA, itemB),
            incomingItems = listOf(incomingA, itemC),
            deletedIds = listOf("sch_b"),
            reset = false
        )

        val ids = merged.map { it.id }
        assertTrue(ids.contains("sch_a"))
        assertFalse(ids.contains("sch_b")) // Deleted
        assertTrue(ids.contains("sch_c")) // Added

        assertEquals("Task A New", merged.first { it.id == "sch_a" }.title)
    }

    @Test
    fun testSnapshotMerge_resetReplacesAll() {
        val itemA = ScheduleItem(id = "sch_old_1", title = "Old 1", targetTimeUtc = 10.0)
        val itemB = ScheduleItem(id = "sch_old_2", title = "Old 2", targetTimeUtc = 20.0)

        val newItems = listOf(
            ScheduleItem(id = "sch_new_1", title = "New 1", targetTimeUtc = 30.0)
        )

        val merged = ScheduleSyncLogic.mergeSnapshot(
            localItems = listOf(itemA, itemB),
            incomingItems = newItems,
            deletedIds = emptyList(),
            reset = true
        )

        assertEquals(1, merged.size)
        assertEquals("sch_new_1", merged.first().id)
    }

    @Test
    fun testFilterTodayAndTomorrow_retainsOnlyTodayAndTomorrow() {
        val now = ZonedDateTime.of(2026, 11, 18, 14, 0, 0, 0, zone)
        val nowMillis = now.toInstant().toEpochMilli()

        // Yesterday 20:00
        val yesterdayTs = now.minusDays(1).withHour(20).toInstant().toEpochMilli() / 1000.0
        // Today 08:00
        val todayMorningTs = now.withHour(8).toInstant().toEpochMilli() / 1000.0
        // Today 18:00
        val todayEveningTs = now.withHour(18).toInstant().toEpochMilli() / 1000.0
        // Tomorrow 09:00
        val tomorrowTs = now.plusDays(1).withHour(9).toInstant().toEpochMilli() / 1000.0
        // Tomorrow 23:59
        val tomorrowLateTs = now.plusDays(1).withHour(23).withMinute(59).toInstant().toEpochMilli() / 1000.0
        // Day after tomorrow 01:00
        val dayAfterTomorrowTs = now.plusDays(2).withHour(1).toInstant().toEpochMilli() / 1000.0

        val items = listOf(
            ScheduleItem(id = "sch_yesterday", title = "Yesterday", targetTimeUtc = yesterdayTs),
            ScheduleItem(id = "sch_today_am", title = "Today AM", targetTimeUtc = todayMorningTs),
            ScheduleItem(id = "sch_today_pm", title = "Today PM", targetTimeUtc = todayEveningTs),
            ScheduleItem(id = "sch_tomorrow", title = "Tomorrow", targetTimeUtc = tomorrowTs),
            ScheduleItem(id = "sch_tomorrow_late", title = "Tomorrow Late", targetTimeUtc = tomorrowLateTs),
            ScheduleItem(id = "sch_after_tomorrow", title = "After Tomorrow", targetTimeUtc = dayAfterTomorrowTs)
        )

        val filtered = ScheduleSyncLogic.filterTodayAndTomorrow(items, nowMillis, zone)
        val filteredIds = filtered.map { it.id }

        assertFalse("Yesterday must be excluded", filteredIds.contains("sch_yesterday"))
        assertTrue("Today AM must be included", filteredIds.contains("sch_today_am"))
        assertTrue("Today PM must be included", filteredIds.contains("sch_today_pm"))
        assertTrue("Tomorrow must be included", filteredIds.contains("sch_tomorrow"))
        assertTrue("Tomorrow late must be included", filteredIds.contains("sch_tomorrow_late"))
        assertFalse("Day after tomorrow must be excluded", filteredIds.contains("sch_after_tomorrow"))

        assertEquals(4, filtered.size)
    }

    @Test
    fun testToggleItemComplete_updatesStatusAndCompletedAt() {
        val item = ScheduleItem(
            id = "sch_toggle_01",
            title = "Walk Dog",
            targetTimeUtc = 1731884400.0,
            status = ScheduleItem.STATUS_ACTIVE,
            metadata = emptyMap()
        )

        val toggled = ScheduleSyncLogic.toggleItemComplete(item, nowSeconds = 1731885000.0)

        assertEquals(ScheduleItem.STATUS_COMPLETED, toggled.status)
        assertTrue(toggled.isCompleted)
        assertEquals(1731885000.0, toggled.updatedAtUtc, 0.001)
        assertEquals(1731885000.0, (toggled.metadata["completed_at_utc"] as Number).toDouble(), 0.001)
    }

    @Test
    fun testAlarmItemToScheduleItemMapping_preservesFields() {
        val alarm = AlarmItem(
            id = "sch_alarm_99",
            type = AlarmItem.TYPE_ALARM,
            title = "Wake up alarm",
            targetTimeUtc = 1731884400.0,
            durationSeconds = 0,
            recurrence = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
            status = AlarmItem.STATUS_ACTIVE,
            origin = AlarmItem.ORIGIN_APK_VOICE,
            metadata = mapOf("label" to "weekday")
        )

        val scheduleItem = ScheduleItem.fromAlarmItem(alarm)
        assertEquals(alarm.id, scheduleItem.id)
        assertEquals(alarm.type, scheduleItem.type)
        assertEquals(alarm.title, scheduleItem.title)
        assertEquals(alarm.targetTimeUtc, scheduleItem.targetTimeUtc, 0.001)
        assertEquals(alarm.recurrence, scheduleItem.recurrence)
        assertEquals(alarm.status, scheduleItem.status)
        assertEquals(alarm.origin, scheduleItem.origin)
        assertEquals("weekday", scheduleItem.metadata["label"])

        val roundTripAlarm = scheduleItem.toAlarmItem()
        assertEquals(alarm.id, roundTripAlarm.id)
        assertEquals(alarm.status, roundTripAlarm.status)
    }

    @Test
    fun testReconciliation_localAlarmDeletedOnBridge_cancelsAndDeletesLocal() {
        val local = AlarmItem(
            id = "sch_alarm_del_1",
            title = "Morning alarm",
            targetTimeUtc = 1731884400.0,
            status = AlarmItem.STATUS_ACTIVE
        )

        val plan = ScheduleSyncLogic.reconcileLocalAlarmsWithBridge(
            localAlarms = listOf(local),
            bridgeItems = emptyList(),
            deletedBridgeIds = listOf("sch_alarm_del_1")
        )

        assertEquals(1, plan.conflicts.size)
        assertEquals("sch_alarm_del_1", plan.conflicts[0].id)
        assertTrue(plan.conflicts[0].reason.contains("deleted on bridge"))
        assertEquals(listOf("sch_alarm_del_1"), plan.alarmsToCancel)
        assertEquals(listOf("sch_alarm_del_1"), plan.alarmsToDelete)
        assertTrue(plan.alarmsToSave.isEmpty())
    }

    @Test
    fun testReconciliation_statusDisagreement_prefersBridgeState() {
        // Local is active, bridge is cancelled
        val local = AlarmItem(
            id = "sch_alarm_stat_1",
            title = "Meeting Reminder",
            targetTimeUtc = 1731884400.0,
            status = AlarmItem.STATUS_ACTIVE
        )
        val bridge = ScheduleItem(
            id = "sch_alarm_stat_1",
            type = ScheduleItem.TYPE_ALARM,
            title = "Meeting Reminder",
            targetTimeUtc = 1731884400.0,
            status = ScheduleItem.STATUS_CANCELLED,
            origin = ScheduleItem.ORIGIN_MUSE_VOICE
        )

        val plan = ScheduleSyncLogic.reconcileLocalAlarmsWithBridge(
            localAlarms = listOf(local),
            bridgeItems = listOf(bridge),
            deletedBridgeIds = emptyList()
        )

        assertEquals(1, plan.conflicts.size)
        assertTrue(plan.conflicts[0].reason.contains("preferring bridge state"))
        assertEquals(listOf("sch_alarm_stat_1"), plan.alarmsToCancel)
        assertEquals(1, plan.alarmsToSave.size)
        assertEquals(AlarmItem.STATUS_CANCELLED, plan.alarmsToSave[0].status)
    }

    @Test
    fun testReconciliation_targetTimeDisagreement_prefersBridgeState() {
        val local = AlarmItem(
            id = "sch_alarm_time_1",
            title = "Gym Alarm",
            targetTimeUtc = 1731884400.0,
            status = AlarmItem.STATUS_ACTIVE
        )
        val bridge = ScheduleItem(
            id = "sch_alarm_time_1",
            type = ScheduleItem.TYPE_ALARM,
            title = "Gym Alarm",
            targetTimeUtc = 1731888000.0, // 1 hour later
            status = ScheduleItem.STATUS_ACTIVE,
            origin = ScheduleItem.ORIGIN_MUSE_VOICE
        )

        val plan = ScheduleSyncLogic.reconcileLocalAlarmsWithBridge(
            localAlarms = listOf(local),
            bridgeItems = listOf(bridge),
            deletedBridgeIds = emptyList()
        )

        assertEquals(1, plan.conflicts.size)
        assertTrue(plan.conflicts[0].reason.contains("target local="))
        assertEquals(1, plan.alarmsToSave.size)
        assertEquals(1731888000.0, plan.alarmsToSave[0].targetTimeUtc, 0.001)
    }

    @Test
    fun testReconciliation_newBridgeAlarm_discoversAndSaves() {
        val bridgeNew = ScheduleItem(
            id = "sch_bridge_new_1",
            type = ScheduleItem.TYPE_ALARM,
            title = "Muse scheduled alarm",
            targetTimeUtc = 1731900000.0,
            status = ScheduleItem.STATUS_ACTIVE,
            origin = ScheduleItem.ORIGIN_MUSE_VOICE
        )

        val plan = ScheduleSyncLogic.reconcileLocalAlarmsWithBridge(
            localAlarms = emptyList(),
            bridgeItems = listOf(bridgeNew),
            deletedBridgeIds = emptyList()
        )

        assertEquals(0, plan.conflicts.size)
        assertEquals(1, plan.alarmsToSave.size)
        assertEquals("sch_bridge_new_1", plan.alarmsToSave[0].id)
        assertEquals(AlarmItem.STATUS_ACTIVE, plan.alarmsToSave[0].status)
    }
}
