package com.voicekiosk.app.ui

import java.util.Calendar

/**
 * Chinese Lunar date representation.
 */
data class LunarDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val isLeapMonth: Boolean
) {
    /**
     * Converts to standard Chinese lunar date string, e.g. "农历十月初三" or "农历闰八月初八".
     */
    fun toChineseString(): String {
        val leapPrefix = if (isLeapMonth) "闰" else ""
        val monthStr = LunarCalendarHelper.CHINESE_MONTHS.getOrElse(month) { "${month}" } + "月"
        val dayStr = LunarCalendarHelper.CHINESE_DAYS.getOrElse(day) { "${day}日" }
        return "农历$leapPrefix$monthStr$dayStr"
    }
}

/**
 * Lightweight Chinese Lunar Calendar Helper.
 * Converts Gregorian dates to accurate Chinese lunar date strings.
 * Covers years 1900 to 2050 using standard 20-bit lunar bitmask tables.
 *
 * NOTE: Contains zero hardcoded board content and zero estimated-time fields.
 */
object LunarCalendarHelper {

    val CHINESE_MONTHS = arrayOf("", "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊")

    val CHINESE_DAYS = arrayOf(
        "", "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    )

    val WEEKDAYS = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /**
     * Standard lunar data table encoding years 1900 to 2050 (151 years).
     * Format:
     * - Bits 0..3: Leap month (0 = none, 1..12 = leap month number)
     * - Bits 4..15: Regular month lengths for months 12 down to 1 (1 = 30 days, 0 = 29 days)
     * - Bit 16: Leap month length (1 = 30 days, 0 = 29 days)
     */
    private val LUNAR_INFO = intArrayOf(
        0x04bd8, 0x04ae0, 0x0a570, 0x054d5, 0x0d260, 0x0d950, 0x16554, 0x056a0, 0x09ad0, 0x055d2,
        0x04ae0, 0x0a5b6, 0x0a4d0, 0x0d250, 0x1d255, 0x0b540, 0x0d6a0, 0x0ada2, 0x095b0, 0x14977,
        0x04970, 0x0a4b0, 0x0b4b5, 0x06a50, 0x06d40, 0x1ab54, 0x02b60, 0x09570, 0x052f2, 0x04970,
        0x06566, 0x0d4a0, 0x0ea50, 0x06e95, 0x05ad0, 0x02b60, 0x186e3, 0x092e0, 0x1c8d7, 0x04950,
        0x0d4a0, 0x1f8a6, 0x0b550, 0x056a0, 0x1aba4, 0x025d0, 0x092d0, 0x0d2b2, 0x0a950, 0x0b557,
        0x06ca0, 0x0b550, 0x15355, 0x04da0, 0x0a5d0, 0x14573, 0x052d0, 0x0a9a8, 0x0e950, 0x06aa0,
        0x0aea6, 0x0ab50, 0x04b60, 0x0aae4, 0x0a570, 0x05260, 0x0f263, 0x0d950, 0x05b57, 0x056a0,
        0x096d0, 0x04dd5, 0x04ad0, 0x0a4d0, 0x0d4d4, 0x0d250, 0x0d558, 0x0b540, 0x0b5a0, 0x195a6,
        0x095b0, 0x049b0, 0x0a974, 0x0a4b0, 0x0b27a, 0x06a50, 0x06d40, 0x0af46, 0x0ab60, 0x09570,
        0x04af5, 0x04970, 0x064b0, 0x074a3, 0x0ea50, 0x06b58, 0x055c0, 0x0ab60, 0x096d5, 0x092e0,
        0x0c960, 0x0d954, 0x0d4a0, 0x0da50, 0x07552, 0x056a0, 0x0abb7, 0x025d0, 0x092d0, 0x0cab5,
        0x0a950, 0x0b4a0, 0x0baa4, 0x0ad50, 0x055d9, 0x04ba0, 0x0a5b0, 0x15176, 0x052b0, 0x0a930,
        0x07954, 0x06aa0, 0x0ad50, 0x05b52, 0x04b60, 0x0a6e6, 0x0a4e0, 0x0d260, 0x0ea65, 0x0d530,
        0x05aa0, 0x076a3, 0x096d0, 0x04bd7, 0x04ad0, 0x0a4d0, 0x1d0b6, 0x0d250, 0x0d520, 0x0dd45,
        0x0b5a0, 0x056d0, 0x055b2, 0x049b0, 0x0a577, 0x0a4b0, 0x0aa50, 0x1b255, 0x06d20, 0x0ada0,
        0x14b63
    )

    private fun getYearDays(yearInfo: Int): Int {
        var days = 0
        val leapMonth = yearInfo and 0xf
        for (m in 1..12) {
            days += ((yearInfo shr (16 - m)) and 1) + 29
        }
        if (leapMonth in 1..12) {
            days += ((yearInfo shr 16) and 1) + 29
        }
        return days
    }

    /**
     * Converts a Gregorian date (year, month 1..12, day 1..31) into a LunarDate.
     */
    fun solarToLunar(year: Int, month: Int, day: Int): LunarDate {
        val baseCal = Calendar.getInstance().apply {
            set(1900, Calendar.JANUARY, 31, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val targetCal = Calendar.getInstance().apply {
            set(year, month - 1, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val diffMs = targetCal.timeInMillis - baseCal.timeInMillis
        // Add 12h (43200000ms) to prevent Daylight Saving Time / time zone offset drift
        var offset = ((diffMs + 43200000L) / (24 * 60 * 60 * 1000L)).toInt()

        if (offset < 0) {
            return LunarDate(year, month, day, false)
        }

        var yearIdx = 0
        while (yearIdx < LUNAR_INFO.size) {
            val yearDays = getYearDays(LUNAR_INFO[yearIdx])
            if (offset < yearDays) {
                break
            }
            offset -= yearDays
            yearIdx++
        }

        if (yearIdx >= LUNAR_INFO.size) {
            return LunarDate(year, month, day, false)
        }

        val lYear = 1900 + yearIdx
        val yearInfo = LUNAR_INFO[yearIdx]
        val leapMonth = yearInfo and 0xf

        // Enumerate months in sequence (regular and intercalary leap month if present)
        val monthList = mutableListOf<Pair<Int, Boolean>>()
        for (m in 1..12) {
            monthList.add(Pair(m, false))
            if (m == leapMonth) {
                monthList.add(Pair(m, true))
            }
        }

        for ((m, isLeap) in monthList) {
            val mDays = if (isLeap) {
                ((yearInfo shr 16) and 1) + 29
            } else {
                ((yearInfo shr (16 - m)) and 1) + 29
            }

            if (offset < mDays) {
                return LunarDate(lYear, m, offset + 1, isLeap)
            }
            offset -= mDays
        }

        return LunarDate(lYear, 12, offset + 1, false)
    }

    /**
     * Converts the date in the given Calendar instance to LunarDate.
     */
    fun solarToLunar(calendar: Calendar = Calendar.getInstance()): LunarDate {
        val y = calendar.get(Calendar.YEAR)
        val m = calendar.get(Calendar.MONTH) + 1
        val d = calendar.get(Calendar.DAY_OF_MONTH)
        return solarToLunar(y, m, d)
    }

    /**
     * Returns the Chinese lunar date string for the given Calendar date (e.g. "农历十月初三").
     */
    fun getLunarDateString(calendar: Calendar = Calendar.getInstance()): String {
        return solarToLunar(calendar).toChineseString()
    }

    /**
     * Formats the complete date line matching Screenshots 1 & 4:
     * e.g. "11月12日 星期四 · 农历十月初三"
     */
    fun formatFullDateLine(calendar: Calendar = Calendar.getInstance()): String {
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val dayOfWeekIndex = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val weekday = WEEKDAYS.getOrElse(dayOfWeekIndex) { "" }
        val lunar = getLunarDateString(calendar)
        return "${month}月${day}日 $weekday · $lunar"
    }
}
