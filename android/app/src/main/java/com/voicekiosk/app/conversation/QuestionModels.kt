package com.voicekiosk.app.conversation

import org.json.JSONObject

/** An open agent question the kiosk can answer by voice (generic; no scene data). */
data class OpenQuestion(
    val id: String,
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val kind: String = "choice",
    val allowFreeText: Boolean = false,
    /** Unix epoch seconds; 0 means unknown (queue uses a fallback TTL). */
    val expiresAt: Double = 0.0
) {
    companion object {
        fun parse(json: JSONObject): OpenQuestion? {
            val id = json.optString("id", json.optString("question_id", "")).trim()
            if (id.isEmpty()) return null
            val arr = json.optJSONArray("options")
            val options = if (arr != null) {
                (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
                }
            } else emptyList()
            return OpenQuestion(
                id = id,
                prompt = json.optString("prompt", "").trim(),
                options = options,
                kind = json.optString("kind", "choice").trim().ifEmpty { "choice" },
                allowFreeText = json.optBoolean("allow_free_text", false),
                expiresAt = json.optDouble("expires_at", 0.0)
            )
        }
    }
}

/** A pending approval the kiosk may only open a PIN pad for -- never decide by voice. */
data class OpenApproval(
    val id: String,
    val risk: String = "",
    val summary: String = ""
) {
    companion object {
        fun parse(json: JSONObject): OpenApproval? {
            val id = json.optString("id", "").trim()
            if (id.isEmpty()) return null
            return OpenApproval(
                id = id,
                risk = json.optString("risk", "").trim(),
                summary = json.optString("summary", "").trim()
            )
        }
    }
}

/** Local match of an utterance onto OpenQuestion options. */
data class QuestionMatch(
    val option: String? = null,
    val text: String? = null,
    val approveVoice: Boolean = false,
    val rejectVoice: Boolean = false
) {
    val isApprovalPin: Boolean get() = approveVoice || rejectVoice
    val hasAnswer: Boolean get() = !option.isNullOrEmpty() || !text.isNullOrEmpty()
}
