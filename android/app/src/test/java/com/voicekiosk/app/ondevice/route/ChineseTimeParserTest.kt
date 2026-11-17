package com.voicekiosk.app.ondevice.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseTimeParserTest {

    @Test
    fun parseMorningTimes() {
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 30, dayOffset = 1, recurrence = ""),
            ChineseTimeParser.parse("明天早上七点半")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 8, minute = 0, dayOffset = 1, recurrence = ""),
            ChineseTimeParser.parse("明早八点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 6, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("早上六点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 6, minute = 45, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("早晨六点四十五")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 10, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("上午十点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 9, minute = 0, dayOffset = 2, recurrence = ""),
            ChineseTimeParser.parse("后天上午九点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 5, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("凌晨五点")
        )
    }

    @Test
    fun parseAfternoonAndNoonTimes() {
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 14, minute = 15, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("今天下午两点一刻")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 14, minute = 15, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("下午两点十五")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 15, minute = 20, dayOffset = 1, recurrence = ""),
            ChineseTimeParser.parse("明天下午3点20分")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 12, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("中午十二点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 12, minute = 30, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("中午十二点半")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 13, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("中午一点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 13, minute = 30, dayOffset = 1, recurrence = ""),
            ChineseTimeParser.parse("明天中午一点半")
        )
    }

    @Test
    fun parseEveningAndNightTimes() {
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 20, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("今晚八点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 20, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("晚上八点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 22, minute = 30, dayOffset = 1, recurrence = ""),
            ChineseTimeParser.parse("明晚十点半")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 18, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("傍晚六点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 23, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("夜里十一点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 0, minute = 0, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("半夜十二点")
        )
    }

    @Test
    fun parseRecurringRules() {
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 8, minute = 0, dayOffset = 0, recurrence = "FREQ=DAILY"),
            ChineseTimeParser.parse("每天早上八点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 30, dayOffset = 0, recurrence = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
            ChineseTimeParser.parse("工作日七点半")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 30, dayOffset = 0, recurrence = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
            ChineseTimeParser.parse("工作日早上七点半")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 30, dayOffset = 0, recurrence = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"),
            ChineseTimeParser.parse("周一到周五早上七点半")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 9, minute = 0, dayOffset = 0, recurrence = "FREQ=WEEKLY;BYDAY=SA,SU"),
            ChineseTimeParser.parse("周末早上九点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 8, minute = 0, dayOffset = 0, recurrence = "FREQ=WEEKLY;BYDAY=MO"),
            ChineseTimeParser.parse("每周一早上八点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 16, minute = 0, dayOffset = 0, recurrence = "FREQ=WEEKLY;BYDAY=FR"),
            ChineseTimeParser.parse("每周五下午四点")
        )
    }

    @Test
    fun parseHalfAndQuarterPast() {
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 30, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("七点半")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 2, minute = 15, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("两点一刻")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 2, minute = 45, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("两点三刻")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 5, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("七点零五分")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 5, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("七点零五")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 55, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("差五分八点")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 45, dayOffset = 1, recurrence = ""),
            ChineseTimeParser.parse("明天差一刻八点")
        )
    }

    @Test
    fun parseColonFormats() {
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 30, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("7:30")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 14, minute = 15, dayOffset = 0, recurrence = ""),
            ChineseTimeParser.parse("14:15")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 7, minute = 30, dayOffset = 1, recurrence = ""),
            ChineseTimeParser.parse("明天早上7:30")
        )
        assertEquals(
            ChineseTimeParser.ParsedTime(hour = 8, minute = 0, dayOffset = 0, recurrence = "FREQ=DAILY"),
            ChineseTimeParser.parse("每天8:00")
        )
    }

    @Test
    fun rejectsNonClockTimesAndDurations() {
        // Durations must return null so they route to timer intent
        assertNull(ChineseTimeParser.parse("45分钟"))
        assertNull(ChineseTimeParser.parse("五分钟"))
        assertNull(ChineseTimeParser.parse("半小时"))
        assertNull(ChineseTimeParser.parse("一个半小时"))
        assertNull(ChineseTimeParser.parse("30秒"))
        assertNull(ChineseTimeParser.parse("1分30秒"))

        // Out of scope / invalid
        assertNull(ChineseTimeParser.parse("昨天七点"))
        assertNull(ChineseTimeParser.parse("大后天八点"))
        assertNull(ChineseTimeParser.parse("二十五点"))
        assertNull(ChineseTimeParser.parse("七点六十五分"))
        assertNull(ChineseTimeParser.parse("明天"))
        assertNull(ChineseTimeParser.parse("早上"))
        assertNull(ChineseTimeParser.parse(""))
        assertNull(ChineseTimeParser.parse("计时器怎么用"))
        assertNull(ChineseTimeParser.parse("五分钟有多少秒"))
    }
}
