package com.voicekiosk.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerQueueTest {
    @Test
    fun dropsExpiredAndCapsSize() {
        var now = 1_000L
        val q = AnswerQueue(clock = { now }, maxSize = 3)
        assertTrue(q.enqueue("a", "一", null, 5_000))
        assertTrue(q.enqueue("b", "二", null, 5_000))
        assertTrue(q.enqueue("c", "三", null, 5_000))
        assertTrue(q.enqueue("d", "四", null, 5_000))
        assertEquals(3, q.size())
        assertEquals(listOf("b", "c", "d"), q.pending().map { it.questionId })
        now = 6_000L
        assertEquals(0, q.size())
        assertTrue(q.pending().isEmpty())
    }

    @Test
    fun rejectsAlreadyExpiredAndReplacesSameId() {
        val now = 10_000L
        val q = AnswerQueue(clock = { now })
        assertFalse(q.enqueue("x", "一", null, 9_999))
        assertTrue(q.enqueue("x", "一", null, 20_000))
        assertTrue(q.enqueue("x", "二", null, 30_000))
        assertEquals(1, q.size())
        assertEquals("二", q.pending().single().option)
        val taken = q.takeReady()
        assertEquals(1, taken.size)
        assertEquals(0, q.size())
    }

    @Test
    fun fallbackTtlWhenExpiresAtUnknown() {
        var now = 1_000L
        val q = AnswerQueue(clock = { now })
        assertTrue(q.enqueue("z", null, "自由回答", 0))
        assertEquals(now + AnswerQueue.FALLBACK_TTL_MS, q.pending().single().expiresAtMs)
        now += AnswerQueue.FALLBACK_TTL_MS + 1
        assertEquals(0, q.size())
    }
}
