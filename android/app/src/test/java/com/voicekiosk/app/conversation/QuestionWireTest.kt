package com.voicekiosk.app.conversation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionWireTest {
    @Test
    fun answerPathAndJson() {
        assertEquals("/api/v2/questions/q1/answer", QuestionWire.answerPath("q1"))
        val json = QuestionWire.answerJson("第二个", null)
        assertEquals("第二个", json.getString("option"))
        assertTrue(!json.has("text"))
    }

    @Test
    fun parseQuestionAndApproval() {
        val q = QuestionWire.parseQuestion(
            JSONObject("""{"id":"q9","prompt":"选班","options":["甲","乙"],"kind":"choice","expires_at":1700000100}""")
        )!!
        assertEquals("q9", q.id)
        assertEquals(listOf("甲", "乙"), q.options)
        assertEquals(1700000100.0, q.expiresAt, 0.01)
        assertNull(QuestionWire.parseQuestion(JSONObject("""{"prompt":"x"}""")))
        val a = QuestionWire.parseApproval(JSONObject("""{"id":"ap1","risk":"medium","summary":"打印"}"""))!!
        assertEquals("ap1", a.id)
        assertEquals("medium", a.risk)
    }
}
