package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.conversation.AnswerQueue
import com.voicekiosk.app.conversation.CloudReply
import com.voicekiosk.app.conversation.CloudRequest
import com.voicekiosk.app.conversation.ConversationContext
import com.voicekiosk.app.conversation.OpenApproval
import com.voicekiosk.app.conversation.OpenQuestion
import com.voicekiosk.app.conversation.QuestionAnswerMatcher
import com.voicekiosk.app.conversation.QuestionMatch
import com.voicekiosk.app.conversation.TurnSource
import com.voicekiosk.app.ondevice.BenchmarkPrompt
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Turn routing (plan §1), S1 + S2/S3:
 *
 *     ASR text → L0 rules → confident local intent → local tool
 *                         → safe small talk → (HIGH, model ready, chitchat on) model reply
 *              → (L1 available) model intent with the last 1–2 turns as context
 *                 → route=local, confidence ≥ threshold, whitelisted intent, valid
 *                   slots, not a question → local tool
 *              → otherwise: bridge conversation turn, unchanged
 *
 * S4 continuity: every bridge call carries the local turns the bridge has not seen
 * ([CloudRequest.localTurns]); they are marked synced when the bridge answers, so cloud
 * follow-ups ("刚才那首是谁唱的") see local turns in order. A reset made while the bridge
 * was unreachable is replayed as [CloudRequest.resetSession] on the next call.
 *
 * [plan] decides without side effects (used by the route eval); [handle] plans and then
 * executes. Any failure (rule exception, model not ready / timeout / bad JSON, tool or
 * weather failure, unusable small-talk reply) falls back to the cloud. Cloud errors are
 * returned as [TurnResult.error] so the caller keeps the "offline: never speak a fake
 * answer" behaviour.
 */
