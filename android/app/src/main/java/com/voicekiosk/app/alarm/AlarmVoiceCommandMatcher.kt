package com.voicekiosk.app.alarm

import com.voicekiosk.app.ondevice.route.ChineseNumber
import java.util.Locale

enum class AlarmVoiceAction {
    DISMISS,
    SNOOZE,
    NONE
}

data class VoiceMatchResult(
    val action: AlarmVoiceAction,
    val snoozeMinutes: Int = 5
)

/**
 * Natural language voice command matcher for ringing alarms.
 * Supports dismissal phrases ("我知道了", "别响了", "stop", "I know")
 * and snooze phrases ("稍后提醒", "五分钟后提醒我", "remind me in five minutes").
 * Snooze duration defaults to 5 minutes if unspecified.
 */
object AlarmVoiceCommandMatcher {

    private val DISMISS_CHINESE_KEYWORDS = listOf(
        "我知道了",
        "我知道啦",
        "知道了",
        "别响了",
        "不要响了",
        "别叫了",
        "停止",
        "关闭闹钟",
        "关闭",
        "关掉闹钟",
        "关掉",
        "不响了",
        "别吵了",
        "闭嘴",
        "停一下",
        "停掉",
        "停"
    )

    private val DISMISS_ENGLISH_REGEX = Regex(
        "\\b(?:i\\s+know(?:\\s+it)?|stop(?:\\s+it|\\s+alarm)?|dismiss(?:\\s+alarm)?|shut\\s+up|turn\\s+off(?:\\s+alarm)?|cancel)\\b",
        RegexOption.IGNORE_CASE
    )

    private val SNOOZE_CHINESE_GENERAL = listOf(
        "稍后提醒",
        "稍后再响",
        "过会儿提醒",
        "过会提醒",
        "再睡一会",
        "再睡会儿",
        "再眯一会",
        "再眯会儿",
        "多睡会"
    )

    // Matches e.g. "五分钟后提醒我", "再睡十分钟", "10分钟后叫我", "再睡5分钟"
    private val SNOOZE_CHINESE_WITH_DURATION = Regex(
        "(?:再睡|稍后|过|等)?(?<num>${ChineseNumber.PATTERN}|[0-9]+)(?:个)?(?:分钟|分)(?:钟)?(?:之后|后)?(?:再)?(?:提醒我|叫我|响)?"
    )

    // Matches e.g. "remind me in 5 minutes", "remind me in five minutes", "five more minutes"
    private val SNOOZE_ENGLISH_REGEX = Regex(
        "\\b(?:remind\\s+me\\s+in\\s+(?<num>\\d+|one|two|three|four|five|six|seven|eight|nine|ten|fifteen|twenty)\\s+minutes?|snooze|remind\\s+me\\s+later|(?:give\\s+me\\s+)?(?<num2>\\d+|five|ten)\\s+more\\s+minutes?)\\b",
        RegexOption.IGNORE_CASE
    )

    private val ENGLISH_WORD_NUMBERS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "fifteen" to 15, "twenty" to 20
    )

    fun match(text: String?): VoiceMatchResult {
        if (text.isNullOrBlank()) return VoiceMatchResult(AlarmVoiceAction.NONE)

        val trimmed = text.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Check Snooze first if specific snooze phrases are present
        val cnSnoozeMatch = SNOOZE_CHINESE_WITH_DURATION.find(trimmed)
        if (cnSnoozeMatch != null) {
            val numStr = cnSnoozeMatch.groups["num"]?.value.orEmpty()
            val parsedMinutes = parseMinutes(numStr) ?: 5
            return VoiceMatchResult(AlarmVoiceAction.SNOOZE, parsedMinutes.coerceIn(1, 60))
        }

        if (SNOOZE_CHINESE_GENERAL.any { trimmed.contains(it) }) {
            return VoiceMatchResult(AlarmVoiceAction.SNOOZE, 5)
        }

        val enSnoozeMatch = SNOOZE_ENGLISH_REGEX.find(lower)
        if (enSnoozeMatch != null) {
            val numStr = enSnoozeMatch.groups["num"]?.value
                ?: enSnoozeMatch.groups["num2"]?.value
            val minutes = if (!numStr.isNullOrEmpty()) {
                numStr.toIntOrNull() ?: ENGLISH_WORD_NUMBERS[numStr] ?: 5
            } else {
                5
            }
            return VoiceMatchResult(AlarmVoiceAction.SNOOZE, minutes.coerceIn(1, 60))
        }

        // 2. Check Dismiss
        if (DISMISS_CHINESE_KEYWORDS.any { trimmed.contains(it) }) {
            return VoiceMatchResult(AlarmVoiceAction.DISMISS)
        }

        if (DISMISS_ENGLISH_REGEX.containsMatchIn(lower)) {
            return VoiceMatchResult(AlarmVoiceAction.DISMISS)
        }

        return VoiceMatchResult(AlarmVoiceAction.NONE)
    }

    private fun parseMinutes(raw: String): Int? {
        if (raw.all { it in '0'..'9' }) {
            return raw.toIntOrNull()
        }
        return ChineseNumber.parse(raw)
    }
}
