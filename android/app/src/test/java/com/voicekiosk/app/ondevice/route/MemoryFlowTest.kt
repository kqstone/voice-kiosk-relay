package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.conversation.CloudReply
import com.voicekiosk.app.conversation.ConversationContext
import com.voicekiosk.app.conversation.TurnSource
import com.voicekiosk.app.memory.MemoryCandidate
import com.voicekiosk.app.memory.MemoryCategory
import com.voicekiosk.app.memory.MemoryConfirmations
import com.voicekiosk.app.memory.MemoryFact
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryFlowTest {
    private class FakeConfirmations : MemoryConfirmations {
        val queue = mutableListOf(MemoryCandidate("c1", MemoryFact(MemoryCategory.FAMILY, "女儿叫朵朵"), 0, "pending"))
        val answers = mutableListOf<Pair<String, Boolean>>()
        val expired = mutableListOf<String>()
        override fun nextToAsk(nowMs: Long) = queue.removeFirstOrNull()
        override fun answer(id: String, accepted: Boolean) { answers += id to accepted }
        override fun expire(id: String) { expired += id }
    }

    private val platform = FakePlatform()
    private val ctx = ConversationContext()
    private val cloudCalls = mutableListOf<String>()
    private var now = 1_000L
    private val mem = FakeConfirmations()

    private fun orch(favorite: String? = null) = TurnOrchestrator(
        TurnOrchestrator.Config(modelRoutingEnabled = false), RuleRouter(), null,
        LocalToolExecutor(platform, favoriteArtist = { favorite }), ctx,
        cloud = { req -> cloudCalls += req.text; Result.success(CloudReply("云端：${req.text}", null)) },
        clock = { now }, memory = mem
    )

    @Test
    fun asksAfterReplyAndAcceptsYes() {
        val o = orch()
        val r = o.handle("今天天气为什么不准")
        assertTrue(r.reply!!.endsWith("要我记住「女儿叫朵朵」吗？"))
        now += 5_000
        val a = o.handle("是的")
        assertEquals("好的，我记住了。", a.reply)
        assertEquals(TurnSource.LOCAL_RULE, a.source)
        assertEquals(listOf("c1" to true), mem.answers)
        assertEquals(1, cloudCalls.size)
    }

    @Test
    fun noAndOtherUtterances() {
        val o = orch()
        o.handle("今天天气为什么不准")
        assertEquals("好的，不记。", o.handle("不用了").reply)
        assertEquals(listOf("c1" to false), mem.answers)

        mem.queue += MemoryCandidate("c2", MemoryFact(MemoryCategory.PREFERENCE, "喜欢喝绿茶"), 0, "pending")
        o.handle("今天天气为什么不准")
        val r = o.handle("把音量调到三十")
        assertEquals("音量已调到33%。", r.reply)
        assertEquals(listOf("c2"), mem.expired)
    }

    @Test
    fun lateAnswerIsNotTaken() {
        val o = orch()
        o.handle("今天天气为什么不准")
        now += TurnOrchestrator.MEMORY_ANSWER_WINDOW_MS + 1
        o.handle("是的")
        assertEquals(emptyList<Pair<String, Boolean>>(), mem.answers)
        assertEquals(listOf("c1"), mem.expired)
    }

    @Test
    fun notAskedAfterMusicOrMemoryCommands() {
        val o = orch()
        val r = o.handle("播放周杰伦的晴天")
        assertTrue(!r.reply!!.contains("记住"))
        val m = o.handle("忘掉我喜欢喝绿茶")
        assertTrue(!m.reply!!.contains("要我记住"))
        assertEquals(1, mem.queue.size)
    }

    @Test
    fun playFavourite() {
        assertEquals(Intents.MEDIA_PLAY, RuleRouter().route("放我喜欢的歌")?.intent)
        assertEquals("1", RuleRouter().route("来点我最爱听的歌")?.slots?.get("favorite"))
        orch(favorite = "周杰伦").handle("放我喜欢的歌")
        assertEquals("周杰伦", platform.lastSearch?.second)
        platform.lastSearch = null
        val r = orch(favorite = null).handle("放我喜欢的歌")
        assertNull(platform.lastSearch)
        assertTrue(r.reply!!.startsWith("我还不知道你喜欢听谁的歌"))
    }
}
