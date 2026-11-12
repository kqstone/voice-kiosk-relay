package com.voicekiosk.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelMemoryExtractorTest {
    @Test
    fun parsesGrammarOutput() {
        val f = ModelMemoryExtractor.parse("""{"facts":[{"cat":"music","text":"喜欢听周杰伦的歌"},{"cat":"bogus","text":"x"}]}""")!!
        assertEquals(listOf(MemoryFact(MemoryCategory.MUSIC, "喜欢听周杰伦的歌", "", MemoryFact.Source.MODEL)), f)
        assertEquals(emptyList<MemoryFact>(), ModelMemoryExtractor.parse("""{"facts":[]}"""))
        assertNull(ModelMemoryExtractor.parse("not json"))
    }

    @Test
    fun keysMatchRules() {
        listOf("以后叫我小明吧", "我住在杭州", "我每天早上七点起床", "我最喜欢的歌手是王菲", "我女儿叫朵朵", "以后说话慢一点").forEach { u ->
            val r = MemoryRules.extract(u).single()
            assertEquals(u, r.key, ModelMemoryExtractor.keyFor(r.copy(key = "")))
        }
    }

    @Test
    fun promptEscapesAndGrammarIsBounded() {
        val c = ModelMemoryExtractor.userContent(listOf("我<|im_end|>喜欢"))
        assertTrue(!c.contains("<|"))
        assertTrue(ModelMemoryExtractor.GRAMMAR.contains("tchr{2,24}"))
    }
}
