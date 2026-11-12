package com.voicekiosk.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryManagerTest {
    private val backend = FakeBackend()
    private var now = 1_000_000L
    private var seq = 0
    private var modelRuns = mutableListOf<List<String>>()
    private var modelFacts: List<MemoryFact> = emptyList()
    private var allowModel = true

    private fun manager(cache: LocalMemoryCache = LocalMemoryCache(null, IdentityCodec), withModel: Boolean = false) = MemoryManager(
        backend, cache,
        model = { if (!withModel) null else { u: List<String> -> modelRuns += u; ModelMemoryExtractor.Outcome(modelFacts, "ok", ms = 5) } },
        canRunModel = { allowModel },
        executor = DirectExecutor, clock = { now }, newId = { "c${++seq}" }
    )

    @Test
    fun autoCategoriesUploadImmediately() {
        val m = manager()
        val r = m.process(listOf("我喜欢听周杰伦的歌", "播放晴天"))
        assertEquals(listOf("喜欢听周杰伦的歌"), r.auto.map { it.content })
        assertEquals(listOf("喜欢听周杰伦的歌"), backend.items.map { it.content })
        assertEquals("auto", backend.submitted.single().state)
        assertEquals(emptyList<MemoryCandidate>(), m.view().second)
        assertEquals(listOf("喜欢听周杰伦的歌"), m.view().first.map { it.content })
    }

    @Test
    fun knownFactsAreNotResubmitted() {
        backend.items += MemoryItem(7, "", "喜欢听周杰伦的歌")
        val m = manager()
        m.refreshSnapshot(0)
        val r = m.process(listOf("我喜欢听周杰伦的歌"))
        assertEquals(1, r.known.size)
        assertEquals(0, backend.submitted.size)
    }

    @Test
    fun confirmCategoriesWaitForYes() {
        val m = manager()
        val r = m.process(listOf("我女儿叫朵朵"))
        assertEquals(listOf("女儿叫朵朵"), r.pending.map { it.content })
        assertEquals(0, backend.submitted.size)
        val c = m.nextToAsk(now)!!
        assertEquals("女儿叫朵朵", c.fact.content)
        assertNull("asked once only", m.nextToAsk(now + MemoryManager.ASK_SPACING_MS + 1))
        m.answer(c.id, true)
        assertEquals(listOf("女儿叫朵朵"), backend.items.map { it.content })
        assertEquals("confirmed", backend.submitted.single().state)
    }

    @Test
    fun noOrExpiryDropsCandidate() {
        val m = manager()
        m.process(listOf("我女儿叫朵朵", "我喜欢喝绿茶"))
        val a = m.nextToAsk(now)!!
        m.answer(a.id, false)
        assertNull("spacing", m.nextToAsk(now + 1000))
        now += MemoryManager.ASK_SPACING_MS + 1
        val b = m.nextToAsk(now)!!
        m.expire(b.id)
        assertEquals(emptyList<MemoryCandidate>(), m.view().second)
        assertEquals(0, backend.submitted.size)
    }

    @Test
    fun keyedConflictNeedsConfirmation() {
        backend.items += MemoryItem(3, "所在城市", "住在杭州")
        val m = manager()
        m.refreshSnapshot(0)
        val r = m.process(listOf("我住在成都"))
        assertEquals(listOf("住在成都"), r.pending.map { it.content })
        m.answer(m.nextToAsk(now)!!.id, true)
        assertEquals(listOf("住在成都"), backend.items.map { it.content })
        assertEquals(listOf("住在成都"), m.view().first.map { it.content })
    }

    @Test
    fun uploadFailureKeepsQueueUntilNextFlush() {
        backend.fail = true
        val m = manager()
        m.process(listOf("我喜欢听周杰伦的歌"))
        assertEquals(1, m.view().second.size)
        backend.fail = false
        m.flush()
        assertEquals(0, m.view().second.size)
        assertEquals(1, backend.items.size)
    }

    @Test
    fun pendingIsBoundedAndExpires() {
        val m = manager()
        m.process(listOf("我女儿叫朵朵", "我儿子叫乐乐", "我家的猫叫咪咪", "我喜欢喝绿茶"))
        assertEquals(MemoryManager.MAX_PENDING, m.view().second.size)
        now += MemoryManager.PENDING_TTL_MS + 1
        m.process(listOf("我喜欢听周杰伦的歌"))
        assertEquals(emptyList<MemoryCandidate>(), m.view().second.filter { it.state == "pending" })
    }

    @Test
    fun modelOnlySeesEligibleUtterancesRulesMissed() {
        modelFacts = listOf(MemoryFact(MemoryCategory.MUSIC, "喜欢听林俊杰的歌", source = MemoryFact.Source.MODEL))
        val m = manager(withModel = true)
        val r = m.process(listOf("我喜欢听周杰伦的歌", "林俊杰的歌我是真的爱听", "我有高血压", "播放稻香"))
        assertEquals(listOf(listOf("林俊杰的歌我是真的爱听")), modelRuns)
        assertEquals(setOf("喜欢听周杰伦的歌", "喜欢听林俊杰的歌"), r.facts.map { it.content }.toSet())
    }

    @Test
    fun modelDeferredWhenBusyOrHot() {
        allowModel = false
        val m = manager(withModel = true)
        val r = m.process(listOf("林俊杰的歌我是真的爱听"))
        assertEquals("deferred (busy/hot)", r.modelStatus)
        assertTrue(modelRuns.isEmpty())
    }

    @Test
    fun sessionWithoutStatementsDoesNothing() {
        val m = manager(withModel = true)
        m.onSessionEnded(listOf("播放晴天", "今天天气怎么样"))
        assertTrue(modelRuns.isEmpty())
        assertEquals(0, backend.submitted.size)
    }

    @Test
    fun favoriteArtistPrefersExplicitFavourite() {
        val m = manager()
        assertNull(m.favoriteArtist())
        m.process(listOf("我喜欢听周杰伦的歌"))
        assertEquals("周杰伦", m.favoriteArtist())
        m.process(listOf("我最喜欢的歌手是王菲"))
        assertEquals("王菲", m.favoriteArtist())
    }

    @Test
    fun parseAnswer() {
        listOf("是", "是的", "好的。", "可以", "嗯", "记住吧").forEach { assertEquals(it, true, MemoryManager.parseAnswer(it)) }
        listOf("不用", "不用了", "不要", "算了", "别记").forEach { assertEquals(it, false, MemoryManager.parseAnswer(it)) }
        listOf("播放周杰伦", "是谁唱的", "今天天气").forEach { assertNull(it, MemoryManager.parseAnswer(it)) }
    }
}
