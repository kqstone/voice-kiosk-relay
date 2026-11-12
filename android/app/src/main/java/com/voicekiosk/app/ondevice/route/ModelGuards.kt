package com.voicekiosk.app.ondevice.route

/**
 * Deterministic checks on an L1 (model) local decision before anything runs (S2).
 *
 * The route eval showed the 1.5B model is good at naming the intent of real commands but
 * poor at abstaining: it maps "调亮屏幕" to volume, "明天早上七点的闹钟" to a 7-minute timer
 * and "下周天气" to after_tomorrow, all with confidence 0.95. So a model decision is only
 * accepted when the utterance itself carries lexical evidence for that intent, and weather
 * day / city slots are re-derived from the text (the model also invents cities from the
 * few-shot examples). Anything that fails goes to the cloud.
 */
object ModelGuards {
    private val VOLUME = Regex("(音量|声音|声|音响|喇叭|吵|响|静音|听不|大点|小点|大一点|小一点|大些|小些|大一些|小一些)")
    private val NOT_VOLUME = Regex("(屏幕|亮度|灯|空调|温度|字体|字|风扇|电视|手机)")
    private val MEDIA = Regex("(暂停|停|继续|接着|下一|上一|切歌|换一首|换首|换歌|跳过|播放|重播|再放)")
    private val PLAY = Regex("(放|播|听|来一首|来首|来点|唱|换成|整一首|整一个|整首)")
    private val TIMER = Regex("(分钟|小时|秒|钟头|计时|倒计时|定时)")
    /** Clock times / dates are alarms or calendar reminders, which are not local timers. */
    private val CLOCK_TIME = Regex("(点|号|早上|上午|中午|下午|晚上|今晚|明天|后天|闹钟|日程|开会|每天|星期|周)")
    private val RESET = Regex("(重新|换个话题|换一个话题|新对话|清空|重来)")
    private val WEATHER = Regex("(天气|气温|温度|度|冷|热|凉|暖|雨|雪|伞|晴|阴|风|雾|霾|紫外线)")
    /** Days the local forecast cannot answer (only today / tomorrow / the day after). */
    private val OTHER_DAY = Regex("(大后天|昨天|前天|下周|下礼拜|下星期|这周|本周|周末|周[一二三四五六日天]|星期|礼拜|月|号|未来|几天|最近|这几天|下个|上个|去年|明年|今年|冬天|夏天|春天|秋天|晚上|早上|上午|下午|夜里|今晚)")
    /** Advice / activities around weather: the cloud answers these. */
    private val WEATHER_ADVICE = Regex("(适合|适不适合|穿什么|穿啥|穿多少|穿多|穿少|做什么|干什么|去哪|能不能去|可以去|要不要去|出去玩|晒|洗车|晾|跑步|运动|爬山|对身体|危害|为什么|怎么|原因|准不准)")
    /** Not a command: asking about / for opinions on something (checked for every non-weather intent). */
    private val NOT_COMMAND = Regex("(推荐|买|哪款|哪个|哪种|牌子|多少钱|价格|好用|好不好|怎么样|什么|为什么|怎么|如何|是谁|谁唱|叫啥|意思|多远|多久|多少|能做|能跑|听懂|会不会|是不是|对不对|伤|危害|影响)")
    /** Question particles at the end: polite "…吗" requests lose the fast path (latency only). */
    private val QUESTION_END = Regex("(吗|么|嘛|呢|？|\\?)$")
    private val NOT_WEATHER = Regex("(亮度|屏幕|空调|调到|调成|调低|调高|开到|设置|冰箱|水温|体温|室内|屋里|热水|歌|唱|电影|是谁|名字|度数|角度|速度|进度|难度|程度|热量|热门|热闹|热点|冷笑话|冷静|风扇|风格|阴影|雨刷|雨衣|哪里|哪儿|买|卖)")
    private val ELLIPSIS = Regex("^(那)?(.{0,8})(呢|怎么样|如何|咋样|的呢)$")

    /**
     * @return the decision to execute (weather slots corrected), or null → cloud.
     * [previous] is the last turn's decision (for ellipsis).
     */
    fun check(text: String, d: RouteDecision, previous: RouteDecision?): RouteDecision? {
        if (!admits(d.intent, text, previous)) return null
        val s = RuleRouter.normalize(text)
        return when (d.intent) {
            Intents.MEDIA_PLAY -> d.takeIf { namesInText(s, d) }
            Intents.WEATHER -> weather(s, d, previous)
            else -> d
        }
    }

