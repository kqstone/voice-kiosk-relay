package com.voicekiosk.app.ondevice.route

/** Where a turn is answered. */
enum class Route { LOCAL, CLOUD }

/** Which layer produced a [RouteDecision]. */
enum class RouteSource(val label: String) { RULE("规则"), MODEL("模型") }

/**
 * Intent names shared by the L0 rules, the L1 model (same names as the routing grammar
 * in [com.voicekiosk.app.ondevice.BenchmarkPrompt]) and the local tool executor.
 */
object Intents {
    const val TIME = "time_query"
    const val WEATHER = "weather_query"
    const val MEDIA_PLAY = "media_play"
    const val MEDIA_CONTROL = "media_control"
    const val VOLUME = "volume_set"
    const val TIMER = "timer_set"
    const val BOARD = "board_switch"
    const val RESET = "session_reset"
    const val MEMORY = "memory_command"
    const val CHITCHAT = "chitchat"
    const val CLOUD_QUESTION = "cloud_question"
    const val QUESTION_ANSWER = "question_answer"
    const val APPROVAL_PIN = "approval_pin"
}

/**
 * One routing decision: an enumerated [intent] with validated-later string [slots].
 * [confidence] is 1.0-ish for rule matches and the model's self-reported value for L1.
 */
data class RouteDecision(
    val intent: String,
    val slots: Map<String, String> = emptyMap(),
    val route: Route,
    val confidence: Double,
    val source: RouteSource
) {
    /** Compact one-line state ("volume_set direction=up"), the format the L1 prompt uses. */
    fun stateLine(): String =
        (listOf(intent) + slots.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }).joinToString(" ")
}
