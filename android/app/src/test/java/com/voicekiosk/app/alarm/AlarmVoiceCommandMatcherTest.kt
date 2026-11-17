package com.voicekiosk.app.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmVoiceCommandMatcherTest {

    @Test
    fun testDismissPhrases_chinese() {
        val dismissPhrases = listOf(
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
            "停",
            "停下"
        )

        for (phrase in dismissPhrases) {
            val result = AlarmVoiceCommandMatcher.match(phrase)
            assertEquals("Expected DISMISS for '$phrase'", AlarmVoiceAction.DISMISS, result.action)
        }
    }

    @Test
    fun testDismissPhrases_english() {
        val dismissPhrases = listOf(
            "I know",
            "i know it",
            "stop",
            "STOP IT",
            "dismiss",
            "dismiss alarm",
            "shut up",
            "turn off",
            "turn off alarm",
            "cancel"
        )

        for (phrase in dismissPhrases) {
            val result = AlarmVoiceCommandMatcher.match(phrase)
            assertEquals("Expected DISMISS for '$phrase'", AlarmVoiceAction.DISMISS, result.action)
        }
    }

    @Test
    fun testSnoozePhrases_chineseDefaultFiveMinutes() {
        val generalSnooze = listOf(
            "稍后提醒",
            "稍后再响",
            "过会儿提醒",
            "过会提醒",
            "再睡一会",
            "再睡会儿",
            "再眯一会",
            "多睡会"
        )

        for (phrase in generalSnooze) {
            val result = AlarmVoiceCommandMatcher.match(phrase)
            assertEquals("Expected SNOOZE for '$phrase'", AlarmVoiceAction.SNOOZE, result.action)
            assertEquals("Expected 5 min for '$phrase'", 5, result.snoozeMinutes)
        }
    }

    @Test
    fun testSnoozePhrases_chineseCustomMinutes() {
        val cases = listOf(
            "五分钟后提醒我" to 5,
            "5分钟后提醒我" to 5,
            "再睡五分钟" to 5,
            "十分钟后提醒我" to 10,
            "10分钟后叫我" to 10,
            "再睡十分钟" to 10,
            "再睡15分钟" to 15,
            "过二十分钟再响" to 20
        )

        for ((phrase, expectedMinutes) in cases) {
            val result = AlarmVoiceCommandMatcher.match(phrase)
            assertEquals("Expected SNOOZE for '$phrase'", AlarmVoiceAction.SNOOZE, result.action)
            assertEquals("Expected $expectedMinutes min for '$phrase'", expectedMinutes, result.snoozeMinutes)
        }
    }

    @Test
    fun testSnoozePhrases_english() {
        val cases = listOf(
            "snooze" to 5,
            "remind me later" to 5,
            "remind me in five minutes" to 5,
            "remind me in 5 minutes" to 5,
            "remind me in ten minutes" to 10,
            "remind me in 10 minutes" to 10,
            "five more minutes" to 5,
            "10 more minutes" to 10
        )

        for ((phrase, expectedMinutes) in cases) {
            val result = AlarmVoiceCommandMatcher.match(phrase)
            assertEquals("Expected SNOOZE for '$phrase'", AlarmVoiceAction.SNOOZE, result.action)
            assertEquals("Expected $expectedMinutes min for '$phrase'", expectedMinutes, result.snoozeMinutes)
        }
    }

    @Test
    fun testUnrelatedPhrases_returnsNone() {
        val unrelated = listOf(
            "",
            "   ",
            "今天天气怎么样",
            "播放一首周杰伦的青花瓷",
            "倒计时十分钟",
            "你好小智",
            "现在几点了",
            "明天会下雨吗",
            "tell me a joke"
        )

        for (phrase in unrelated) {
            val result = AlarmVoiceCommandMatcher.match(phrase)
            assertEquals("Expected NONE for '$phrase'", AlarmVoiceAction.NONE, result.action)
        }
    }
}
