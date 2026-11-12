package com.voicekiosk.app.ondevice.eval

import com.voicekiosk.app.conversation.ConversationContext
import com.voicekiosk.app.conversation.TurnSource
import com.voicekiosk.app.ondevice.route.ClassifyResult
import com.voicekiosk.app.ondevice.route.IntentClassifier
import com.voicekiosk.app.ondevice.route.Intents
import com.voicekiosk.app.ondevice.route.Route
import com.voicekiosk.app.ondevice.route.RouteDecision
import com.voicekiosk.app.ondevice.route.RuleRouter
import com.voicekiosk.app.ondevice.route.TurnOrchestrator
import com.voicekiosk.app.ondevice.tools.City
import com.voicekiosk.app.ondevice.tools.DayForecast
import com.voicekiosk.app.ondevice.tools.Forecast
import com.voicekiosk.app.ondevice.tools.LocalPlatform
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import com.voicekiosk.app.ondevice.tools.MediaAction
import com.voicekiosk.app.ondevice.tools.PlaySearchResult
import com.voicekiosk.app.ondevice.tools.WeatherSource
import java.time.ZoneId
import java.util.Locale

/**
 * Route eval (S2): runs [RouteEvalSet] through the real L0 rules and orchestrator
 * planning (no tools executed, no network) with a given L1 classifier (the on-device
 * model, or null for rules only). Runs on the host (JNI + Qwen GGUF) and on the phone
 * (benchmark screen "路由评测").
 *
 * For every case the model is queried once directly (model-only metrics: intent
 * accuracy, JSON validity, latency) and that answer is replayed into the pipeline plan,
 * so pipeline metrics cost no second inference. Small-talk cases additionally run the
 * reply generation.
 */
