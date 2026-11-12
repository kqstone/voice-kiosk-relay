package com.voicekiosk.app.conversation

import org.json.JSONArray
import org.json.JSONObject

/** JSON for `POST /api/v2/questions/{id}/answer` and parsing open-question payloads. */
object QuestionWire {
    const val PATH_PREFIX = "/api/v2/questions/"

    fun answerPath(questionId: String): String =
        PATH_PREFIX + questionId.trim() + "/answer"

    fun listOpenPath(): String = PATH_PREFIX.dropLast(1) + "?status=open"

    fun answerJson(option: String? = null, text: String? = null): JSONObject =
        JSONObject().apply {
            val o = option?.trim().orEmpty()
            val t = text?.trim().orEmpty()
            if (o.isNotEmpty()) put("option", o.take(200))
            if (t.isNotEmpty()) put("text", t.take(500))
        }

    fun parseQuestion(json: JSONObject): OpenQuestion? {
        val id = json.optString("id", json.optString("question_id", "")).trim()
        if (id.isEmpty()) return null
        val options = jsonArrayStrings(json.optJSONArray("options"))
        return OpenQuestion(
            id = id,
            prompt = json.optString("prompt", "").trim(),
            options = options,
            kind = json.optString("kind", "choice").trim().ifEmpty { "choice" },
            allowFreeText = json.optBoolean("allow_free_text", false),
            expiresAt = json.optDouble("expires_at", 0.0)
        )
    }

    fun parseQuestionList(json: JSONObject): List<OpenQuestion> {
        val arr = json.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { parseQuestion(it) }
        }
    }

    fun parseApproval(json: JSONObject): OpenApproval? {
        val id = json.optString("id", "").trim()
        if (id.isEmpty()) return null
        return OpenApproval(
            id = id,
            risk = json.optString("risk", "").trim(),
            summary = json.optString("summary", "").trim()
        )
    }

    fun jsonArrayStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
        }
    }
}