class TurnOrchestrator(
    private val config: Config,
    private val rules: RuleRouter,
    private val classifier: IntentClassifier?,
    private val tools: LocalToolExecutor,
    val context: ConversationContext,
    private val cloud: (CloudRequest) -> Result<CloudReply>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val smallTalkReplies: SmallTalkReplies = SmallTalkReplies(),
    /** Runs speculative cloud requests ([Config.speculativeCloud]). */
    private val cloudExecutor: ExecutorService = DEFAULT_CLOUD_EXECUTOR,
    /** S5: voice confirmation of memory candidates ("要我记住…吗？" → 是/不用); null = off. */
    private val memory: com.voicekiosk.app.memory.MemoryConfirmations? = null,
    /** Currently displayed agent question (from `show_question` / dashboard JS). */
    private val openQuestion: () -> OpenQuestion? = { null },
    /** Currently displayed approval card; voice 批准 only opens the PIN pad. */
    private val openApproval: () -> OpenApproval? = { null },
    private val postAnswer: (OpenQuestion, QuestionMatch) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    /** Voice 批准/拒绝: open the on-screen PIN pad. Never posts an approval. */
    private val onApprovalVoice: (OpenApproval, approve: Boolean) -> Unit = { _, _ -> },
    private val answerQueue: AnswerQueue? = null
) {
    /** The memory candidate asked about at the end of the previous reply, and when. */
    @Volatile private var askedMemory: Pair<com.voicekiosk.app.memory.MemoryCandidate, Long>? = null

    data class Config(
        val modelRoutingEnabled: Boolean = true,
        /** Local replies to rule-recognised safe small talk (S3.1: fixed templates, all tiers). */
        val chitchatEnabled: Boolean = true,
        /**
         * S3 model-written small-talk replies instead of templates. Off: on the phone they took
         * ~1.8 s and read oddly ("谢谢你 → 你说的对…"); kept for experiments only.
         */
        val chitchatModel: Boolean = false,
        /**
         * S3.1 pre-gate: call the model only when [ModelGuards.mayBeLocal] (some whitelisted
         * intent could pass the post-guards). Everything else goes to the cloud at once.
         */
        val modelPreGate: Boolean = true,
        /**
         * Start the bridge request while the model runs; if the model wins with a local
         * action, the cloud reply is discarded (never spoken). Off by default: the bridge has
         * already appended that turn to its session history by then (a "ghost" turn the user
         * never heard), and there is no way to retract it. See docs/ONDEVICE-ROUTING.md.
         */
        val speculativeCloud: Boolean = false,
        val ruleMinConfidence: Double = 0.9,
        /**
         * The model reports 0.9–0.95 for clear commands; ±0.1 jitter was seen between
         * batched and single-token decoding (S0.2: 0.8 vs 0.9 on the same input), so this
         * stays clear of both. Tuned on the S2 route eval (docs/ONDEVICE-ROUTING.md).
         */
        val modelMinConfidence: Double = 0.85,
        val modelTimeoutMs: Long = 1500,
        val chitchatTimeoutMs: Long = 2500,
        /** Context turns passed to the model ("最近：…"). */
        val contextTurns: Int = 2
    )

    /** What happened in one turn (for the dashboard debug line, logs and the eval). */
    data class Trace(
        val ruleMs: Long,
        val rule: RouteDecision?,
        val modelStatus: String,
        val model: RouteDecision?,
        val modelMs: Long,
        val totalMs: Long,
        val modelRaw: String = "",
        /** Model invoked for this turn (after the pre-gate). */
        val modelInvoked: Boolean = false,
        /** A speculative cloud request was started / its reply was discarded. */
        val speculative: Boolean = false,
        val cloudDiscarded: Boolean = false
    )

    /** Routing decision for one utterance, before any side effect. */
    sealed class Plan {
        abstract val decision: RouteDecision?
        abstract val trace: Trace

        data class LocalTool(override val decision: RouteDecision, val source: TurnSource, override val trace: Trace) : Plan()
        data class SmallTalk(override val decision: RouteDecision, val reply: String, val source: TurnSource, override val trace: Trace) : Plan()
        data class Reset(override val decision: RouteDecision, override val trace: Trace) : Plan()
        data class Cloud(override val decision: RouteDecision?, override val trace: Trace) : Plan()

        val isLocal: Boolean get() = this !is Cloud
    }

    /**
     * @property reply text to speak (null on error)
     * @property error cloud failure message (offline notice; nothing is spoken)
     * @property route chosen route from bridge ("local" or "muse")
     * @property conversationId bridge conversation id for async Muse routes, if any
     */
    data class TurnResult(
        val reply: String?,
        val error: String?,
        val source: TurnSource,
        val decision: RouteDecision?,
        val trace: Trace,
        val route: String = "local",
        val conversationId: String? = null
    ) {
        val isSuccess: Boolean get() = reply != null
    }

    /**
     * Plans and executes; [Trace.totalMs] includes the tool / cloud call.
     *
     * S5: if the previous reply asked "要我记住…吗？", a yes/no answer is handled here (not
     * routed, not sent to the cloud); any other utterance or a late answer drops the
     * candidate (no answer = no). After a successful turn one pending candidate may be asked
     * about at the end of the reply.
     */
    fun handle(text: String): TurnResult {
        val t0 = clock()
        questionOrApprovalAnswer(text, t0)?.let { return it }
        memoryAnswer(text, t0)?.let { return it }
        val r = handleTurn(text, t0)
        return withMemoryQuestion(r)
    }

    /**
     * While a question or approval card is open, short answers stay on-device.
     * Approval voice never decides: it only asks the UI to show the PIN pad.
     */
    private fun questionOrApprovalAnswer(text: String, now: Long): TurnResult? {
        openApproval()?.let { ap ->
            val pin = QuestionAnswerMatcher.matchApproval(text)
            if (pin != null && pin.isApprovalPin) {
                onApprovalVoice(ap, pin.approveVoice)
                val d = RouteDecision(
                    Intents.APPROVAL_PIN,
                    mapOf("approval_id" to ap.id, "kind" to if (pin.approveVoice) "approve" else "reject"),
                    Route.LOCAL, 1.0, RouteSource.RULE
                )
                val reply = "请在屏幕上输入审批 PIN"
                return TurnResult(reply, null, TurnSource.LOCAL_RULE, d, Trace(0, d, "skipped (approval pin)", null, 0, clock() - now))
            }
        }
        val q = openQuestion() ?: return null
        val m = QuestionAnswerMatcher.match(text, q) ?: return null
        if (!m.hasAnswer) return null
        val posted = try { postAnswer(q, m) } catch (e: Exception) { Result.failure(e) }
        if (posted.isFailure) {
            val expMs = if (q.expiresAt > 0) (q.expiresAt * 1000.0).toLong() else now + AnswerQueue.FALLBACK_TTL_MS
            answerQueue?.enqueue(q.id, m.option, m.text, expMs)
            log("question answer queued (${posted.exceptionOrNull()?.message})")
        }
        val chosen = m.option ?: m.text.orEmpty()
        val reply = if (!m.option.isNullOrEmpty()) "好的，已选择$chosen" else "好的，已记录你的回答"
        val d = RouteDecision(
            Intents.QUESTION_ANSWER,
            mapOf("question_id" to q.id, "option" to chosen.take(24)),
            Route.LOCAL, 1.0, RouteSource.RULE
        )
        return TurnResult(reply, null, TurnSource.LOCAL_RULE, d, Trace(0, d, "skipped (question answer)", null, 0, clock() - now))
    }

    private fun memoryAnswer(text: String, now: Long): TurnResult? {
        val (c, at) = askedMemory ?: return null
        askedMemory = null
        val m = memory ?: return null
        val ans = if (now - at <= MEMORY_ANSWER_WINDOW_MS) com.voicekiosk.app.memory.MemoryManager.parseAnswer(text) else null
        if (ans == null) { m.expire(c.id); log("memory candidate dropped (no answer)"); return null }
        m.answer(c.id, ans)
        val d = RouteDecision(Intents.MEMORY, mapOf("answer" to if (ans) "yes" else "no"), Route.LOCAL, 1.0, RouteSource.RULE)
        val reply = if (ans) "好的，我记住了。" else "好的，不记。"
        return TurnResult(reply, null, TurnSource.LOCAL_RULE, d, Trace(0, d, "skipped (memory answer)", null, 0, clock() - now))
    }

    private fun withMemoryQuestion(r: TurnResult): TurnResult {
        val m = memory ?: return r
        if (r.reply == null || r.decision?.intent == Intents.RESET || r.decision?.intent == Intents.MEMORY ||
            r.decision?.intent?.startsWith("media_") == true) return r
        val c = m.nextToAsk(clock()) ?: return r
        askedMemory = c to clock()
        return r.copy(reply = r.reply.trimEnd() + " 顺便问一下，要我记住「${c.fact.content}」吗？")
    }

    private fun handleTurn(text: String, t0: Long): TurnResult {
        var spec: Future<Result<CloudReply>>? = null
        val p = plan(text) {
            if (config.speculativeCloud) spec = try { cloudExecutor.submit<Result<CloudReply>> { callCloud(text) } } catch (e: Exception) { null }
        }
        val s = spec
        val r = if (s == null) execute(text, p) else executeSpeculative(text, p, s)
        return r.copy(trace = r.trace.copy(totalMs = clock() - t0, speculative = s != null || r.trace.speculative))
    }

    /** With a speculative cloud request in flight: use it for any cloud outcome, discard it otherwise. */
    private fun executeSpeculative(text: String, p: Plan, spec: Future<Result<CloudReply>>): TurnResult {
        val await = { try { spec.get() } catch (e: Exception) { Result.failure<CloudReply>(e.cause ?: e) } }
        return when (p) {
            is Plan.Cloud -> cloudTurn(text, p.decision, p.trace, await())
            is Plan.LocalTool -> runTool(text, p)?.let { r ->
                // Never spoken; the bridge still keeps this turn in its history (documented).
                log("speculative cloud reply discarded (local ${p.decision.intent})")
                r.copy(trace = r.trace.copy(cloudDiscarded = true))
            } ?: cloudTurn(text, p.decision, p.trace.copy(modelStatus = p.trace.modelStatus + " (tool→cloud)"), await())
            else -> execute(text, p).let { r -> log("speculative cloud reply discarded"); r.copy(trace = r.trace.copy(cloudDiscarded = true)) }
        }
    }

    /** [onModelStart] runs right before the model is invoked (used for the speculative cloud call). */
    fun plan(text: String, onModelStart: () -> Unit = {}): Plan {
        val t0 = clock()
        val previous = context.lastDecision()
        val rule = try { rules.route(text, previous) } catch (e: Exception) { log("L0 error: ${e.message}"); null }
        val ruleMs = clock() - t0
        fun trace(status: String, model: RouteDecision? = null, modelMs: Long = 0, raw: String = "") =
            Trace(ruleMs, rule, status, model, modelMs, clock() - t0, raw)

        if (rule != null && rule.confidence >= config.ruleMinConfidence) {
            return when {
                rule.intent == Intents.RESET -> Plan.Reset(rule, trace("skipped"))
                rule.intent == Intents.CHITCHAT -> smallTalk(text, rule, ::trace)
                rule.route == Route.LOCAL && tools.canHandle(rule) -> Plan.LocalTool(rule, TurnSource.LOCAL_RULE, trace("skipped"))
                rule.route == Route.LOCAL -> Plan.Cloud(rule, trace("skipped (rule tool unavailable)"))
                // explicit cloud shortcut (memory commands, weather knowledge): skip the model
                else -> Plan.Cloud(rule, trace("skipped (rule→cloud)"))
            }
        }

        val clf = classifier
        if (!config.modelRoutingEnabled || clf == null) return Plan.Cloud(null, trace("off"))
        if (!clf.isReady) return Plan.Cloud(null, trace("not_ready"))
        if (config.modelPreGate && !ModelGuards.mayBeLocal(text, previous, MODEL_LOCAL_INTENTS)) return Plan.Cloud(null, trace("gated"))
        onModelStart()
        val res = try {
            clf.classify(text, context.recentLine(config.contextTurns), config.modelTimeoutMs)
        } catch (e: Exception) {
            ClassifyResult(null, "error: ${e.message}")
        }
        val d = res.decision
        val accepted = d?.let { acceptModel(text, it, previous) }
        if (accepted != null) {
            val tr = trace(res.status, d, res.ms, res.raw).copy(modelInvoked = true)
            return if (accepted.intent == Intents.RESET) Plan.Reset(accepted, tr) else Plan.LocalTool(accepted, TurnSource.LOCAL_MODEL, tr)
        }
        return Plan.Cloud(d, trace(res.status + if (d != null) " (→cloud)" else "", d, res.ms, res.raw).copy(modelInvoked = true))
    }

    private fun smallTalk(text: String, rule: RouteDecision, trace: (String, RouteDecision?, Long, String) -> Trace): Plan {
        if (!config.chitchatEnabled) return Plan.Cloud(rule, trace("off (small talk)", null, 0, ""))
        if (!config.chitchatModel) {
            val reply = smallTalkReplies.reply(rule.slots["kind"].orEmpty(), rule.slots["phrase"].orEmpty())
                ?: return Plan.Cloud(rule, trace("skipped (no template)", null, 0, ""))
            return Plan.SmallTalk(rule, reply, TurnSource.LOCAL_RULE, trace("skipped (template)", null, 0, ""))
        }
        val clf = classifier
        if (!config.modelRoutingEnabled || clf == null) return Plan.Cloud(rule, trace("off (small talk)", null, 0, ""))
        if (!clf.isReady) return Plan.Cloud(rule, trace("not_ready (small talk)", null, 0, ""))
        val res = try {
            clf.classify(text, context.recentLine(config.contextTurns), config.chitchatTimeoutMs, chitchat = true)
        } catch (e: Exception) {
            ClassifyResult(null, "error: ${e.message}")
        }
        val d = res.decision
        val reply = res.reply?.let { cleanReply(it) }
        if (d == null || reply == null || d.route != Route.LOCAL || d.confidence < config.modelMinConfidence) {
            return Plan.Cloud(rule, trace("${res.status} (small talk→cloud)", d, res.ms, res.raw))
        }
        return Plan.SmallTalk(d.copy(slots = rule.slots), reply, TurnSource.LOCAL_MODEL, trace(res.status, d, res.ms, res.raw).copy(modelInvoked = true))
    }

    /**
     * L1 local decisions need: route=local, confidence, an allowed intent, lexical evidence
     * and no question form ([ModelGuards], which also re-derives weather day/city from the
     * text), and valid slots. Returns the decision to run, or null → cloud.
     */
    private fun acceptModel(text: String, d: RouteDecision, previous: RouteDecision?): RouteDecision? {
        if (d.route != Route.LOCAL || d.confidence < config.modelMinConfidence) return null
        if (d.intent !in MODEL_LOCAL_INTENTS) return null
        val checked = ModelGuards.check(text, d, previous) ?: return null
        return checked.takeIf { it.intent == Intents.RESET || tools.canHandle(it) }
    }

    private fun execute(text: String, plan: Plan): TurnResult = when (plan) {
        is Plan.Reset -> reset(plan.decision, plan.trace)
        is Plan.SmallTalk -> {
            context.record(text, plan.reply, plan.source, plan.decision)
            TurnResult(plan.reply, null, plan.source, plan.decision, plan.trace)
        }
        is Plan.LocalTool -> runTool(text, plan) ?: cloudTurn(text, plan.decision, plan.trace.copy(modelStatus = plan.trace.modelStatus + " (tool→cloud)"))
        is Plan.Cloud -> cloudTurn(text, plan.decision, plan.trace)
    }

    private fun runTool(text: String, plan: Plan.LocalTool): TurnResult? {
        val d = plan.decision
        val r = try { tools.execute(d) } catch (e: Exception) { log("tool ${d.intent} failed: ${e.message}"); return null }
        if (r.fallbackToCloud || !r.handled) { log("tool ${d.intent} → cloud: ${r.reply}"); return null }
        context.record(text, r.reply, plan.source, d)
        return TurnResult(r.reply, null, plan.source, d, plan.trace.copy(totalMs = plan.trace.totalMs))
    }

    /**
     * "重新开始": clear the device context and forward the bridge's own reset command, so
     * both sides start over. If the bridge is unreachable the local reset still counts.
     */
    private fun reset(d: RouteDecision, trace: Trace): TurnResult {
        // Pending local turns go with the log: the bridge drops pre-reset turns anyway.
        context.reset()
        val r = cloud(CloudRequest(BRIDGE_RESET_TEXT)).map { it.reply }
        context.bridgeResetPending = r.isFailure
        return if (r.isSuccess) {
            TurnResult(r.getOrNull().orEmpty().trim().ifEmpty { LOCAL_RESET_REPLY }, null, TurnSource.CLOUD, d, trace)
        } else {
            log("bridge reset failed (will ask the bridge to reset on the next call): ${r.exceptionOrNull()?.message}")
            TurnResult(LOCAL_RESET_REPLY, null, if (d.source == RouteSource.RULE) TurnSource.LOCAL_RULE else TurnSource.LOCAL_MODEL, d, trace)
        }
    }

    /**
     * One bridge call with the pending local turns (newest [ConversationContext.MAX_PENDING_SENT]).
     * Once the bridge answers, every turn that was pending is marked synced: the bridge has
     * stored what it accepted (`acked_local_turn_ids`), rejected the rest on validation;
     * resending would not help and could reorder history.
     * On failure they stay pending and go out again next time (the bridge dedupes by id).
     */
    private fun callCloud(text: String): Result<CloudReply> {
        val allPending = context.unsyncedLocalTurns()
        val sent = allPending.takeLast(ConversationContext.MAX_PENDING_SENT)
        val resetFirst = context.bridgeResetPending
        val r = try { cloud(CloudRequest(text, sent, resetFirst)) } catch (e: Exception) { Result.failure(e) }
        r.getOrNull()?.let { reply ->
            if (resetFirst) context.bridgeResetPending = false
            context.markSynced(allPending.map { it.id })
            val acked = reply.ackedIds
            if (sent.isNotEmpty()) log("local_turns sent ${sent.size}, acked ${acked?.size ?: "n/a"}")
        }
        return r
    }

    private fun cloudTurn(text: String, d: RouteDecision?, trace: Trace, already: Result<CloudReply>? = null): TurnResult {
        val r = already ?: callCloud(text)
        val cloudReply = r.getOrNull()
        val reply = cloudReply?.reply?.trim()?.takeIf { it.isNotEmpty() }
        if (reply == null) {
            return TurnResult(null, r.exceptionOrNull()?.message ?: "服务未返回有效回答", TurnSource.CLOUD, d, trace)
        }
        // Keep the classified intent (if any) so ellipsis after a cloud turn ("那明天呢"
        // after a weather answer that had to go to the cloud) still has its state.
        context.record(text, reply, TurnSource.CLOUD, d)
        return TurnResult(
            reply = reply,
            error = null,
            source = TurnSource.CLOUD,
            decision = d,
            trace = trace,
            route = cloudReply.route,
            conversationId = cloudReply.conversationId
        )
    }

    companion object {
        private val DEFAULT_CLOUD_EXECUTOR: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "VoiceKiosk-SpecCloud").apply { isDaemon = true }
        }

        /** Intents the model may route locally (time stays rule-only: "纽约几点" etc.). */
        val MODEL_LOCAL_INTENTS = setOf(
            Intents.VOLUME, Intents.MEDIA_CONTROL, Intents.MEDIA_PLAY, Intents.TIMER, Intents.RESET, Intents.WEATHER
        )

        /** A memory question must be answered in the very next utterance within this window. */
        const val MEMORY_ANSWER_WINDOW_MS = 60_000L

        /** Exact phrase the bridge treats as a session reset (nas-bridge RESET_COMMANDS). */
        const val BRIDGE_RESET_TEXT = "重新开始"
        const val LOCAL_RESET_REPLY = "好的，我们重新开始吧。"

        private val CJK = Regex("[\\u4e00-\\u9fa5]")
        private val BAD_REPLY = Regex("(http|www|\\d|作为|AI|模型|无法|不能确定|建议你|医生)")

        /** Small-talk replies: 2..REPLY_MAX_CHARS chars, Chinese, no digits/links/advice. */
        fun cleanReply(raw: String): String? {
            val r = raw.trim()
            if (r.length !in 2..BenchmarkPrompt.REPLY_MAX_CHARS) return null
            if (!CJK.containsMatchIn(r) || BAD_REPLY.containsMatchIn(r)) return null
            return r
        }
    }
}
