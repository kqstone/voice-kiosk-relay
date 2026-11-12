package com.voicekiosk.app.conversation

/** An open agent question the kiosk can answer by voice (generic; no scene data). */
data class OpenQuestion(
    val id: String,
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val kind: String = "choice",
    val allowFreeText: Boolean = false,
    /** Unix epoch seconds; 0 means unknown (queue uses a fallback TTL). */
    val expiresAt: Double = 0.0
)

/** A pending approval the kiosk may only open a PIN pad for — never decide by voice. */
data class OpenApproval(
    val id: String,
    val risk: String = "",
    val summary: String = ""
)

/** Local match of an utterance onto [OpenQuestion] options. */
data class QuestionMatch(
    val option: String? = null,
    val text: String? = null,
    val approveVoice: Boolean = false,
    val rejectVoice: Boolean = false
) {
    val isApprovalPin: Boolean get() = approveVoice || rejectVoice
    val hasAnswer: Boolean get() = !option.isNullOrEmpty() || !text.isNullOrEmpty()
}