class RouteEval(
    private val classifier: IntentClassifier?,
    private val config: TurnOrchestrator.Config = TurnOrchestrator.Config(),
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * true (host): query the model for every case, for model-only metrics. false (phone):
     * only when the pipeline itself would (after the pre-gate), so heat and time match use.
     */
    private val modelAllCases: Boolean = true
) {
    data class CaseResult(
        val case: RouteEvalSet.Case,
        val plannedLocal: Boolean,
        val plannedIntent: String?,
        val plannedSlots: Map<String, String>,
        val planSource: String,
        val model: RouteDecision?,
        val modelStatus: String,
        val modelMs: Long,
        val reply: String?,
        val replyMs: Long,
        val modelRaw: String,
        /** The pipeline invoked the model for this case (after the pre-gate). */
        val pipelineInvokedModel: Boolean = false,
        val thermal: String = "",
        val suffixTokens: Int = 0,
        val generatedTokens: Int = 0
    ) {
        val falseLocal get() = !case.local && plannedLocal
        val missedLocal get() = case.local && !plannedLocal
        val wrongAction get() = case.local && plannedLocal && !(plannedIntent == case.intent && slotsMatch(case, plannedSlots))
        val correct get() = !falseLocal && !missedLocal && !wrongAction
        val modelIntentOk get() = model?.intent == case.intent ||
            (!case.local && model != null && model.route == Route.CLOUD && model.intent in CLOUD_INTENTS)
        /** The raw model alone would have gone local on a must-cloud case. */
        val modelFalseLocal get() = !case.local && model != null && model.route == Route.LOCAL && model.confidence >= MIN_REPORT_CONF

        fun line(): String = "${case.id} 「${case.text}」 expect " + (if (case.local) "local:${case.intent}${fmt(case.slots)}" else "cloud") +
            " got " + (if (plannedLocal) "local:$plannedIntent${fmt(plannedSlots)} via $planSource" else "cloud") +
            " | model ${model?.let { "${it.stateLine()} ${it.route.name.lowercase()} ${it.confidence}" } ?: modelStatus} ${modelMs}ms" +
            (if (case.history.isNotEmpty()) " | history ${case.history.joinToString("；") { it.stateLine() }}" else "")
    }

    data class Report(val results: List<CaseResult>, val modelLabel: String, val wallMs: Long) {
        private val cloudCases get() = results.filter { !it.case.local }
        private val localCases get() = results.filter { it.case.local }
        val accuracy get() = results.count { it.correct }.toDouble() / results.size
        val falseLocal get() = results.filter { it.falseLocal }
        val falseLocalRate get() = falseLocal.size.toDouble() / cloudCases.size.coerceAtLeast(1)
        val wrongAction get() = results.filter { it.wrongAction }
        val missedLocal get() = results.filter { it.missedLocal }
        val localRecall get() = localCases.count { it.plannedLocal && !it.wrongAction }.toDouble() / localCases.size.coerceAtLeast(1)
        val ruleCoverage get() = localCases.count { it.planSource == "rule" && it.plannedLocal }.toDouble() / localCases.size.coerceAtLeast(1)
        val modelTimes get() = results.filter { it.model != null || it.modelStatus != "off" }.map { it.modelMs }.filter { it > 0 }.sorted()
        val invoked get() = results.filter { it.pipelineInvokedModel }
        /** Sequential pipeline: model time cloud questions wait before the bridge call, averaged over all cloud cases. */
        val addedCloudLatencyMs get() = cloudCases.filter { it.pipelineInvokedModel }.sumOf { it.modelMs }.toDouble() / cloudCases.size.coerceAtLeast(1)
        val replyTimes get() = results.map { it.replyMs }.filter { it > 0 }.sorted()

        fun summary(): String = buildString {
            val loc = Locale.US
            appendLine("route eval  model=$modelLabel  cases=${results.size} (local ${localCases.size}, cloud ${cloudCases.size})  wall=${wallMs / 1000}s")
            appendLine(String.format(loc, "pipeline accuracy %.1f%%  false-local %d/%d (%.1f%%)  wrong-action %d  missed-local %d  local recall %.1f%%  L0 coverage %.1f%%",
                accuracy * 100, falseLocal.size, cloudCases.size, falseLocalRate * 100, wrongAction.size, missedLocal.size, localRecall * 100, ruleCoverage * 100))
            if (modelTimes.isNotEmpty()) {
                val valid = results.count { it.model != null }
                appendLine(String.format(loc, "model: json valid %d/%d  intent acc %.1f%%  raw false-local %d  latency p50 %d ms  p90 %d ms  max %d ms",
                    valid, results.size, results.count { it.modelIntentOk } * 100.0 / results.size,
                    results.count { it.modelFalseLocal }, pct(modelTimes, 50), pct(modelTimes, 90), modelTimes.last()))
            }
            if (replyTimes.isNotEmpty()) appendLine("chitchat reply latency p50 ${pct(replyTimes, 50)} ms  p90 ${pct(replyTimes, 90)} ms")
            val inv = invoked
            val invCloud = cloudCases.count { it.pipelineInvokedModel }
            appendLine(String.format(loc, "pipeline model invocations %d/%d (%.1f%%): local cases %d/%d, cloud cases %d/%d  → expected added latency per cloud question %.0f ms (sequential)",
                inv.size, results.size, inv.size * 100.0 / results.size.coerceAtLeast(1),
                localCases.count { it.pipelineInvokedModel }, localCases.size, invCloud, cloudCases.size, addedCloudLatencyMs))
            val invTimes = inv.map { it.modelMs }.filter { it > 0 }.sorted()
            if (invTimes.isNotEmpty()) {
                appendLine("invoked model latency p50 ${pct(invTimes, 50)} ms  p90 ${pct(invTimes, 90)} ms  timeouts ${inv.count { it.modelStatus == "timeout" }}/${inv.size}")
            }
            val withTok = results.filter { it.suffixTokens > 0 }
            if (withTok.isNotEmpty()) {
                appendLine(String.format(loc, "tokens: suffix avg %.1f (min %d, max %d)  generated avg %.1f (max %d)",
                    withTok.map { it.suffixTokens }.average(), withTok.minOf { it.suffixTokens }, withTok.maxOf { it.suffixTokens },
                    withTok.map { it.generatedTokens }.average(), withTok.maxOf { it.generatedTokens }))
            }
            val byThermal = results.filter { it.thermal.isNotEmpty() && it.modelMs > 0 }.groupBy { it.thermal }
            if (byThermal.isNotEmpty()) {
                appendLine("model latency by thermal state:")
                byThermal.forEach { (t, rs) ->
                    val ts = rs.map { it.modelMs }.sorted()
                    appendLine("  %-10s n=%-3d p50 %d ms  p90 %d ms  timeouts %d".format(loc, t, rs.size, pct(ts, 50), pct(ts, 90), rs.count { it.modelStatus == "timeout" }))
                }
            }
            appendLine("per category (pipeline ok / n | model intent ok):")
            results.groupBy { it.case.category }.forEach { (cat, rs) ->
                appendLine(String.format(loc, "  %-20s %3d/%-3d | %3d", cat, rs.count { it.correct }, rs.size, rs.count { it.modelIntentOk }))
            }
            appendLine("per intent (model intent ok / n):")
            results.groupBy { it.case.intent }.forEach { (intent, rs) ->
                appendLine(String.format(loc, "  %-16s %3d/%-3d", intent, rs.count { it.modelIntentOk }, rs.size))
            }
            fun list(title: String, rs: List<CaseResult>) {
                if (rs.isEmpty()) return
                appendLine("$title:")
                rs.forEach { appendLine("  ${it.line()}") }
            }
            list("FALSE-LOCAL (must be empty)", falseLocal)
            list("wrong action", wrongAction)
            list("missed local", missedLocal)
            val replies = results.filter { it.reply != null }
            if (replies.isNotEmpty()) {
                appendLine("chitchat replies:")
                replies.forEach { appendLine("  ${it.case.text} → ${it.reply} (${it.replyMs} ms)") }
            }
        }

        fun tsv(): String = buildString {
            appendLine("id\ttext\texpect\tplanned\tsource\tmodel\tmodel_ms\tinvoked\tthermal\tsuffix_tok\tgen_tok\tok")
            results.forEach {
                appendLine(listOf(it.case.id, it.case.text, (if (it.case.local) "local:" else "cloud:") + it.case.intent + fmt(it.case.slots),
                    (if (it.plannedLocal) "local:" else "cloud:") + (it.plannedIntent ?: "-") + fmt(it.plannedSlots), it.planSource,
                    it.model?.stateLine() ?: it.modelStatus, it.modelMs, if (it.pipelineInvokedModel) "y" else "n", it.thermal.ifEmpty { "-" },
                    it.suffixTokens, it.generatedTokens, if (it.correct) "ok" else "FAIL").joinToString("\t"))
            }
        }
    }

    fun run(
        cases: List<RouteEvalSet.Case> = RouteEvalSet.CASES,
        modelLabel: String = "none",
        cancelled: () -> Boolean = { false },
        /** Called before each case (phone: cool down / wait for thermal); returns the thermal state to record. */
        beforeCase: () -> String = { "" },
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Report {
        val t0 = clock()
        val out = ArrayList<CaseResult>()
        for ((i, c) in cases.withIndex()) {
            if (cancelled()) break
            onProgress(i, cases.size)
            val thermal = beforeCase()
            if (cancelled()) break
            out += runCase(c).copy(thermal = thermal)
        }
        return Report(out, modelLabel, clock() - t0)
    }

    fun runCase(c: RouteEvalSet.Case): CaseResult {
        val ctx = ConversationContext()
        c.history.forEach { ctx.record("(历史)", "(回答)", if (it.route == Route.LOCAL) TurnSource.LOCAL_RULE else TurnSource.CLOUD, it) }
        val recent = ctx.recentLine(config.contextTurns)
        fun ask(): ClassifyResult? = classifier?.takeIf { it.isReady }?.let { clf ->
            try { clf.classify(c.text, recent, config.modelTimeoutMs) } catch (e: Exception) { ClassifyResult(null, "error: ${e.message}") }
        }
        var direct: ClassifyResult? = if (modelAllCases) ask() else null
        var replyMs = 0L
        var invoked = false
        val replay = classifier?.let { real ->
            object : IntentClassifier {
                override val isReady: Boolean get() = real.isReady
                override fun classify(text: String, recent: String, timeoutMs: Long, chitchat: Boolean): ClassifyResult {
                    invoked = true
                    if (chitchat) return real.classify(text, recent, timeoutMs, true).also { replyMs = it.ms }
                    if (direct == null) direct = ask()
                    return direct ?: ClassifyResult(null, "not_ready")
                }
            }
        }
        val orch = TurnOrchestrator(config, RuleRouter(), replay, tools, ctx, { Result.failure<com.voicekiosk.app.conversation.CloudReply>(IllegalStateException("eval")) }, clock)
        val plan = orch.plan(c.text)
        val d = plan.decision
        val source = when {
            plan is TurnOrchestrator.Plan.Cloud -> "cloud"
            plan.trace.rule != null && plan.trace.rule!!.confidence >= config.ruleMinConfidence &&
                !(plan is TurnOrchestrator.Plan.SmallTalk && plan.source == TurnSource.LOCAL_MODEL) -> "rule"
            else -> "model"
        }
        return CaseResult(
            c, plan.isLocal, if (plan.isLocal) d?.intent else d?.intent, if (plan.isLocal) d?.slots.orEmpty() else emptyMap(),
            source, direct?.decision, direct?.status ?: "off", direct?.ms ?: 0,
            (plan as? TurnOrchestrator.Plan.SmallTalk)?.reply, replyMs, direct?.raw.orEmpty(),
            pipelineInvokedModel = invoked, suffixTokens = direct?.suffixTokens ?: 0, generatedTokens = direct?.generatedTokens ?: 0
        )
    }

    private val tools = LocalToolExecutor(EvalPlatform, EvalWeather) { City("上海", 31.23, 121.47) }

    /** Side-effect-free platform: plan() only validates, but keep it harmless anyway. */
    private object EvalPlatform : LocalPlatform {
        override fun nowMillis() = 0L
        override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun volume() = 7 to 15
        override fun setVolumeIndex(index: Int) {}
        override fun setMuted(muted: Boolean) {}
        override fun isMuted() = false
        override fun sendMediaAction(action: MediaAction) = true
        override fun playFromSearch(query: String, artist: String?, song: String?) = PlaySearchResult.STARTED to "eval"
        override fun startTimer(seconds: Int, label: String?) = true
        override fun cancelTimers() = 0
    }

    private object EvalWeather : WeatherSource {
        override fun forecast(city: City) = Result.success(Forecast(city.name, 10.0, 1, listOf(DayForecast("d", 1, 12.0, 5.0, 10))))
        override fun geocode(name: String) = Result.success<City?>(City(name, 0.0, 0.0))
    }

    companion object {
        const val MIN_REPORT_CONF = 0.85
        val CLOUD_INTENTS = setOf(Intents.CLOUD_QUESTION, Intents.MEMORY, Intents.BOARD)

        fun pct(sorted: List<Long>, p: Int): Long = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p / 100.0).toInt()]

        private fun fmt(s: Map<String, String>) = if (s.isEmpty()) "" else s.entries.joinToString(",", "{", "}") { "${it.key}=${it.value}" }

        private fun num(v: String?) = v?.trim()?.toDoubleOrNull()

        /** Expected slots must match; weather day defaults to today; play names match on either key. */
        fun slotsMatch(c: RouteEvalSet.Case, got: Map<String, String>): Boolean {
            if (c.intent == Intents.WEATHER) {
                val day = got["day"] ?: "today"
                if (day != (c.slots["day"] ?: "today")) return false
                val city = got["city"]?.removeSuffix("市")
                if (city != c.slots["city"]) return false
                return true
            }
            if (c.intent == Intents.MEDIA_PLAY) {
                val values = got.values.map { it.trim() }
                return c.slots.values.all { v -> values.any { it == v || it.contains(v) } }
            }
            if (c.intent == Intents.TIMER && c.slots["minutes"] != null) {
                val want = num(c.slots["minutes"])!! * 60
                val have = num(got["seconds"]) ?: num(got["minutes"])?.times(60)
                return have == want
            }
            if (c.intent == Intents.VOLUME && c.slots["level"] == "100" && got["direction"] == "up" && got["level"] == null) return false
            return c.slots.all { (k, v) ->
                val g = got[k]
                g == v || (num(g) != null && num(g) == num(v))
            }
        }
    }
}
