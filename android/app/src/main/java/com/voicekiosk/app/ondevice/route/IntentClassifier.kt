package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.ondevice.BenchmarkPrompt
import com.voicekiosk.app.ondevice.LlmEngine
import com.voicekiosk.app.ondevice.PromptFormat
import com.voicekiosk.app.ondevice.RoutingDefaults
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** L1 result. [decision] is null when the model was unavailable or its answer unusable. */
data class ClassifyResult(
    val decision: RouteDecision?,
    val status: String,
    val raw: String = "",
    val ms: Long = 0,
    /** Small-talk reply (only with chitchat = true). */
    val reply: String? = null,
    /** Engine stats (0 when unknown): suffix prompt tokens, generated / grammar-forced tokens. */
    val suffixTokens: Int = 0,
    val generatedTokens: Int = 0,
    val forcedTokens: Int = 0
)

/** L1: on-device model NLU. */
interface IntentClassifier {
    /** True once the model is loaded and its prefix cached; false while loading or after failure. */
    val isReady: Boolean

    /**
     * [recent] is the "最近：" state line ([com.voicekiosk.app.conversation.ConversationContext.recentLine]).
     * With [chitchat] the small-talk grammar is used and [ClassifyResult.reply] is filled.
     * Must return within about [timeoutMs]; never throws for model errors.
     */
    fun classify(text: String, recent: String, timeoutMs: Long, chitchat: Boolean = false): ClassifyResult
}

/** Parses the grammar-constrained routing JSON. Unknown intents / routes → null. */
object RouteJson {
    fun reply(raw: String): String? = try { JSONObject(raw.trim()).optString("reply", "").trim().ifEmpty { null } } catch (_: Exception) { null }

    fun parse(raw: String): RouteDecision? = try {
        val o = JSONObject(raw.trim())
        val intent = o.getString("intent")
        if (intent !in BenchmarkPrompt.INTENTS) null else {
            val route = when (o.getString("route")) { "local" -> Route.LOCAL; "cloud" -> Route.CLOUD; else -> null }
            val conf = o.optDouble("confidence", Double.NaN)
            if (route == null || conf.isNaN() || conf !in 0.0..1.0) null else {
                val slotsObj = o.optJSONObject("slots")
                val slots = mutableMapOf<String, String>()
                slotsObj?.keys()?.forEach { k -> slots[k] = slotsObj.get(k).toString().trim() }
                RouteDecision(intent, slots, route, conf, RouteSource.MODEL)
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * [IntentClassifier] over an [LlmEngine] that already has the routing prompt prefix
 * cached (see [RouterModelHost]). Same prompt, grammar and decoding as the S0.2
 * benchmark "default" config (prefix-cached, greedy, fast-forward on).
 */
class ModelIntentClassifier(
    private val engine: LlmEngine,
    private val format: PromptFormat,
    private val ready: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) : IntentClassifier {

    override val isReady: Boolean get() = ready() && engine.isLoaded

    override fun classify(text: String, recent: String, timeoutMs: Long, chitchat: Boolean): ClassifyResult {
        if (!isReady) return ClassifyResult(null, "not_ready")
        val t0 = clock()
        val user = BenchmarkPrompt.userContent(sanitize(recent), sanitize(text))
        val future = engine.generate(
            LlmEngine.GenerateRequest(
                prompt = format.suffix(user),
                prefixMode = LlmEngine.PrefixMode.CACHED,
                maxTokens = if (chitchat) BenchmarkPrompt.REPLY_MAX_TOKENS else RoutingDefaults.MAX_TOKENS,
                grammar = if (chitchat) BenchmarkPrompt.CHITCHAT_GRAMMAR else BenchmarkPrompt.GRAMMAR,
                temperature = 0f,
                timeoutMs = timeoutMs,
                fastForward = RoutingDefaults.FAST_FORWARD
            )
        )
        val g = try {
            future.get(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            engine.cancel()
            return ClassifyResult(null, "timeout", ms = clock() - t0)
        } catch (e: Exception) {
            return ClassifyResult(null, "error: ${e.message}", ms = clock() - t0)
        }
        val ms = clock() - t0
        val sfx = if (g.suffixTokens > 0) g.suffixTokens else g.promptTokens
        if (!g.isSuccess) return ClassifyResult(null, g.status.name.lowercase(), g.text, ms, null, sfx, g.generatedTokens, g.forcedTokens)
        val d = RouteJson.parse(g.text) ?: return ClassifyResult(null, "bad_json", g.text, ms, null, sfx, g.generatedTokens, g.forcedTokens)
        return ClassifyResult(d, "ok", g.text, ms, if (chitchat) RouteJson.reply(g.text) else null, sfx, g.generatedTokens, g.forcedTokens)
    }

    companion object {
        const val GRACE_MS = 300L

        /** Keeps user text from closing the chat turn early (ASR never emits these, but be safe). */
        fun sanitize(s: String): String = s.replace("<|", "<").replace("|>", ">").replace("\n", " ").trim().take(160)
    }
}
