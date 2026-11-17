package com.voicekiosk.app.schedule

import com.voicekiosk.app.ui.MainPagerAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Contract tests for Schedule page layouts, ViewPager2 view types, and Phase 1 JSON schema parity.
 * Verifies that all layout IDs referenced in code exist in both portrait and landscape XML files.
 */
class ScheduleContractTest {

    private fun locateFile(relativePath: String): File {
        val candidates = listOf(
            File(relativePath),
            File("android/$relativePath"),
            File("app/$relativePath"),
            File("android/app/$relativePath"),
            File("../$relativePath"),
            File("../android/$relativePath")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("Could not find required file: $relativePath", file)
        return file!!
    }

    @Test
    fun testSchedulePageLayouts_viewIdsParity() {
        val portraitFile = locateFile("src/main/res/layout/schedule_page.xml")
        val landscapeFile = locateFile("src/main/res/layout-land/schedule_page.xml")

        val portraitContent = portraitFile.readText(StandardCharsets.UTF_8)
        val landscapeContent = landscapeFile.readText(StandardCharsets.UTF_8)

        val requiredPageIds = listOf(
            "@+id/headerContainer",
            "@+id/viewAccentDot",
            "@+id/tvScheduleTitle",
            "@+id/tvScheduleBadge",
            "@+id/tvScheduleSubtitle",
            "@+id/rvScheduleTimeline",
            "@+id/emptyViewContainer",
            "@+id/tvEmptyTitle",
            "@+id/tvEmptySubtitle"
        )

        for (id in requiredPageIds) {
            assertTrue("Portrait schedule_page.xml must contain $id", portraitContent.contains(id))
            assertTrue("Landscape schedule_page.xml must contain $id", landscapeContent.contains(id))
        }
    }

    @Test
    fun testItemScheduleCardLayouts_viewIdsParity() {
        val portraitFile = locateFile("src/main/res/layout/item_schedule_card.xml")
        val landscapeFile = locateFile("src/main/res/layout-land/item_schedule_card.xml")

        val portraitContent = portraitFile.readText(StandardCharsets.UTF_8)
        val landscapeContent = landscapeFile.readText(StandardCharsets.UTF_8)

        val requiredCardIds = listOf(
            "@+id/cardContainer",
            "@+id/categoryRow",
            "@+id/viewCategoryDot",
            "@+id/tvCategoryLabel",
            "@+id/tvTargetTime",
            "@+id/tvItemTitle",
            "@+id/tvRecurrenceTag",
            "@+id/btnDelete",
            "@+id/checkboxContainer",
            "@+id/ivCheckbox"
        )

        for (id in requiredCardIds) {
            assertTrue("Portrait item_schedule_card.xml must contain $id", portraitContent.contains(id))
            assertTrue("Landscape item_schedule_card.xml must contain $id", landscapeContent.contains(id))
        }
    }

    @Test
    fun testViewPager2ViewTypesContract() {
        assertEquals("Page 0 must be Home", 0, MainPagerAdapter.VIEW_TYPE_HOME)
        assertEquals("Page 1 must be Schedule Board", 1, MainPagerAdapter.VIEW_TYPE_SCHEDULE)
        assertEquals("Page 2+ must be dynamic Boards", 2, MainPagerAdapter.VIEW_TYPE_BOARD)
    }

    @Test
    fun testScheduleItemSchemaMatchesPhase1() {
        val item = ScheduleItem(
            id = "sch_schema_test",
            type = "schedule",
            title = "Test Item",
            targetTimeUtc = 1731884400.0,
            durationSeconds = 60,
            recurrence = "FREQ=DAILY",
            status = "active",
            origin = "apk_ui",
            createdAtUtc = 1731800000.0,
            updatedAtUtc = 1731800000.0,
            metadata = mapOf("label" to "test")
        )

        val json = item.toJsonObject()
        val expectedKeys = listOf(
            "id",
            "type",
            "title",
            "target_time_utc",
            "duration_seconds",
            "recurrence",
            "status",
            "origin",
            "created_at_utc",
            "updated_at_utc",
            "metadata"
        )

        for (key in expectedKeys) {
            assertTrue("ScheduleItem JSON must contain wire key '$key'", json.has(key))
        }

        // Round-trip deserialization
        val deserialized = ScheduleItem.fromJsonObject(json)
        assertEquals(item.id, deserialized.id)
        assertEquals(item.type, deserialized.type)
        assertEquals(item.title, deserialized.title)
        assertEquals(item.targetTimeUtc, deserialized.targetTimeUtc, 0.001)
        assertEquals(item.durationSeconds, deserialized.durationSeconds)
        assertEquals(item.recurrence, deserialized.recurrence)
        assertEquals(item.status, deserialized.status)
        assertEquals(item.origin, deserialized.origin)
        assertEquals(item.createdAtUtc, deserialized.createdAtUtc, 0.001)
        assertEquals(item.updatedAtUtc, deserialized.updatedAtUtc, 0.001)
        assertEquals("test", deserialized.metadata["label"])
    }
}
