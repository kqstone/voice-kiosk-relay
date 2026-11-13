package com.voicekiosk.app

import com.voicekiosk.app.data.BoardComponent
import com.voicekiosk.app.data.BoardParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardParserTest {

    @Test
    fun testParseChecklistBoard() {
        val rawJson = """
            {
                "board_id": "test_board_1",
                "source": "muse",
                "updated_at": "2026-11-13T10:00:00Z",
                "data": {
                    "title": "Daily Tasks",
                    "tag": "Grade 3",
                    "subtitle": "Before bedtime checklist",
                    "tasks": [
                        {
                            "id": "t1",
                            "label": "Math",
                            "text": "Complete workbook pages 12-13",
                            "completed": false
                        },
                        {
                            "id": "t2",
                            "label": "Science",
                            "text": "Prepare plant observation sheet",
                            "completed": true
                        }
                    ]
                }
            }
        """.trimIndent()

        val board = BoardParser.parseBoard("test_board_1", rawJson)

        assertEquals("test_board_1", board.boardId)
        assertEquals("Daily Tasks", board.titleComponent.title)
        assertEquals("Grade 3", board.titleComponent.tag)
        assertEquals("Before bedtime checklist", board.titleComponent.subtitle)
        assertEquals("1 / 2 完成", board.titleComponent.badge)

        assertEquals(1, board.components.size)
        val checklist = board.components[0] as BoardComponent.Checklist
        assertEquals(2, checklist.items.size)

        val item1 = checklist.items[0]
        assertEquals("t1", item1.id)
        assertEquals("Math", item1.label)
        assertEquals("Complete workbook pages 12-13", item1.text)
        assertFalse(item1.completed)
        assertTrue(item1.colorHint.isNotEmpty())

        val item2 = checklist.items[1]
        assertEquals("t2", item2.id)
        assertEquals("Science", item2.label)
        assertEquals("Prepare plant observation sheet", item2.text)
        assertTrue(item2.completed)
    }

    @Test
    fun testParseGenericListBoard() {
        val rawJson = """
            {
                "board_id": "announcements",
                "data": {
                    "title": "Family Notes",
                    "type": "list",
                    "items": [
                        "Water the plants at 5 PM",
                        "Turn off living room heater before leaving"
                    ]
                }
            }
        """.trimIndent()

        val board = BoardParser.parseBoard("announcements", rawJson)

        assertEquals("announcements", board.boardId)
        assertEquals("Family Notes", board.titleComponent.title)
        assertEquals("2 项", board.titleComponent.badge)

        assertEquals(1, board.components.size)
        val listComp = board.components[0] as BoardComponent.ListComp
        assertEquals(2, listComp.items.size)
        assertEquals("Water the plants at 5 PM", listComp.items[0].text)
        assertEquals("Turn off living room heater before leaving", listComp.items[1].text)
    }

    @Test
    fun testParseKeyValueBoard() {
        val rawJson = """
            {
                "board_id": "device_status",
                "data": {
                    "title": "System Parameters",
                    "Battery": "98%",
                    "WiFi": "Connected (5GHz)",
                    "Uptime": "7 days"
                }
            }
        """.trimIndent()

        val board = BoardParser.parseBoard("device_status", rawJson)

        assertEquals("device_status", board.boardId)
        assertEquals("System Parameters", board.titleComponent.title)
        assertEquals("3 项", board.titleComponent.badge)

        assertEquals(1, board.components.size)
        val kvComp = board.components[0] as BoardComponent.KeyValue
        assertEquals(3, kvComp.pairs.size)
        val map = kvComp.pairs.associate { it.key to it.value }
        assertEquals("98%", map["Battery"])
        assertEquals("Connected (5GHz)", map["WiFi"])
        assertEquals("7 days", map["Uptime"])
    }

    @Test
    fun testParseExplicitComponents() {
        val rawJson = """
            {
                "board_id": "explicit_board",
                "data": {
                    "components": [
                        {
                            "type": "title",
                            "title": "Custom Title",
                            "tag": "Custom Tag",
                            "subtitle": "Custom Sub",
                            "badge": "Custom Badge"
                        },
                        {
                            "type": "checklist",
                            "items": [
                                {
                                    "id": "c1",
                                    "label": "Reading",
                                    "text": "Read chapter 4",
                                    "completed": false,
                                    "color_hint": "#10B981"
                                }
                            ]
                        }
                    ]
                }
            }
        """.trimIndent()

        val board = BoardParser.parseBoard("explicit_board", rawJson)

        assertEquals("Custom Title", board.titleComponent.title)
        assertEquals("Custom Tag", board.titleComponent.tag)
        assertEquals("Custom Sub", board.titleComponent.subtitle)
        assertEquals("Custom Badge", board.titleComponent.badge)

        assertEquals(1, board.components.size)
        val checklist = board.components[0] as BoardComponent.Checklist
        assertEquals(1, checklist.items.size)
        assertEquals("c1", checklist.items[0].id)
        assertEquals("Reading", checklist.items[0].label)
        assertEquals("#10B981", checklist.items[0].colorHint)
    }

    @Test
    fun testZeroEstimatedTimeInBoardParser() {
        // Confirm that even if incoming JSON carries an estimated_time or meta field,
        // BoardParser deliberately ignores it and does NOT store or expose it.
        val rawJson = """
            {
                "board_id": "homework",
                "data": {
                    "title": "Homework",
                    "tasks": [
                        {
                            "id": "1",
                            "text": "Math exercises",
                            "estimated_time": "30 mins",
                            "meta": "30 min"
                        }
                    ]
                }
            }
        """.trimIndent()

        val board = BoardParser.parseBoard("homework", rawJson)
        val checklist = board.components[0] as BoardComponent.Checklist
        val item = checklist.items[0]

        // ChecklistItem only has: id, label, text, completed, colorHint.
        // It has NO meta or estimated_time fields.
        assertEquals("1", item.id)
        assertEquals("Math exercises", item.text)
    }
}
