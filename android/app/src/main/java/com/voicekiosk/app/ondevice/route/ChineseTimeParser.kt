package com.voicekiosk.app.ondevice.route

/**
 * Parses Chinese clock-time expressions into hour and minute plus day offset and recurrence.
 *
 * Covers forms like:
 * - "明天早上七点半" -> hour=7, minute=30, dayOffset=1, recurrence=""
 * - "今天下午两点一刻" -> hour=14, minute=15, dayOffset=0, recurrence=""
 * - "下午两点十五" -> hour=14, minute=15, dayOffset=0, recurrence=""
 * - "每天早上八点" -> hour=8, minute=0, dayOffset=0, recurrence="FREQ=DAILY"
 * - "工作日七点半" -> hour=7, minute=30, dayOffset=0, recurrence="FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
 * - "周一到周五早上七点半" -> hour=7, minute=30, dayOffset=0, recurrence="FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"
 * - "周末早上九点" -> hour=9, minute=0, dayOffset=0, recurrence="FREQ=WEEKLY;BYDAY=SA,SU"
 *
 * Anything the deterministic parser cannot resolve returns null so the utterance falls through
 * to the cloud instead of guessing.
 */
object ChineseTimeParser {

    data class ParsedTime(
        val hour: Int,
        val minute: Int,
        val dayOffset: Int = 0,
        val recurrence: String = ""
    )

    private const val N = ChineseNumber.PATTERN

    private val RECURRENCES = listOf(
        Regex("^(?:工作日|周一到周五|周一至周五|星期一到星期五|礼拜一到礼拜五)") to "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        Regex("^(?:周末|双休日)") to "FREQ=WEEKLY;BYDAY=SA,SU",
        Regex("^(?:每天|天天|每日)") to "FREQ=DAILY",
        Regex("^(?:每周一|每星期一|每礼拜一)") to "FREQ=WEEKLY;BYDAY=MO",
        Regex("^(?:每周二|每星期二|每礼拜二)") to "FREQ=WEEKLY;BYDAY=TU",
        Regex("^(?:每周三|每星期三|每礼拜三)") to "FREQ=WEEKLY;BYDAY=WE",
        Regex("^(?:每周四|每星期四|每礼拜四)") to "FREQ=WEEKLY;BYDAY=TH",
        Regex("^(?:每周五|每星期五|每礼拜五)") to "FREQ=WEEKLY;BYDAY=FR",
        Regex("^(?:每周六|每星期六|每礼拜六)") to "FREQ=WEEKLY;BYDAY=SA",
        Regex("^(?:每周日|每周天|每星期日|每星期天|每礼拜日|每礼拜天)") to "FREQ=WEEKLY;BYDAY=SU"
    )

    private val INVALID_DAYS = Regex("^(?:大后天|昨天|前天|昨早|昨晚)")

    private val COMPOUND_DAYS = listOf(
        Regex("^(?:明早)") to (1 to "早上"),
        Regex("^(?:明晚)") to (1 to "晚上"),
        Regex("^(?:今早)") to (0 to "早上"),
        Regex("^(?:今晚)") to (0 to "晚上")
    )

    private val PLAIN_DAYS = listOf(
        Regex("^(?:明天|明日|明儿个|明儿)") to 1,
        Regex("^(?:后天|后儿)") to 2,
        Regex("^(?:今天|今日|今儿个|今儿)") to 0
    )

    private val PERIODS = listOf(
        Regex("^(?:凌晨)") to "凌晨",
        Regex("^(?:早晨|早上|清晨|一早|早)") to "早上",
        Regex("^(?:上午)") to "上午",
        Regex("^(?:中午)") to "中午",
        Regex("^(?:下午)") to "下午",
        Regex("^(?:傍晚)") to "傍晚",
        Regex("^(?:晚上|夜里|半夜)") to "晚上"
    )

    private val SUFFIXES_TO_STRIP = listOf(
        "的闹钟", "闹钟", "叫我起床", "喊我起床", "叫醒我", "提醒我起床", "叫我", "喊我"
    )

    private val DIFF_TIME_RE = Regex("^差(?:(${N})分|一刻|三刻)(${N})(?:点钟|点|时)$")
    private val COLON_TIME_RE = Regex("^(\\d{1,2}):(\\d{2})$")
    private val CHINESE_TIME_RE = Regex("^(${N})(?:点钟|点|时)(.*)$")
    private val MIN_ZERO_RE = Regex("^零(${N})(?:分|分钟)?$")
    private val MIN_EXPLICIT_RE = Regex("^(${N})(?:分|分钟)$")
    private val MIN_BARE_RE = Regex("^(${N})$")

