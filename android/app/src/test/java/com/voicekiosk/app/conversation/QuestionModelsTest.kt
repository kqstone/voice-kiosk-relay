package com.voicekiosk.app.conversation

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionModelsTest {
    @Test
    fun parseQuestionAndApproval() {
        val q = OpenQuestion.parse(
            JSONObject("""{"id":"q9","prompt":"选班","options":["甲","乙"],"kind":"choice","expires_at":1700000100}""")
        )!!
        assertEquals("q9", q.id)
        assertEquals(listOf("甲", "乙"), q.options)
        assertEquals(1700000100.0, q.expiresAt, 0.01)
        assertNull(OpenQuestion.parse(JSONObject("""{"prompt":"x"}""")))
        val a = OpenApproval.parse(JSONObject("""{"id":"ap1","risk":"medium","summary":"打印"}"""))!!
        assertEquals("ap1", a.id)
        assertEquals("medium", a.risk)
    }
}
