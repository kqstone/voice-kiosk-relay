package com.voicekiosk.app.conversation

import com.voicekiosk.app.bridge.ErrorEnvelope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWireTest {
    @Test
    fun requestJsonUsesV1EndpointAndFields() {
        val req = CloudRequest("今天有什么作业")
        val json = ConversationWire.requestJson(req, "default")
        assertEquals("default", json.getString("session_id"))
        assertEquals("今天有什么作业", json.getString("text"))
        assertFalse(json.has("reset"))
        assertFalse(json.has("local_turns"))
        assertEquals("/api/v1/chat", ConversationWire.PATH)
    }

    @Test
    fun parseReplyLocalAndMuseRoutes() {
        val localJson = JSONObject("""{"reply":"上海今天晴","route":"local"}""")
        val localParsed = ConversationWire.parseReply(localJson)
        assertEquals("上海今天晴", localParsed.reply)
        assertEquals("local", localParsed.route)
        assertNull(localParsed.conversationId)
        assertTrue(localParsed.speak)

        val museJson = JSONObject("""{"reply":"好，我去问问 Muse，稍等一下。","route":"muse","conversation_id":"c-123"}""")
        val museParsed = ConversationWire.parseReply(museJson)
        assertEquals("好，我去问问 Muse，稍等一下。", museParsed.reply)
        assertEquals("muse", museParsed.route)
        assertEquals("c-123", museParsed.conversationId)
        assertTrue(museParsed.speak)
    }

    @Test
    fun parseReplySpeakFlag() {
        val silent = ConversationWire.parseReply(JSONObject("""{"reply":"x","route":"local","speak":false}"""))
        assertFalse(silent.speak)
        assertEquals("x", silent.reply)
    }

    @Test
    fun errorEnvelope401And403IsUnpaired() {
        val body401 = """{"detail":"Missing Authorization header"}"""
        assertEquals(ErrorEnvelope.UNPAIRED_MESSAGE, ErrorEnvelope.userMessage(401, body401))
        val body403 = """{"detail":"Invalid device token"}"""
        assertEquals(ErrorEnvelope.UNPAIRED_MESSAGE, ErrorEnvelope.userMessage(403, body403))
        assertEquals("服务错误 (HTTP 502)", ErrorEnvelope.userMessage(502, "not-json"))
        val parsed = ErrorEnvelope.parse("""{"error":{"code":"err1","message":"测试错误"}}""")!!
        assertEquals("err1", parsed.code)
        assertEquals("测试错误", parsed.message)
        assertNull(ErrorEnvelope.parse("{"))
    }
}
