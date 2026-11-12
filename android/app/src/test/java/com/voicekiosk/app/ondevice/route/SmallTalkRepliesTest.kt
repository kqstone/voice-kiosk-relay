package com.voicekiosk.app.ondevice.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SmallTalkRepliesTest {
    @Test
    fun timeOfDayAware() {
        assertTrue(SmallTalkReplies.options("greeting", "早安", 8).all { it.contains("早") })
        assertEquals(listOf("现在已经是下午啦，下午好！"), SmallTalkReplies.options("greeting", "早安", 15))
        assertTrue(SmallTalkReplies.options("greeting", "下午好", 15).all { it.contains("下午") })
        assertEquals(listOf("现在已经是晚上啦，晚上好！"), SmallTalkReplies.options("greeting", "下午好", 19))
        assertTrue(SmallTalkReplies.options("bye", "晚安", 23).all { it.contains("晚安") })
        assertEquals(listOf("好的，休息一下吧，晚安。"), SmallTalkReplies.options("bye", "晚安", 10))
        assertTrue(SmallTalkReplies.options("thanks", "辛苦了", 10).all { it.startsWith("不辛苦") })
    }

    @Test
    fun allRepliesAreShortAndVaried() {
        for (kind in listOf("greeting", "thanks", "how_are_you", "bye", "praise")) {
            for (h in 0..23) {
                val o = SmallTalkReplies.options(kind, "", h)
                assertTrue(o.isNotEmpty())
                o.forEach { assertTrue(it, it.length in 2..40) }
            }
        }
        val r = SmallTalkReplies(hour = { 12 }, random = Random(1))
        val seq = (1..10).map { r.reply("praise", "你真棒")!! }
        seq.zipWithNext().forEach { (a, b) -> assertTrue(a != b) }
        assertNull(r.reply("unknown", "x"))
    }
}
