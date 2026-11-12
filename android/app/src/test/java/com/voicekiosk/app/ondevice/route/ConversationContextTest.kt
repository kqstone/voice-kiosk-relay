package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.conversation.ConversationContext
import com.voicekiosk.app.conversation.TurnSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationContextTest {
    private var now = 1_000_000L
    private val ctx = ConversationContext(clock = { now }, idleTimeoutMs = 60_000, maxTurns = 3)
    private val vol = RouteDecision(Intents.VOLUME, mapOf("direction" to "up"), Route.LOCAL, 0.98, RouteSource.RULE)

    @Test
    fun recordsTurnsWithSourceAndState() {
        assertEquals("无", ctx.previousTurnLine())
        ctx.record("声音大一点", "音量已调大到60%。", TurnSource.LOCAL_RULE, vol)
        assertEquals("volume_set direction=up", ctx.previousTurnLine())
        ctx.record("今天股价", "……", TurnSource.CLOUD, null)
        assertNull(ctx.lastDecision())
        assertEquals(listOf(1L, 2L), ctx.turns().map { it.seq })
        assertEquals(1, ctx.unsyncedLocalTurns().size)
        assertEquals(TurnSource.LOCAL_RULE, ctx.unsyncedLocalTurns()[0].source)
    }

    @Test
    fun capsTurnsAndExpiresWhenIdle() {
        repeat(5) { ctx.record("t$it", "r", TurnSource.LOCAL_RULE, vol) }
        assertEquals(listOf("t2", "t3", "t4"), ctx.turns().map { it.userText })
        now += 61_000
        assertEquals(emptyList<Any>(), ctx.turns())
        assertEquals("无", ctx.previousTurnLine())
    }

    @Test
    fun resetClears() {
        ctx.record("a", "b", TurnSource.LOCAL_RULE, vol)
        ctx.reset()
        assertEquals(0, ctx.turns().size)
    }

    @Test
    fun turnIdsAreUniquePerContextAndMarkSyncedClearsPending() {
        val a = ctx.record("下一首", "好的", TurnSource.LOCAL_RULE, vol)
        val b = ctx.record("大声点", "好的", TurnSource.LOCAL_RULE, vol)
        val other = ConversationContext().record("下一首", "好的", TurnSource.LOCAL_RULE, vol)
        org.junit.Assert.assertNotEquals(a.id, b.id)
        org.junit.Assert.assertNotEquals(a.id, other.id)
        assertEquals(true, Regex("^[A-Za-z0-9_-]{1,64}$").matches(a.id))
        ctx.markSynced(listOf(a.id))
        assertEquals(listOf(b.id), ctx.unsyncedLocalTurns().map { it.id })
        assertEquals(2, ctx.turns().size)
    }

    @Test
    fun idleExpiryDropsPendingLocalTurnsLikeTheBridgeSession() {
        ctx.record("暂停", "好的", TurnSource.LOCAL_RULE, vol)
        now += 59_000
        assertEquals(1, ctx.unsyncedLocalTurns().size)
        now += 61_000
        assertEquals(0, ctx.unsyncedLocalTurns().size)
    }

    @Test
    fun localTurnsJsonCarriesOnlyUtteranceReplyActionAndAge() {
        val play = RouteDecision(Intents.MEDIA_PLAY, mapOf("artist" to "周杰伦", "song" to "Let It Go"), Route.LOCAL, 0.98, RouteSource.RULE)
        val t1 = ctx.record("放周杰伦的Let It Go", "好的，正在播放。", TurnSource.LOCAL_RULE, play)
        now += 5_000
        val hi = RouteDecision(Intents.CHITCHAT, mapOf("kind" to "greeting", "phrase" to "你好"), Route.LOCAL, 0.98, RouteSource.RULE)
        val t2 = ctx.record("你好" + "啊".repeat(400), "嗨，我在。", TurnSource.LOCAL_RULE, hi)
        val arr = com.voicekiosk.app.conversation.LocalTurnsCodec.toJson(listOf(t1, t2), now)
        assertEquals(2, arr.length())
        val j1 = arr.getJSONObject(0)
        assertEquals(t1.id, j1.getString("id"))
        assertEquals("media_play artist=周杰伦 song=LetItGo", j1.getString("action"))
        assertEquals(5_000L, j1.getLong("age_ms"))
        assertEquals(setOf("id", "user", "reply", "action", "age_ms"), j1.keys().asSequence().toSet())
        val j2 = arr.getJSONObject(1)
        assertEquals(false, j2.has("action"))
        assertEquals(com.voicekiosk.app.conversation.LocalTurnsCodec.MAX_USER_CHARS, j2.getString("user").length)
        assertEquals(listOf("a", "b"), com.voicekiosk.app.conversation.LocalTurnsCodec.acked(org.json.JSONObject("""{"acked_local_turn_ids":["a","b"]}""")))
        assertNull(com.voicekiosk.app.conversation.LocalTurnsCodec.acked(org.json.JSONObject("""{"reply":"x"}""")))
    }
}
