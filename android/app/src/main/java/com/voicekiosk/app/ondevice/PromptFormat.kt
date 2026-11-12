package com.voicekiosk.app.ondevice

/**
 * Chat prompt formatting done on the Kotlin side so it is deterministic and
 * JVM-testable (llama.cpp's built-in template detection is not used).
 *
 * A prompt is split into a fixed [prefix] (system turn + the opening of the user turn)
 * and a short variable [suffix] (user content + assistant opening), so the prefix KV
 * cache can be evaluated once and reused: `format(s, u) == prefix(s) + suffix(u)`.
 */
enum class PromptFormat {
    /** Qwen2.5 ChatML. */
    CHATML,

    /** Qwen3 ChatML with thinking disabled (same as enable_thinking=false). */
    CHATML_QWEN3_NO_THINK;

    /** Ends right after "<|im_start|>user\n" — a special-token boundary, so the split never changes tokenization. */
    fun prefix(system: String): String =
        "<|im_start|>system\n" + system.trim() + "<|im_end|>\n<|im_start|>user\n"

    fun suffix(user: String): String {
        val sb = StringBuilder()
        sb.append(user.trim()).append("<|im_end|>\n<|im_start|>assistant\n")
        if (this == CHATML_QWEN3_NO_THINK) sb.append("<think>\n\n</think>\n\n")
        return sb.toString()
    }

    fun format(system: String, user: String): String = prefix(system) + suffix(user)
}

/** One routing request in the benchmark workload, with what a correct answer must contain. */
data class RouteCase(
    val id: String,
    /**
     * Recent dialog state, oldest first, newest last ("无" when there is none), e.g.
     * "weather_query city=北京 day=today" or "chitchat；weather_query city=北京 day=today".
     */
    val previousTurn: String,
    val utterance: String,
    val expectedIntent: String,
    val expectedRoute: String,
    /** Slot values that must appear (string compare after trimming quotes). */
    val expectedSlots: Map<String, String> = emptyMap()
) {
    /** Variable part of the user turn: recent dialog state + utterance. */
    fun userContent(): String = BenchmarkPrompt.userContent(previousTurn, utterance)
}

/**
 * The on-device routing prompt: used by the voice service's L1 router, the benchmark
 * sweep and the route eval, so all three measure the same thing.
 *
 * s2-route-v3 vs s0.1-route-v2:
 * - explicit "only clear device commands, weather or small talk are local" rule and
 *   cloud few-shots for questions about devices (the false-local risk);
 * - weather is local now (S3), chitchat is local only with a short reply (S3);
 * - the suffix carries the last 1–2 turns' compact state ("最近：…") instead of one
 *   "上一轮" line; still the only variable part, so the prefix KV cache is unchanged;
 * - slot keys are an enumerated set in the grammar (no free-form keys).
 *
 * Bump [VERSION] whenever text or grammar changes (it is part of the prefix-state cache key).
 */
object BenchmarkPrompt {
    const val VERSION = "s2-route-v3"
    const val MAX_TOKENS = 48

    /** Small-talk replies are capped at this many characters (grammar + post-check). */
    const val REPLY_MAX_CHARS = 40
    const val REPLY_MAX_TOKENS = 80

    val INTENTS = listOf(
        "time_query", "weather_query", "media_play", "media_control", "volume_set",
        "timer_set", "board_switch", "session_reset", "memory_command", "chitchat",
        "cloud_question"
    )

    val SLOT_KEYS = listOf("city", "day", "song", "artist", "action", "direction", "level", "minutes", "board")

    val SYSTEM = """
你是语音助手的端侧路由器，只输出一个JSON，不要解释。
格式：{"intent":意图,"slots":{槽位},"route":"local"或"cloud","confidence":0到1}
意图（槽位）：
time_query 问现在的时间日期星期
weather_query 问天气气温下雨（city，day=today|tomorrow|after_tomorrow）
media_play 点播歌曲或歌手（song，artist）
media_control 暂停继续切歌（action=pause|resume|next|prev）
volume_set 调音量（direction=up|down|mute|unmute 或 level=0-100）
timer_set 倒计时提醒（minutes）
board_switch 切换看板（board）
session_reset 重新开始或换话题
memory_command 记住或忘掉某事
chitchat 打招呼、感谢、问候、道别、夸奖
cloud_question 知识、新闻、股票、实时信息、建议、推理、计算、写作、翻译、讲故事和其他一切
规则：
1.只有明确的设备指令、问天气、简单寒暄才route=local；提问、求建议、聊话题、不确定都是cloud_question且route=cloud。
2.问怎么做、为什么、是不是、会不会，即使提到音量音乐天气，也是cloud_question。
3.“最近”是前一两轮的意图，最后一项是上一轮；省略句（那明天呢、北京呢、再大点）继承上一轮意图和槽位，只改提到的部分。
4.很确定才confidence≥0.9。
5.寒暄要回复时，reply写一句不超过二十字、友好自然的中文，不提供任何事实或建议。
示例：
最近：无
用户：现在几点了
{"intent":"time_query","slots":{},"route":"local","confidence":0.95}
最近：weather_query city=北京 day=today
用户：那明天上海呢
{"intent":"weather_query","slots":{"city":"上海","day":"tomorrow"},"route":"local","confidence":0.9}
最近：media_play artist=周杰伦
用户：声音大一点
{"intent":"volume_set","slots":{"direction":"up"},"route":"local","confidence":0.95}
最近：无
用户：音量开太大会伤耳朵吗
{"intent":"cloud_question","slots":{},"route":"cloud","confidence":0.95}
最近：无
用户：帮我写一首关于秋天的诗
{"intent":"cloud_question","slots":{},"route":"cloud","confidence":0.95}
最近：无
用户：谢谢你
{"intent":"chitchat","slots":{},"route":"local","confidence":0.95}
""".trim()