    /**
     * Slot-independent part of [check]: could a model decision with [intent] be accepted
     * for this utterance at all? ([check] returning non-null implies this is true.)
     */
    fun admits(intent: String, text: String, previous: RouteDecision?): Boolean {
        val s = RuleRouter.normalize(text)
        if (s.isEmpty()) return false
        if (intent != Intents.WEATHER && (NOT_COMMAND.containsMatchIn(s) || QUESTION_END.containsMatchIn(text.trim()))) return false
        return when (intent) {
            Intents.VOLUME -> VOLUME.containsMatchIn(s) && !NOT_VOLUME.containsMatchIn(s) && !RuleRouter.looksLikeQuestion(text)
            Intents.MEDIA_CONTROL -> MEDIA.containsMatchIn(s) && !RuleRouter.looksLikeQuestion(text)
            Intents.MEDIA_PLAY -> PLAY.containsMatchIn(s) && !RuleRouter.looksLikeQuestion(text)
            Intents.TIMER -> TIMER.containsMatchIn(s) && !CLOCK_TIME.containsMatchIn(s) && !RuleRouter.looksLikeQuestion(text)
            Intents.RESET -> RESET.containsMatchIn(s)
            Intents.WEATHER -> weatherAdmits(s, previous)
            else -> false
        }
    }

    /**
     * S3.1 pre-gate: run the model only if some whitelisted intent could pass [check];
     * everything else goes straight to the cloud with no model delay. Exact by
     * construction: the gate never drops a sentence the post-guards would accept.
     */
    fun mayBeLocal(text: String, previous: RouteDecision?, intents: Set<String>): Boolean =
        intents.any { admits(it, text, previous) }

    private fun weatherAdmits(s: String, previous: RouteDecision?): Boolean {
        if (OTHER_DAY.containsMatchIn(s) || WEATHER_ADVICE.containsMatchIn(s) || NOT_WEATHER.containsMatchIn(s) || RuleRouter.looksLikeKnowledgeQuestion(s)) return false
        val elliptical = previous?.intent == Intents.WEATHER && ELLIPSIS.matches(s)
        return WEATHER.containsMatchIn(s) || elliptical
    }

    /** At least one song / artist name must come from the utterance (no invented titles; "周董" → 周杰伦 is fine). */
    private fun namesInText(s: String, d: RouteDecision): Boolean {
        val names = listOfNotNull(d.slots["song"], d.slots["artist"], d.slots["query"]).map { it.trim() }.filter { it.isNotEmpty() }
        return names.isNotEmpty() && names.all { it.length >= 2 || s.contains(it) } && names.any { it.length >= 2 && s.contains(it) && it !in FUNCTION_WORDS }
    }

    private val FUNCTION_WORDS = setOf("这个", "那个", "你能", "我想", "一下", "一首", "歌曲", "音乐", "什么")

    private fun weather(s: String, d: RouteDecision, previous: RouteDecision?): RouteDecision? {
        val prevWeather = previous?.takeIf { it.intent == Intents.WEATHER }
        val elliptical = prevWeather != null && ELLIPSIS.matches(s)

        val day = when {
            s.contains("后天") -> "after_tomorrow"
            s.contains("明天") || s.contains("明儿") || s.contains("明日") -> "tomorrow"
            s.contains("今天") || s.contains("今儿") || s.contains("今日") || s.contains("现在") -> "today"
            prevWeather != null -> prevWeather.slots["day"] ?: "today"
            else -> "today"
        }
        val modelCity = d.slots["city"]?.trim()?.removeSuffix("市")?.takeIf { it.isNotEmpty() }
        val city = when {
            modelCity != null && s.contains(modelCity) -> modelCity
            prevWeather != null -> prevWeather.slots["city"]
            else -> null // the model's city was not said: use the default city
        }
        if (elliptical && modelCity != null && !s.contains(modelCity) && city == null) return null
        val slots = mutableMapOf("day" to day, "kind" to kindOf(s))
        if (city != null) slots["city"] = city
        return d.copy(slots = slots)
    }

    private fun kindOf(s: String): String = when {
        s.contains("雨") || s.contains("伞") || s.contains("雪") -> "rain"
        s.contains("度") || s.contains("冷") || s.contains("热") || s.contains("温") -> "temp"
        else -> "general"
    }
}
