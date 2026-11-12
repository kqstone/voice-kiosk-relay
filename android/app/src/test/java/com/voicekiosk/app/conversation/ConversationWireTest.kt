package com.voicekiosk.app.conversation

import com.voicekiosk.app.bridge.ErrorEnvelope
import com.voicekiosk.app.ondevice.route.Intents
import com.voicekiosk.app.ondevice.route.Route
import com.voicekiosk.app.ondevice.route.RouteDecision
import com.voicekiosk.app.ondevice.route.RouteSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationWireTest {
    private val play = RouteDecision(
        Intents.MEDIA_PLAY,
        mapOf("artist" to "周杰伦", "song" to "晴天"),
        Route.LOCAL,
        0.98,
        RouteSource.RULE
    )

    @Test
    fun requestJsonUsesV2FieldNames() {
        val ctx = ConversationContext(clock = { 1_000L }, epoch = "abc")
        val t = ctx.record("播放晴天", "好的，正在播放。", TurnSource.LOCAL_RULE, play)
        val req = CloudRequest("刚才那首是谁唱的", listOf(t), resetSession = true)
        val json = ConversationWire.requestJson(req, "default", nowMs = 5_000L)
        assertEquals("default", json.getString("session_id"))
        assertEquals("刚才那首是谁唱的", json.getString("text"))
        assertTrue(json.getBoolean("reset"))
        assertFalse(json.has("session"))
        assertFalse(json.has("reset_session"))
        val turns = json.getJSONArray("local_turns")
        assertEquals(1, turns.length())
        assertEquals(t.id, turns.getJSONObject(0).getString("id"))
        assertEquals(ConversationWire.PATH, "/api/v2/conversation/turns")
    }

    @Test
    fun parseReplyActionsAndAckedIds() {
        val json = JSONObject(
            """{"reply":"正在打开作业看板","speak":true,"actions":[{"type":"focus_board","board_id":"homework"},{"type":"show_question","question_id":"q1"},{"type":""}],"acked_local_turn_ids":["a","b"]}"""
        )
        val parsed = ConversationWire.parseReply(json)
        assertEquals("正在打开作业看板", parsed.reply)
        assertTrue(parsed.speak)
        assertEquals(listOf("a", "b"), parsed.ackedIds)
        assertEquals(2, parsed.actions.size)
        assertEquals(CloudAction("focus_board", "homework"), parsed.actions[0])
        assertEquals("show_question", parsed.actions[1].type)
        assertEquals("", parsed.actions[1].boardId)
        val rich = ConversationWire.parseReply(
            JSONObject(
                """{"reply":"选一个","actions":[{"type":"show_question","question_id":"q9","prompt":"选班","options":["甲","乙"],"kind":"choice","expires_at":1700000100}]}"""
            )
        )
        assertEquals("q9", rich.actions[0].questionId)
        assertEquals("选班", rich.actions[0].prompt)
        assertEquals(listOf("甲", "乙"), rich.actions[0].options)
        assertEquals(1700000100.0, rich.actions[0].expiresAt, 0.01)
    }

    @Test
    fun parseReplySpeakDefaultsTrueAndUnknownActionsKept() {
        val json = JSONObject("""{"reply":"hi","actions":[{"type":"task_started"}]}""")
        val parsed = ConversationWire.parseReply(json)
        assertTrue(parsed.speak)
        assertEquals(listOf(CloudAction("task_started")), parsed.actions)
        val silent = ConversationWire.parseReply(JSONObject("""{"reply":"x","speak":false}"""))
        assertFalse(silent.speak)
        assertEquals(emptyList<CloudAction>(), silent.actions)
        assertNull(silent.ackedIds)
        assertNull(LocalTurnsCodec.acked(JSONObject("""{"local_turns_acked":["old"]}""")))
    }

    @Test
    fun parseActionsSkipsMalformedEntries() {
        val arr = JSONArray("""[{"type":"focus_board","board_id":"a"},"nope",{"board_id":"x"},{"type":"focus_board","board_id":"b"}]""")
        val actions = ConversationWire.parseActions(arr)
        assertEquals(listOf(CloudAction("focus_board", "a"), CloudAction("focus_board", "b")), actions)
        assertEquals(emptyList<CloudAction>(), ConversationWire.parseActions(null))
    }

    @Test
    fun errorEnvelope401IsUnpaired() {
        val body = """{"error":{"code":"unauthorized","message":"认证失败，令牌无效"}}"""
        assertEquals(ErrorEnvelope.UNPAIRED_MESSAGE, ErrorEnvelope.userMessage(401, body))
        assertEquals("配对码已过期", ErrorEnvelope.userMessage(400, """{"error":{"code":"pairing_expired","message":"配对码已过期"}}"""))
        assertEquals("服务错误 (HTTP 502)", ErrorEnvelope.userMessage(502, "not-json"))
        assertEquals("上游超时", ErrorEnvelope.userMessage(426, """{"error":{"code":"upgrade","message":"上游超时"}}"""))
        val parsed = ErrorEnvelope.parse(body)!!
        assertEquals("unauthorized", parsed.code)
        assertEquals("认证失败，令牌无效", parsed.message)
        assertNull(ErrorEnvelope.parse("{"))
    }
}
