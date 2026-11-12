package com.voicekiosk.app.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRulesTest {
    private fun one(u: String) = MemoryRules.extract(u).map { it.content to it.category }

    @Test
    fun extractsCanonicalFacts() {
        assertEquals(listOf("喜欢听周杰伦的歌" to MemoryCategory.MUSIC), one("我喜欢听周杰伦的歌"))
        assertEquals(listOf("喜欢听邓丽君的歌" to MemoryCategory.MUSIC), one("我特别喜欢听邓丽君。"))
        assertEquals(listOf("最喜欢的歌手是王菲" to MemoryCategory.MUSIC), one("我最喜欢的歌手是王菲"))
        assertEquals(listOf("希望被称呼为小明" to MemoryCategory.NICKNAME), one("以后叫我小明吧"))
        assertEquals(listOf("通常早上七点起床" to MemoryCategory.ROUTINE), one("我每天早上七点起床"))
        assertEquals(listOf("住在杭州" to MemoryCategory.CITY), one("我住在杭州"))
        assertEquals(listOf("希望说话慢一点" to MemoryCategory.DEVICE), one("以后说话慢一点"))
        assertEquals(listOf("女儿叫朵朵" to MemoryCategory.FAMILY), one("我女儿叫朵朵"))
        assertEquals(listOf("家里的猫叫咪咪" to MemoryCategory.FAMILY), one("我家的猫叫咪咪"))
        assertEquals(listOf("喜欢喝绿茶" to MemoryCategory.PREFERENCE), one("我喜欢喝绿茶"))
        // names may contain negation characters
        assertEquals(listOf("喜欢听毛不易的歌" to MemoryCategory.MUSIC), one("毛不易的歌我特别爱听"))
        assertEquals(listOf("最喜欢的歌手是毛不易" to MemoryCategory.MUSIC), one("我最喜欢的歌手是毛不易"))
        assertEquals(emptyList<Pair<String, MemoryCategory>>(), one("我不喜欢听毛不易的歌"))
    }

    @Test
    fun keysForSingleValuedFacts() {
        assertEquals("称呼", MemoryRules.extract("以后叫我小明吧").single().key)
        assertEquals("所在城市", MemoryRules.extract("我住在杭州").single().key)
        assertEquals("作息·起床", MemoryRules.extract("我每天早上七点起床").single().key)
        assertEquals("", MemoryRules.extract("我喜欢听周杰伦的歌").single().key)
    }

    @Test
    fun ignoresRequestsQuestionsNegationsAndHearsay() {
        listOf(
            "播放周杰伦的歌", "我想听周杰伦的歌", "你喜欢听什么歌", "我不喜欢听周杰伦的歌", "如果我住在北京就好了",
            "我朋友说他喜欢听陈奕迅", "记住我喜欢听周杰伦", "忘掉我喜欢听周杰伦", "叫我起床", "叫我一声",
            "我喜欢听歌", "提醒我每天晚上十点睡觉", "我住在火星", "我可能喜欢听周杰伦吧"
        ).forEach { assertEquals(it, emptyList<MemoryFact>(), MemoryRules.extract(it)) }
        assertTrue(MemoryRules.eligible("我特别喜欢听邓丽君"))
    }

    @Test
    fun sensitiveUtterancesAreNeverEligible() {
        listOf(
            "我的手机号是13812345678", "我的密码是abc12345", "我住在杭州市西湖区文三路100号", "我家在阳光小区三号楼",
            "我有高血压", "我月薪两万", "我的邮箱是someone@example.com", "我喜欢吃药膳", "我的车牌是浙A12345", "我女儿在实验小学读三年级二班"
        ).forEach {
            assertFalse(it, MemoryRules.eligible(it))
            assertTrue(it, SensitiveFilter.isSensitive(it))
        }
    }

    @Test
    fun validateRequiresTemplateAndGrounding() {
        val said = listOf("周杰伦的歌我是真的爱听")
        assertTrue(MemoryRules.validate(MemoryFact(MemoryCategory.MUSIC, "喜欢听周杰伦的歌"), said))
        assertFalse(MemoryRules.validate(MemoryFact(MemoryCategory.MUSIC, "喜欢听林俊杰的歌"), said)) // not said
        assertFalse(MemoryRules.validate(MemoryFact(MemoryCategory.MUSIC, "用户喜欢周杰伦"), said)) // wording
        assertFalse(MemoryRules.validate(MemoryFact(MemoryCategory.CITY, "住在周杰伦"), said)) // not a city
        assertFalse(MemoryRules.validate(MemoryFact(MemoryCategory.PREFERENCE, "喜欢吃药膳"), listOf("我喜欢吃药膳"))) // sensitive
        assertFalse(MemoryRules.validate(MemoryFact(MemoryCategory.DEVICE, "希望说话慢一点"), said)) // device pattern not said
        assertTrue(MemoryRules.validate(MemoryFact(MemoryCategory.ROUTINE, "通常十一点半睡觉"), listOf("我平时都是十一点半睡")))
    }
}

class MemoryRulesGroundingTest {
    @Test
    fun modelProposalsMustMatchWhatWasSaid() {
        fun ok(c: MemoryCategory, s: String, u: String) = MemoryRules.validate(MemoryFact(c, s), listOf(u))
        assertFalse(ok(MemoryCategory.FAMILY, "女儿叫上学", "我女儿去上学了"))
        assertFalse(ok(MemoryCategory.FAMILY, "家里的猫叫豆豆", "我家小狗叫豆豆"))
        assertTrue(ok(MemoryCategory.FAMILY, "家里的小狗叫豆豆", "我家小狗叫豆豆"))
        assertFalse(ok(MemoryCategory.PREFERENCE, "喜欢吃咖啡", "我特别喜欢喝咖啡"))
        assertFalse(ok(MemoryCategory.MUSIC, "喜欢听儿歌的歌", "我女儿喜欢听儿歌"))
        assertFalse(ok(MemoryCategory.ROUTINE, "通常晚上七点起床", "我每天早上七点起床"))
        assertFalse(MemoryRules.eligible("我同事住在上海"))
        assertFalse(MemoryRules.eligible("我今天晚上十一点睡觉"))
        assertFalse(MemoryRules.eligible("明天早上七点叫我起床"))
        assertEquals(
            "通常早上七点起床",
            MemoryRules.canonicalize(MemoryFact(MemoryCategory.ROUTINE, "通常七点起床"), listOf("我每天早上七点起床")).content
        )
    }
}
