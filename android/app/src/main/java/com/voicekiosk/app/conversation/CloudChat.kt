package com.voicekiosk.app.conversation

import com.voicekiosk.app.ondevice.route.Intents
import org.json.JSONArray
import org.json.JSONObject

/**
 * One `POST /api/v2/conversation/turns` call: the utterance plus the local turns the
 * bridge has not seen, and whether the bridge should first drop its short-term history
 * (a reset made while it was unreachable).
 */
data class CloudRequest(
    val text: String,
    val localTurns: List<ConversationTurn> = emptyList(),
    val resetSession: Boolean = false
)

data class CloudAction(
    val type: String,
    val boardId: String = "",
    val questionId: String = "",
    val taskId: String = "",
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val kind: String = "",
    val expiresAt: Double = 0.0
)

/**
 * @property ackedIds ids the bridge reported as stored (`acked_local_turn_ids`); null when
 *   the field is absent or malformed.
 * @property speak whether TTS should play [reply] (dashboard still shows it).
 */
data class CloudReply(
    val reply: String,
    val ackedIds: List<String>? = null,
    val speak: Boolean = true,
    val actions: List<CloudAction> = emptyList()
)

/** JSON for `POST /api/v2/conversation/turns` (JVM-testable). */
object ConversationWire {
    const val PATH = "/api/v2/conversation/turns"
    const val ACTION_FOCUS_BOARD = "focus_board"
    const val ACTION_SHOW_QUESTION = "show_question"
    const val ACTION_TASK_STARTED = "task_started"
    const val ACTION_QUESTION_ANSWERED = "question_answered"

    fun requestJson(
        req: CloudRequest,
        sessionId: String,
        nowMs: Long
    ): JSONObject = JSONObject().apply {
        put("session_id", sessionId)
        put("text", req.text)
        put("local_turns", LocalTurnsCodec.toJson(req.localTurns, nowMs))
        put("reset", req.resetSession)
    }

    fun parseReply(json: JSONObject): CloudReply {
        val reply = json.optString("reply", "").trim()
        val speak = if (json.has("speak")) json.optBoolean("speak", true) else true
        return CloudReply(
            reply = reply,
            ackedIds = LocalTurnsCodec.acked(json),
            speak = speak,
            actions = parseActions(json.optJSONArray("actions"))
        )
    }

    fun parseActions(arr: JSONArray?): List<CloudAction> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = o.optString("type", "").trim()
            if (type.isEmpty()) return@mapNotNull null
            CloudAction(
                type = type,
                boardId = o.optString("board_id", "").trim(),
                questionId = o.optString("question_id", "").trim(),
                taskId = o.optString("task_id", "").trim(),
                prompt = o.optString("prompt", "").trim(),
                options = QuestionWire.jsonArrayStrings(o.optJSONArray("options")),
                kind = o.optString("kind", "").trim(),
                expiresAt = o.optDouble("expires_at", 0.0)
            )
        }
    }
}

/**
 * JSON for `local_turns`: only what the cloud would have seen had the turn gone there
 * (the utterance) plus the spoken local reply and a compact action summary — no device
 * state, ids beyond the turn id, or memory. Limits mirror nas-bridge (which re-checks).
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

    /** "media_play artist=周杰伦 song=晴天"; null for small talk / no decision. */
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

    /** Parses `acked_local_turn_ids`; null if absent or malformed. */
    fun acked(json: JSONObject): List<String>? {
        val arr = json.optJSONArray("acked_local_turn_ids") ?: return null
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotEmpty() } }
    }
}
