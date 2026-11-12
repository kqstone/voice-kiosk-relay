package com.voicekiosk.app.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionAnswerMatcherTest {
    private fun q(vararg options: String, kind: String = "choice") = OpenQuestion(
        id = "q1",
        prompt = "选一个",
        options = options.toList(),
        kind = kind
    )

    @Test
    fun chineseOrdinalsPickNth() {
        val question = q("晴天", "七里香", "稻香")
        assertEquals("晴天", QuestionAnswerMatcher.match("第一个", question)?.option)
        assertEquals("七里香", QuestionAnswerMatcher.match("第二个", question)?.option)
        assertEquals("稻香", QuestionAnswerMatcher.match("第3个", question)?.option)
        assertEquals("七里香", QuestionAnswerMatcher.match("第两 个", question)?.option)
        assertNull(QuestionAnswerMatcher.match("第四个", question))
    }

    @Test
    fun selectOptionTextFuzzyUnique() {
        val question = q("三年级二班", "四年级一班", "打印作业")
        assertEquals("三年级二班", QuestionAnswerMatcher.match("选三年级二班", question)?.option)
        assertEquals("三年级二班", QuestionAnswerMatcher.match("三年级", question)?.option)
        assertNull(QuestionAnswerMatcher.match("年级", question))
        assertEquals("打印作业", QuestionAnswerMatcher.match("选打印", question)?.option)
    }

    @Test
    fun shortTokenDoesNotFuzzyMatch() {
        val question = q("好天气", "坏天气")
        assertNull(QuestionAnswerMatcher.match("好", question))
    }

    @Test
    fun yesNoAndNegation() {
        val confirm = q("是", "不用", kind = "confirm")
        assertEquals("是", QuestionAnswerMatcher.match("是", confirm)?.option)
        assertEquals("是", QuestionAnswerMatcher.match("好的", confirm)?.option)
        assertEquals("是", QuestionAnswerMatcher.match("批准", confirm)?.option)
        assertEquals("不用", QuestionAnswerMatcher.match("不用", confirm)?.option)
        assertEquals("不用", QuestionAnswerMatcher.match("拒绝", confirm)?.option)
        assertNull(QuestionAnswerMatcher.match("不要第二个", q("甲", "乙", "丙")))
        assertNull(QuestionAnswerMatcher.match("别选第一个", q("甲", "乙")))
    }

    @Test
    fun letterAndExactMatch() {
        val question = q("甲", "乙", "丙")
        assertEquals("乙", QuestionAnswerMatcher.match("B", question)?.option)
        assertEquals("甲", QuestionAnswerMatcher.match("选A", question)?.option)
        assertEquals("丙", QuestionAnswerMatcher.match("丙", question)?.option)
    }

    @Test
    fun approvalVoiceNeverCarriesAnOption() {
        val pin = QuestionAnswerMatcher.matchApproval("批准")
        assertTrue(pin!!.approveVoice)
        assertFalse(pin.hasAnswer)
        val reject = QuestionAnswerMatcher.matchApproval("拒绝")
        assertTrue(reject!!.rejectVoice)
        assertNull(QuestionAnswerMatcher.matchApproval("第二个"))
    }
}
