package com.voicekiosk.app.conversation

import com.voicekiosk.app.ondevice.route.Intents
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat request sent to POST /api/v1/chat.
 * Optional localTurns and resetSession kept for compatibility with callers
 * (such as TurnOrchestrator), but not sent over the wire in v1.
 */
data class CloudRequest(
    val text: String,
    val localTurns: List<ConversationTurn> = emptyList(),
    val resetSession: Boolean = false
)

/**
 * Chat reply from POST /api/v1/chat.
 *
 * @property reply Spoken/displayed reply text from the bridge.
 * @property route Route chosen by the bridge: "local" or "muse".
 * @property conversationId Conversation ID for async Muse responses, or null.
 * @property speak Whether TTS should speak reply (dashboard still shows it).
 * @property ackedIds Deprecated: kept for backwards compatibility with callers; always null on v1 bridge.
 */
data class CloudReply(
    val reply: String,
    val route: String = "local",
    val conversationId: String? = null,
    val speak: Boolean = true
) {
    val ackedIds: List<String>? get() = null

    constructor(reply: String, ackedIds: List<String>?) : this(reply = reply)
}

/** JSON wire codec for POST /api/v1/chat. */
object ConversationWire {
    const val PATH = "/api/v1/chat"

    fun requestJson(
        req: CloudRequest,
        sessionId: String? = null
    ): JSONObject = JSONObject().apply {
        put("text", req.text)
        if (!sessionId.isNullOrEmpty()) {
            put("session_id", sessionId)
        }
    }

    fun requestJson(
        req: CloudRequest,
        sessionId: String?,
        @Suppress("UNUSED_PARAMETER") nowMs: Long
    ): JSONObject = requestJson(req, sessionId)

    fun parseReply(json: JSONObject): CloudReply {
        val reply = json.optString("reply", "").trim()
        val route = json.optString("route", "local").trim().ifEmpty { "local" }
        val convId = if (json.has("conversation_id") && !json.isNull("conversation_id")) {
            json.optString("conversation_id", "").trim().takeIf { it.isNotEmpty() }
        } else null
        val speak = if (json.has("speak")) json.optBoolean("speak", true) else true
        return CloudReply(
            reply = reply,
            route = route,
            conversationId = convId,
            speak = speak
        )
    }
}

/**
 * JSON codec for local turns (retained for test compatibility).
 */
object LocalTurnsCodec {
    const val MAX_USER_CHARS = 200
    const val MAX_REPLY_CHARS = 300
    private val SLOT_VALUE_DROP = Regex("[\\s=]")

    fun toJson(turns: List<ConversationTurn>, nowMs: Long): JSONArray = JSONArray().apply {
        for (t in turns) {
            put(JSONObject().apply {
                put("id", t.id)
                put("user", t.userText.trim().take(MAX_USER_CHARS))
                put("reply", t.reply.trim().take(MAX_REPLY_CHARS))
                action(t)?.let { put("action", it) }
                put("age_ms", (nowMs - t.timeMs).coerceAtLeast(0))
            })
        }
    }

    fun action(t: ConversationTurn): String? {
        val d = t.decision ?: return null
        if (d.intent == Intents.CHITCHAT) return null
        if (!Regex("^[a-z_]{1,32}$").matches(d.intent)) return null
        val slots = d.slots.entries.sortedBy { it.key }
            .filter { Regex("^[a-z_]{1,16}$").matches(it.key) }
            .mapNotNull { (k, v) -> v.replace(SLOT_VALUE_DROP, "").take(24).takeIf { it.isNotEmpty() }?.let { "$k=$it" } }
            .take(4)
        return (listOf(d.intent) + slots).joinToString(" ")
    }

    fun acked(json: JSONObject): List<String>? {
        val arr = json.optJSONArray("acked_local_turn_ids") ?: return null
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotEmpty() } }
    }
}