    /** Cases for the sweep workload (measured runs rotate); case 0 is also the warm-up. */
    val CASES = listOf(
        RouteCase(
            "ellipsis_weather", "weather_query city=北京 day=today", "那明天上海呢？",
            "weather_query", "local", mapOf("city" to "上海", "day" to "tomorrow")
        ),
        RouteCase("volume_level", "无", "把音量调到30", "volume_set", "local", mapOf("level" to "30")),
        RouteCase(
            "play_song", "无", "播放周杰伦的晴天", "media_play", "local",
            mapOf("song" to "晴天", "artist" to "周杰伦")
        ),
        RouteCase("stock_cloud", "无", "今天英伟达股价多少", "cloud_question", "cloud")
    )

    private fun common() = buildString {
        appendLine("slots ::= \"{\" ( kv ( \",\" kv ){0,3} )? \"}\"")
        appendLine("kv ::= key \":\" ( str | num )")
        appendLine("key ::= \"\\\"\" (" + SLOT_KEYS.joinToString(" | ") { "\"$it\"" } + ") \"\\\"\"")
        appendLine("str ::= \"\\\"\" chr{0,16} \"\\\"\"")
        appendLine("chr ::= [^\"\\\\\\x00-\\x1f]")
        appendLine("num ::= [0-9]{1,3}")
        append(routeConf())
    }

    private fun routeConf() = buildString {
        appendLine("route ::= \"\\\"local\\\"\" | \"\\\"cloud\\\"\"")
        appendLine("conf ::= \"0\" ( \".\" [0-9] [0-9]? )? | \"1\" ( \".0\" )?")
    }

    val GRAMMAR: String = buildString {
        appendLine("root ::= \"{\\\"intent\\\":\" intent \",\\\"slots\\\":\" slots \",\\\"route\\\":\" route \",\\\"confidence\\\":\" conf \"}\"")
        appendLine("intent ::= \"\\\"\" (" + INTENTS.joinToString(" | ") { "\"$it\"" } + ") \"\\\"\"")
        append(common())
    }

    /**
     * Small-talk variant (only used after the rules recognised safe small talk): intent and
     * slots are forced (fast-forwarded), the model picks route + confidence and writes a
     * reply of at most [REPLY_MAX_CHARS] characters.
     */
    val CHITCHAT_GRAMMAR: String = buildString {
        appendLine("root ::= \"{\\\"intent\\\":\\\"chitchat\\\",\\\"slots\\\":{},\\\"route\\\":\" route \",\\\"confidence\\\":\" conf \",\\\"reply\\\":\\\"\" rchr{2,$REPLY_MAX_CHARS} \"\\\"}\"")
        appendLine("rchr ::= [^\"\\\\\\x00-\\x1f0-9a-zA-Z<>{}]")
        append(routeConf())
    }

    fun userContent(recent: String, utterance: String): String = "最近：$recent\n用户：$utterance"

    fun prefix(format: PromptFormat): String = format.prefix(SYSTEM)
    fun suffix(format: PromptFormat, case: RouteCase = CASES[0]): String = format.suffix(case.userContent())
    fun prompt(format: PromptFormat, case: RouteCase = CASES[0]): String = format.format(SYSTEM, case.userContent())
}

/** Grades a routing answer against a [RouteCase] (used to keep prompt shortening honest). */
object RouteOutputCheck {
    data class Verdict(val ok: Boolean, val reason: String)

    private fun field(json: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"([^\"]*)\"|[0-9.]+)").find(json) ?: return null
        return m.groups[2]?.value ?: m.groups[1]?.value
    }

    fun check(output: String, case: RouteCase): Verdict {
        val t = output.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return Verdict(false, "not a JSON object")
        val intent = field(t, "intent") ?: return Verdict(false, "no intent")
        if (intent != case.expectedIntent) return Verdict(false, "intent=$intent want ${case.expectedIntent}")
        val route = field(t, "route") ?: return Verdict(false, "no route")
        if (route != case.expectedRoute) return Verdict(false, "route=$route want ${case.expectedRoute}")
        val slotsBody = Regex("\"slots\"\\s*:\\s*\\{([^}]*)\\}").find(t)?.groups?.get(1)?.value
            ?: return Verdict(false, "no slots")
        for ((k, v) in case.expectedSlots) {
            val got = field("{$slotsBody}", k) ?: return Verdict(false, "missing slot $k")
            if (got != v) return Verdict(false, "slot $k=$got want $v")
        }
        return Verdict(true, "ok")
    }
}
