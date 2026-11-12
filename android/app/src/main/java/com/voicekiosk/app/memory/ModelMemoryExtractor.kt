package com.voicekiosk.app.memory

import com.voicekiosk.app.ondevice.LlmEngine
import com.voicekiosk.app.ondevice.PromptFormat
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HIGH tier only: the on-device model (Qwen2.5-1.5B) proposes facts from a finished
 * session's eligible utterances. Every proposal still has to pass [MemoryRules.validate]
 * (canonical wording, grounding, sensitive filter), so the model adds recall for unusual
 * phrasings but cannot invent wording, categories or sensitive facts.
 *
 * Uses a full prompt ([LlmEngine.PrefixMode.NONE]); the caller restores the routing prefix
 * KV afterwards ([com.voicekiosk.app.ondevice.route.RouterModelHost.runExclusive]).
 */
class ModelMemoryExtractor(
    private val engine: LlmEngine,
    private val format: PromptFormat,
    private val timeoutMs: Long = TIMEOUT_MS
) {
    data class Outcome(val facts: List<MemoryFact>, val status: String, val raw: String = "", val ms: Long = 0)

    fun extract(utterances: List<String>): Outcome {
        val eligible = utterances.filter { MemoryRules.eligible(it) }.takeLast(MAX_UTTERANCES)
        if (eligible.isEmpty()) return Outcome(emptyList(), "no_input")
        val t0 = System.currentTimeMillis()
        val g = try {
            engine.generate(
                LlmEngine.GenerateRequest(
                    prompt = format.format(SYSTEM, userContent(eligible)),
                    prefixMode = LlmEngine.PrefixMode.NONE,
                    maxTokens = MAX_TOKENS,
                    grammar = GRAMMAR,
                    temperature = 0f,
                    timeoutMs = timeoutMs,
                    fastForward = true
                )
            ).get(timeoutMs + 500, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            engine.cancel()
            return Outcome(emptyList(), "timeout", ms = System.currentTimeMillis() - t0)
        }
        val ms = System.currentTimeMillis() - t0
        if (!g.isSuccess) return Outcome(emptyList(), g.status.name.lowercase(), g.text, ms)
        val proposed = parse(g.text) ?: return Outcome(emptyList(), "bad_json", g.text, ms)
        val facts = proposed.map { MemoryRules.canonicalize(it, eligible) }.filter { MemoryRules.validate(it, eligible) }
            .map { it.copy(key = keyFor(it), evidence = eligible.joinToString(" / ")) }
        return Outcome(facts.distinctBy { it.content }, "ok", g.text, ms)
    }

    companion object {
        const val VERSION = "s5-memory-v1"
        const val TIMEOUT_MS = 6000L
        const val MAX_TOKENS = 64
        const val MAX_UTTERANCES = 6

        fun userContent(utterances: List<String>): String =
            "用户说过：\n" + utterances.joinToString("\n") { "- " + it.replace("<|", "<").replace("|>", ">").replace("\n", " ").trim().take(80) }

        val SYSTEM = """
你从家庭语音助手的对话里整理用户本人陈述的、长期稳定的事实，只输出 JSON。
规则：
1. 只整理用户本人明确陈述的事实。问句、请求、命令、假设、转述别人的话、否定句都不整理。
2. 绝不整理敏感信息：健康、疾病、用药、财务、收入、账户、证件号、密码、电话、邮箱、详细住址、精确位置。
3. 每条事实只能用下面的写法，X 必须是用户原话里的词：
   music：喜欢听X的歌 / 最喜欢的歌手是X / 最喜欢的歌是X
   nickname：希望被称呼为X
   routine：通常X点起床 / 通常晚上X点睡觉（X 照抄原话的时间）
   city：住在X（X 只写城市名）
   device：希望回答简短一些 / 希望说话慢一点
   family：女儿叫X / 家里的猫叫X（亲属或宠物的称呼）
   preference：喜欢吃X / 喜欢喝X
4. 没有可整理的事实时输出 {"facts":[]}。最多两条。
示例：
用户说过：
- 周杰伦的歌我是真的爱听
输出：{"facts":[{"cat":"music","text":"喜欢听周杰伦的歌"}]}
用户说过：
- 我平时都是十一点半睡
输出：{"facts":[{"cat":"routine","text":"通常十一点半睡觉"}]}
用户说过：
- 我们家住在杭州
- 我最近血压有点高
输出：{"facts":[{"cat":"city","text":"住在杭州"}]}
用户说过：
- 我同事说他喜欢听陈奕迅
输出：{"facts":[]}
""".trim()

        val GRAMMAR: String = buildString {
            appendLine("root ::= \"{\\\"facts\\\":[\" ( fact ( \",\" fact )? )? \"]}\"")
            appendLine("fact ::= \"{\\\"cat\\\":\\\"\" cat \"\\\",\\\"text\\\":\\\"\" tchr{2,24} \"\\\"}\"")
            appendLine("cat ::= \"music\" | \"nickname\" | \"routine\" | \"city\" | \"device\" | \"family\" | \"preference\"")
            append("tchr ::= [^\"\\\\\\x00-\\x1f<>{}]")
        }

        fun parse(raw: String): List<MemoryFact>? = try {
            val arr = JSONObject(raw.trim()).getJSONArray("facts")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val c = MemoryCategory.fromWire(o.getString("cat")) ?: return@mapNotNull null
                MemoryFact(c, o.getString("text").trim(), "", MemoryFact.Source.MODEL)
            }
        } catch (_: Exception) {
            null
        }

        /** Same keys as [MemoryRules.extract] for single-valued facts. */
        fun keyFor(f: MemoryFact): String = when (f.category) {
            MemoryCategory.NICKNAME -> "称呼"
            MemoryCategory.CITY -> "所在城市"
            MemoryCategory.ROUTINE -> Regex("(起床|睡觉|上床|午睡|下班|到家|吃晚饭|吃早饭|吃午饭)$").find(f.content)?.let { "作息·${it.value}" } ?: ""
            MemoryCategory.DEVICE -> if (f.content.startsWith("希望说话")) "语速" else "回答风格"
            MemoryCategory.MUSIC -> when {
                f.content.startsWith("最喜欢的歌手是") -> "最喜欢的歌手"
                f.content.startsWith("最喜欢的歌是") -> "最喜欢的歌"
                else -> ""
            }
            MemoryCategory.FAMILY -> f.content.substringBefore("叫").let { "称呼·$it" }
            MemoryCategory.PREFERENCE -> ""
        }
    }
}
