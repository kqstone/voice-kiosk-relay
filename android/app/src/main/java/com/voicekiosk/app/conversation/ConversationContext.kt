package com.voicekiosk.app.conversation

import com.voicekiosk.app.ondevice.route.RouteDecision

/** Who answered a turn. */
enum class TurnSource(val label: String) {
    LOCAL_RULE("本地·规则"),
    LOCAL_MODEL("本地·模型"),
    CLOUD("云端")
}

/** One user turn and how it was answered. */
data class ConversationTurn(
    val seq: Long,
    val timeMs: Long,
    val userText: String,
    val reply: String,
    val source: TurnSource,
    /** Routing decision behind the turn (null for cloud turns the router did not classify). */
    val decision: RouteDecision?,
    /**
     * False for local turns the bridge has not seen yet. They go out as `local_turns` with
     * the next conversation turn (S4) and are marked synced once the bridge answers.
     */
    val syncedToCloud: Boolean,
    /** Stable id for bridge-side dedupe: "<context epoch>-<seq>". */
    val id: String = "t-$seq"
)

/**
 * The device-side conversation record (plan §4). S1 keeps it in memory for the
 * lifetime of the voice service: it gives L0 / L1 the previous turn's intent + slots
 * (ellipsis such as "再大点", "继续") and keeps local turns for S4's cloud sync.
 *
 * Expires after [idleTimeoutMs] without a turn (aligned with the bridge's 15-minute
 * short-term session) and keeps at most [maxTurns] turns. Thread-safe.
 */
class ConversationContext(
    private val clock: () -> Long = System::currentTimeMillis,
    private val idleTimeoutMs: Long = 15 * 60_000L,
    private val maxTurns: Int = 20,
    /** Prefix of turn ids; random per instance so a restarted service never reuses an id. */
    private val epoch: String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
) {
    private val turns = ArrayDeque<ConversationTurn>()
    private var nextSeq = 1L
    private var lastTurnMs = 0L

    /**
     * The user reset locally while the bridge was unreachable: the next conversation turn
     * asks the bridge to drop its short-term history first (`reset: true`). Cleared once sent OK.
     */
    @get:Synchronized @set:Synchronized
    var bridgeResetPending: Boolean = false

    @Synchronized
    fun record(userText: String, reply: String, source: TurnSource, decision: RouteDecision?): ConversationTurn {
        expireIfIdle()
        val seq = nextSeq++
        val t = ConversationTurn(seq, clock(), userText, reply, source, decision, source == TurnSource.CLOUD, "$epoch-$seq")
        turns.addLast(t)
        while (turns.size > maxTurns) turns.removeFirst()
        lastTurnMs = t.timeMs
        return t
    }

    /** Decision of the most recent turn, if any (null after expiry / reset). */
    @Synchronized
    fun lastDecision(): RouteDecision? {
        expireIfIdle()
        return turns.lastOrNull()?.decision
    }

    /** "无" or the previous turn's compact state, as the L1 prompt expects. */
    @Synchronized
    fun previousTurnLine(): String = lastDecision()?.stateLine() ?: "无"

    /**
     * Compact state of the last [n] turns, oldest first ("无" when empty), for the L1
     * prompt's "最近：" line. Cloud turns the router did not classify show as cloud_question.
     */
    @Synchronized
    fun recentLine(n: Int = 2): String {
        val recent = turns().takeLast(n)
        if (recent.isEmpty()) return "无"
        return recent.joinToString("；") { it.decision?.stateLine() ?: "cloud_question" }
    }

    @Synchronized
    fun turns(): List<ConversationTurn> { expireIfIdle(); return turns.toList() }

    /** Local turns the bridge has not seen (input for S4's `local_turns`). */
    @Synchronized
    fun unsyncedLocalTurns(): List<ConversationTurn> = turns().filter { !it.syncedToCloud }

    /** Marks turns as synced (the bridge answered the turn that carried them). */
    @Synchronized
    fun markSynced(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val set = ids.toSet()
        val updated = turns.map { if (!it.syncedToCloud && it.id in set) it.copy(syncedToCloud = true) else it }
        turns.clear()
        turns.addAll(updated)
    }

    /**
     * Clears the turn log, pending local turns included (bridge-side long-term memory is
     * never touched from here). Idle expiry does the same, matching the bridge's own
     * 15-minute session expiry.
     */
    @Synchronized
    fun reset() {
        turns.clear()
        lastTurnMs = 0L
    }

    companion object {
        /** Matches nas-bridge MAX_LOCAL_TURNS_PER_REQUEST. */
        const val MAX_PENDING_SENT = 10
    }

    private fun expireIfIdle() {
        if (turns.isNotEmpty() && clock() - lastTurnMs > idleTimeoutMs) reset()
    }
}