    fun parse(raw: String): ParsedTime? {
        if (raw.isEmpty()) return null
        var s = raw.trim().replace("：", ":").replace(" ", "")
        for (suffix in SUFFIXES_TO_STRIP) {
            if (s.endsWith(suffix)) {
                s = s.removeSuffix(suffix)
                break
            }
        }
        s = s.removeSuffix("的")
        if (s.isEmpty()) return null

        var recurrence = ""
        for ((re, recVal) in RECURRENCES) {
            val m = re.find(s)
            if (m != null) {
                recurrence = recVal
                s = s.substring(m.range.last + 1)
                break
            }
        }

        if (INVALID_DAYS.containsMatchIn(s)) return null

        var dayOffset = 0
        var period: String? = null

        for ((re, pair) in COMPOUND_DAYS) {
            val m = re.find(s)
            if (m != null) {
                dayOffset = pair.first
                period = pair.second
                s = s.substring(m.range.last + 1)
                break
            }
        }

        if (period == null) {
            for ((re, offset) in PLAIN_DAYS) {
                val m = re.find(s)
                if (m != null) {
                    dayOffset = offset
                    s = s.substring(m.range.last + 1)
                    break
                }
            }
        }

        if (period == null) {
            for ((re, pVal) in PERIODS) {
                val m = re.find(s)
                if (m != null) {
                    period = pVal
                    s = s.substring(m.range.last + 1)
                    break
                }
            }
        }

        var hour: Int? = null
        var minute: Int? = null

        val diffMatch = DIFF_TIME_RE.matchEntire(s)
        if (diffMatch != null) {
            val diffMinStr = diffMatch.groups[1]?.value
            val hStr = diffMatch.groups[2]!!.value
            val rawH = ChineseNumber.parse(hStr) ?: return null
            val diff = when {
                diffMinStr != null -> ChineseNumber.parse(diffMinStr) ?: return null
                s.contains("一刻") -> 15
                s.contains("三刻") -> 45
                else -> return null
            }
            if (diff !in 1..59) return null
            minute = 60 - diff
            hour = (rawH - 1 + 24) % 24
        } else {
            val colonMatch = COLON_TIME_RE.matchEntire(s)
            if (colonMatch != null) {
                hour = colonMatch.groups[1]!!.value.toIntOrNull() ?: return null
                minute = colonMatch.groups[2]!!.value.toIntOrNull() ?: return null
            } else {
                val chMatch = CHINESE_TIME_RE.matchEntire(s) ?: return null
                val rawH = ChineseNumber.parse(chMatch.groups[1]!!.value) ?: return null
                val minPart = chMatch.groups[2]!!.value
                val mVal = when {
                    minPart.isEmpty() || minPart == "整" -> 0
                    minPart == "半" -> 30
                    minPart == "一刻" -> 15
                    minPart == "三刻" -> 45
                    else -> {
                        val zeroMatch = MIN_ZERO_RE.matchEntire(minPart)
                        if (zeroMatch != null) {
                            ChineseNumber.parse(zeroMatch.groups[1]!!.value) ?: return null
                        } else {
                            val expMatch = MIN_EXPLICIT_RE.matchEntire(minPart)
                            if (expMatch != null) {
                                ChineseNumber.parse(expMatch.groups[1]!!.value) ?: return null
                            } else {
                                val bareMatch = MIN_BARE_RE.matchEntire(minPart) ?: return null
                                val num = ChineseNumber.parse(bareMatch.groups[1]!!.value) ?: return null
                                if (num !in 10..59) return null
                                num
                            }
                        }
                    }
                }
                hour = rawH
                minute = mVal
            }
        }

        if (hour == null || minute == null || minute !in 0..59) return null

        val adjustedHour = when (period) {
            "下午" -> when (hour) {
                in 1..11 -> hour + 12
                12 -> 12
                in 13..23 -> hour
                else -> return null
            }
            "晚上", "夜里", "半夜", "傍晚" -> when (hour) {
                in 1..11 -> hour + 12
                12 -> 0
                in 18..23 -> hour
                else -> return null
            }
            "中午" -> when (hour) {
                12 -> 12
                in 1..2 -> hour + 12
                11 -> 11
                else -> return null
            }
            "凌晨" -> when (hour) {
                12 -> 0
                in 0..6 -> hour
                else -> return null
            }
            "早上" -> when (hour) {
                in 0..11 -> hour
                12 -> 0
                else -> return null
            }
            "上午" -> when (hour) {
                in 0..12 -> hour
                else -> return null
            }
            else -> when (hour) {
                in 0..23 -> hour
                else -> return null
            }
        }

        if (adjustedHour !in 0..23) return null

        return ParsedTime(
            hour = adjustedHour,
            minute = minute,
            dayOffset = dayOffset,
            recurrence = recurrence
        )
    }
}
