package com.voicekiosk.app.ondevice.route

/**
 * L0: regex / lexicon fast path over the ASR text (target < 20 ms, typically < 1 ms).
 *
 * Every pattern is anchored to the WHOLE normalized utterance, so anything with extra
 * content ("纽约现在几点", "怎么调大音量", "下一首歌叫什么") does not match and goes on
 * to L1 / the cloud. Returns null when unsure.
 *
 * Besides LOCAL decisions it also returns a few CLOUD decisions that let the
 * orchestrator skip the model: weather (stub until S3) and memory commands (the bridge
 * owns long-term memory).
 */
class RuleRouter {

    /** Previous turn as far as L0 cares (ellipsis like "继续" / "再大点"). */
    fun route(text: String, previous: RouteDecision? = null): RouteDecision? {
        val s = normalize(text)
        if (s.isEmpty() || s.length > MAX_LEN) return null
        return reset(s)
            ?: smallTalk(s)
            ?: time(s)
            ?: weather(s, previous)
            ?: volume(s, previous)
            ?: mediaControl(s, previous)
            ?: withoutReason(s)?.let { core -> volume(core, previous) ?: mediaControl(core, previous) }
            ?: timer(s)
            ?: mediaPlay(s)
            ?: cloudShortcut(s)
    }

    // ---- intents ----

    /** "小声一点孩子在睡觉" → "小声一点": a short reason clause before or after a device command. */
    private fun withoutReason(s: String): String? {
        REASON_TAIL.matchEntire(s)?.let { m -> return m.groups[1]!!.value.takeIf { it.isNotEmpty() } }
        REASON_HEAD.matchEntire(s)?.let { m -> return m.groups[1]!!.value.takeIf { it.isNotEmpty() } }
        return null
    }

    private fun reset(s: String): RouteDecision? =
        if (RESET.matches(s)) local(Intents.RESET) else null

    private fun time(s: String): RouteDecision? {
        if (TIME.any { it.matches(s) }) return local(Intents.TIME, "kind" to "time")
        val m = DATE.matchEntire(s) ?: return null
        val datePart = m.groups[3]?.value
        val weekPart = m.groups[4]?.value
        if (datePart == null && weekPart == null) return null
        val offset = when (m.groups[1]?.value) { "明天" -> 1; "后天" -> 2; "昨天" -> -1; "前天" -> -2; else -> 0 }
        val kind = if (datePart == null) "weekday" else "date"
        return local(Intents.TIME, "kind" to kind, "day_offset" to offset.toString())
    }

    private fun volume(s: String, previous: RouteDecision?): RouteDecision? {
        VOL_LEVEL.matchEntire(s)?.let { m ->
            val n = ChineseNumber.parse(m.groups[1]!!.value) ?: return null
            if (n !in 0..100) return null
            return local(Intents.VOLUME, "level" to n.toString())
        }
        if (VOL_MAX.any { it.matches(s) }) return local(Intents.VOLUME, "level" to "100")
        if (VOL_HALF.matches(s)) return local(Intents.VOLUME, "level" to "50")
        if (VOL_MIN.matches(s)) return local(Intents.VOLUME, "level" to "10")
        if (VOL_UNMUTE.matches(s)) return local(Intents.VOLUME, "direction" to "unmute")
        if (VOL_MUTE.matches(s)) return local(Intents.VOLUME, "direction" to "mute")
        if (VOL_UP.any { it.matches(s) }) return local(Intents.VOLUME, "direction" to "up")
        if (VOL_DOWN.any { it.matches(s) }) return local(Intents.VOLUME, "direction" to "down")
        // bare "再大点" / "小一点" only right after a volume turn
        if (previous?.intent == Intents.VOLUME) {
            if (BARE_UP.matches(s)) return local(Intents.VOLUME, "direction" to "up")
            if (BARE_DOWN.matches(s)) return local(Intents.VOLUME, "direction" to "down")
        }
        return null
    }

    private fun mediaControl(s: String, previous: RouteDecision?): RouteDecision? {
        if (s in PAUSE || PAUSE_RE.matches(s)) return local(Intents.MEDIA_CONTROL, "action" to "pause")
        if (s in NEXT || DISLIKE_NEXT.matches(s) || NEXT_RE.matches(s)) return local(Intents.MEDIA_CONTROL, "action" to "next")
        if (s in PREV || PREV_RE.matches(s)) return local(Intents.MEDIA_CONTROL, "action" to "prev")
        if (s in RESUME) return local(Intents.MEDIA_CONTROL, "action" to "resume")
        // "继续" means "continue talking" after a cloud answer; only media right after media
        val lastWasMedia = previous?.intent == Intents.MEDIA_CONTROL || previous?.intent == Intents.MEDIA_PLAY
        if (lastWasMedia && s in RESUME_AFTER_MEDIA) return local(Intents.MEDIA_CONTROL, "action" to "resume")
        return null
    }

    private fun timer(s: String): RouteDecision? {
        if (TIMER_CANCEL.matches(s)) return local(Intents.TIMER, "action" to "cancel")
        for (p in TIMER_FORMS) {
            val m = p.matchEntire(s) ?: continue
            val secs = parseDuration(m.groups["d"]!!.value) ?: continue
            if (secs !in 1..MAX_TIMER_SECONDS) return null
            val label = (if (p.pattern.contains("<label>")) m.groups["label"]?.value else null).orEmpty()
                .removePrefix("一声").removePrefix("一下").removeSuffix("一声")
            return if (label.isEmpty()) local(Intents.TIMER, "seconds" to secs.toString())
            else local(Intents.TIMER, "seconds" to secs.toString(), "label" to label)
        }
        return null
    }

    private val PLAY_FAVORITE = Regex(
        "^(?:播放|放|来|听|我想听|我要听|想听|给我放|放一首|来一首|来几首|来点|放点|放几首)?(?:一首|点|几首)?我(?:最)?(?:喜欢|爱)(?:听)?的(?:歌|歌曲|音乐|歌手的歌)$"
    )

    private fun mediaPlay(s: String): RouteDecision? {
        if (s in PLAY_GENERIC) return local(Intents.MEDIA_CONTROL, "action" to "resume")
        // S5: "放我喜欢的歌" → the favourite artist from the memory cache (resolved by the tool)
        if (PLAY_FAVORITE.matches(s)) return local(Intents.MEDIA_PLAY, "favorite" to "1")
        if (looksLikeQuestion(s)) return null
        val m = PLAY.matchEntire(s)
        if (m == null) {
            // bare 放/听/播 + "X的歌" ("放周杰伦的歌"); bare 放 is too broad otherwise (放假, 放大)
            val a = PLAY_ARTIST_SONGS.matchEntire(s) ?: return null
            val artist = a.groups[1]!!.value
            return if (validName(artist)) local(Intents.MEDIA_PLAY, "artist" to artist) else null
        }
        var body = m.groups["body"]!!.value
        for (prefix in BODY_PREFIXES) if (body.startsWith(prefix) && body.length > prefix.length) { body = body.removePrefix(prefix); break }
        for (suffix in BODY_SUFFIXES) if (body.endsWith(suffix) && body.length > suffix.length) { body = body.removeSuffix(suffix); break }
        if (body in GENERIC_MUSIC) return local(Intents.MEDIA_CONTROL, "action" to "resume")
        if (BAD_BODY_START.any { body.startsWith(it) }) return null
        if (NOT_MUSIC.matches(body)) return null
        // "来点王菲的" → artist
        if (body.length > 2 && body.endsWith("的") && body.indexOf('的') == body.length - 1) {
            val artist = body.dropLast(1)
            return if (validName(artist)) local(Intents.MEDIA_PLAY, "artist" to artist) else null
        }
        val idx = body.indexOf('的')
        if (idx > 0 && idx < body.length - 1) {
            val artist = body.substring(0, idx)
            val rest = body.substring(idx + 1)
            if (!validName(artist)) return null
            return if (rest in GENERIC_MUSIC) local(Intents.MEDIA_PLAY, "artist" to artist)
            else if (validName(rest)) local(Intents.MEDIA_PLAY, "artist" to artist, "song" to rest) else null
        }
        return if (validName(body)) local(Intents.MEDIA_PLAY, "query" to body) else null
    }

    private fun cloudShortcut(s: String): RouteDecision? = when {
        MEMORY.containsMatchIn(s) -> RouteDecision(Intents.MEMORY, emptyMap(), Route.CLOUD, CONFIDENCE, RouteSource.RULE)
        // weather-ish but not a plain forecast question ("天气预报为什么不准", "台风是怎么形成的")
        WEATHER.containsMatchIn(s) && WEATHER_KNOWLEDGE.containsMatchIn(s) ->
            RouteDecision(Intents.CLOUD_QUESTION, emptyMap(), Route.CLOUD, CONFIDENCE, RouteSource.RULE)
        else -> null
    }

    /** Safe small talk only (greeting / thanks / how-are-you / bye / praise), whole utterance. */
    private fun smallTalk(s: String): RouteDecision? {
        val kind = SMALL_TALK.entries.firstOrNull { (_, set) -> s in set }?.key ?: return null
        return local(Intents.CHITCHAT, "kind" to kind, "phrase" to s)
    }

    /**
     * Plain forecast questions: [day][city][day](的)(天气|气温|冷不冷|下雨|带伞…). Cities
     * must be built-in names (or end in 市/县/区) so junk like "外面" never becomes a slot.
     * Ellipsis right after a weather turn: "那明天呢", "后天呢", "北京呢".
     */
    private fun weather(s: String, previous: RouteDecision?): RouteDecision? {
        WEATHER_Q.matchEntire(s)?.let { m ->
            val city = m.groups["city"]?.value?.let { cleanCity(it) ?: return null }
                // "明天会下雨吗" right after "北京天气" still means Beijing
                ?: previous?.takeIf { it.intent == Intents.WEATHER }?.slots?.get("city")
            val day = dayOf(m.groups["d1"]?.value ?: m.groups["d2"]?.value)
            val kind = kindOf(m.groups["what"]!!.value)
            return weatherDecision(city, day ?: "today", kind)
        }
        if (previous?.intent == Intents.WEATHER) {
            WEATHER_ELLIPSIS.matchEntire(s)?.let { m ->
                val cityRaw = m.groups["city"]?.value
                val dayRaw = m.groups["d1"]?.value ?: m.groups["d2"]?.value
                if (cityRaw == null && dayRaw == null) return null
                val city = cityRaw?.let { cleanCity(it) ?: return null } ?: previous.slots["city"]
                val day = dayOf(dayRaw) ?: previous.slots["day"] ?: "today"
                return weatherDecision(city, day, previous.slots["kind"] ?: "general")
            }
        }
        return null
    }

    private fun weatherDecision(city: String?, day: String, kind: String): RouteDecision {
        val slots = mutableListOf("day" to day, "kind" to kind)
        if (city != null) slots += "city" to city
        return local(Intents.WEATHER, *slots.toTypedArray())
    }

    private fun cleanCity(raw: String): String? {
        val c = raw.removeSuffix("的")
        return when {
            com.voicekiosk.app.ondevice.tools.CityTable.find(c) != null -> c.removeSuffix("市")
            c.length in 2..6 && (c.endsWith("市") || c.endsWith("县") || c.endsWith("区")) && c !in NOT_PLACES -> c
            else -> null
        }
    }

    private fun dayOf(d: String?): String? = when (d) {
        null -> null
        "今天", "今日", "今儿", "今儿个", "现在", "这会儿" -> "today"
        "明天", "明日", "明儿", "明儿个" -> "tomorrow"
        "后天", "后儿" -> "after_tomorrow"
        else -> null
    }

    private fun kindOf(what: String): String = when {
        what.contains("雨") || what.contains("伞") || what.contains("雪") -> "rain"
        what.contains("度") || what.contains("冷") || what.contains("热") || what.contains("温") -> "temp"
        else -> "general"
    }

    private fun local(intent: String, vararg slots: Pair<String, String>) =
        RouteDecision(intent, mapOf(*slots), Route.LOCAL, CONFIDENCE, RouteSource.RULE)

    private fun validName(n: String) = n.length in 1..MAX_NAME

    companion object {
        const val CONFIDENCE = 0.98
        const val MAX_LEN = 40
        const val MAX_NAME = 24
        const val MAX_TIMER_SECONDS = 3 * 3600

        private val PREFIXES = listOf(
            "你好小智", "小智小智", "小智", "嗯嗯", "嗯", "那个", "好的", "那", "请问", "请你", "请", "告诉我", "我想知道", "问一下", "麻烦你", "麻烦",
            "你帮我", "帮我", "帮忙", "你能不能", "能不能", "能否", "可不可以", "你可以", "可以", "你给我", "给我"
        )
        private val SUFFIXES = listOf(
            "好不好", "好吗", "行不行", "行吗", "可以吗", "谢谢你", "谢谢", "一下下", "一下", "吧", "啊", "呀", "哦", "哈", "嘛", "吗"
        )
        private val STRIP = Regex("[\\s\\p{P}\\p{S}&&[^%]]")

        /** Lower-cases, removes spaces / punctuation and polite fillers at both ends. */
        fun normalize(raw: String): String {
            var s = STRIP.replace(raw.lowercase(), "")
            var changed = true
            while (changed && s.isNotEmpty()) {
                changed = false
                for (p in PREFIXES) if (s.startsWith(p) && s.length > p.length) { s = s.removePrefix(p); changed = true; break }
                for (x in SUFFIXES) if (s.endsWith(x) && s.length > x.length) { s = s.removeSuffix(x); changed = true; break }
            }
            // "给我放一首" -> stripping "给我" is fine; "把" is handled by the patterns
            return s
        }

        private val QUESTION = Regex("(吗|么|什么|怎么|怎样|为什么|多少|哪|谁|几|如何|是不是|有没有|啥|会不会|要不要|对不对|能不能|可不可以)")

        /** Weather-specific: "为什么/准不准/怎么形成" etc. (plain "会下雨吗/多少度" are fine). */
        fun looksLikeKnowledgeQuestion(s: String): Boolean = WEATHER_KNOWLEDGE.containsMatchIn(normalize(s))

        /** Utterances that ask about something rather than command it. */
        fun looksLikeQuestion(s: String): Boolean = QUESTION.containsMatchIn(normalize(s))

        private const val N = ChineseNumber.PATTERN

        private val RESET = Regex("(我们)?(重新开始|重新来|重来|换个话题|换一个话题|开始新对话|新对话|清空对话|重新聊)")

        private val TIME = listOf(
            Regex("(现在|当前|目前|北京时间)?(是)?(几点|几点钟|几点了|几点钟了|什么时间|什么时间了|什么时候了|啥时候了|多少点了?|几时了?)(了)?"),
            Regex("(告诉我|说一下|看看|报一下)?(现在|当前)?(的)?时间"),
            Regex("报时")
        )
        private val DATE = Regex(
            "(今天|今日|明天|后天|昨天|前天|现在)?(是)?" +
                "(几月几号|几月几日|几号|几日|多少号|什么日期|日期|几月)?" +
                "(星期几|礼拜几|周几)?"
        )

        private const val V = "(?:音量|声音|音响|媒体音量|喇叭)"
        private const val SET = "(?:调|设|设置|开|调整|放|改|弄)?(?:到|成|为|在)?"
        private val VOL_LEVEL = Regex("(?:把)?${V}${SET}(?:百分之)?(${N})(?:%)?")
        private val VOL_MAX = listOf(
            Regex("(?:把)?${V}${SET}(?:最大|最高|最响)(?:声)?"), Regex("最大音量"), Regex("(?:调到|开到)?最大声")
        )
        private val VOL_HALF = Regex("(?:把)?${V}${SET}一半")
        private val VOL_MIN = Regex("(?:把)?${V}${SET}(?:最小|最低)|最小音量|最小声")
        private val VOL_MUTE = Regex("(?:设置|设为|打开|开启|开|切换到)?静音(?:模式)?|(?:把)?${V}(?:关掉|关闭|关了)|(?:关掉|关闭|关了)${V}")
        private val VOL_UNMUTE = Regex("(?:取消|关闭|关掉|解除|退出)静音(?:模式)?|恢复${V}|打开${V}|${V}(?:打开|恢复)")

        private const val MORE = "(?:一)?(?:点|些|点儿)?"
        private val VOL_UP = listOf(
            Regex("(?:再)?(?:把)?${V}(?:再)?(?:调|开|放|弄)?(?:大|高|响)${MORE}"),
            Regex("(?:再)?(?:调大|调高|增大|提高|加大|开大|放大|调响)${MORE}${V}?"),
            Regex("(?:再)?大(?:一)?(?:点|些)(?:儿)?声(?:音)?|(?:再)?大声${MORE}"),
            Regex("${V}(?:太)?(?:小|低)了?|太小声了?|${V}(?:加|加大|增加)${MORE}"),
            Regex("(?:${V}(?:太小了?|太小声了?|有点小|太低了?)?|太小了?|太小声了?|有点小|太低了?)(?:我)?(?:听不清|听不见|听不到|听不清楚)(?:了)?(?:大声${MORE}|大${MORE})?|(?:有点)?(?:听不清|听不见|听不到)(?:大声${MORE}|大${MORE}|调大${MORE})")
        )
        private val VOL_DOWN = listOf(
            Regex("(?:再)?(?:把)?${V}(?:再)?(?:调|开|放|弄)?(?:小|低|轻)${MORE}"),
            Regex("(?:再)?(?:调小|调低|减小|降低|减少|关小|调轻)${MORE}${V}?"),
            Regex("(?:再)?小(?:一)?(?:点|些)(?:儿)?声(?:音)?|(?:再)?小声${MORE}|(?:再)?轻(?:一)?(?:点|些)(?:声)?"),
            Regex("${V}太(?:大|响|吵)了?|太吵了|太响了|${V}(?:减|减小|降低)${MORE}")
        )
        private val BARE_UP = Regex("(?:再)?(?:大|响|高)${MORE}")
        private val BARE_DOWN = Regex("(?:再)?(?:小|低|轻)${MORE}")

        private val PAUSE = setOf(
            "暂停", "暂停播放", "暂停音乐", "暂停一下", "停止播放", "停止音乐", "停止", "停", "停下", "停下来", "先停", "先停一下",
            "别放了", "不要放了", "不放了", "不听了", "关掉音乐", "关闭音乐", "关音乐", "音乐停", "音乐暂停", "别唱了", "把音乐关了", "把音乐关掉", "停一停"
        )
        private val RESUME = setOf(
            "继续播放", "接着放", "接着播", "接着播放", "恢复播放", "继续放", "继续音乐", "继续听", "开始播放", "播放", "播放音乐"
        )
        private val RESUME_AFTER_MEDIA = setOf("继续", "接着", "放吧", "播吧", "接着来")
        private val NEXT = setOf(
            "下一首", "下一曲", "切歌", "换一首", "换首歌", "换一首歌", "下一首歌", "下首", "下一个", "跳过", "跳过这首", "跳过这首歌",
            "播放下一首", "放下一首", "换歌", "切换下一首", "听下一首"
        )
        /** "这首不好听换一首", "不喜欢这首歌下一首" */
        private val DISLIKE_NEXT = Regex("(?:这首|这首歌|这歌)?(?:不好听|难听|不喜欢|不想听|听腻了|听烦了)(?:这首|这首歌)?(?:了)?(?:换一首|换首|换一首歌|换首歌|换歌|下一首|切歌|跳过)(?:吧)?")
        private val PREV = setOf("上一首", "上一曲", "上首", "上一首歌", "播放上一首", "放上一首", "回到上一首", "切换上一首", "听上一首", "上一个")

        private val PLAY_GENERIC = setOf(
            "放音乐", "放点音乐", "来点音乐", "放首歌", "来首歌", "放歌", "放一首歌", "来一首歌", "听歌", "我想听歌", "我要听歌",
            "播放歌曲", "随便放首歌", "随便放点音乐", "放点歌", "唱首歌", "唱一首歌", "听音乐", "我想听音乐", "我要听音乐"
        )
        private val GENERIC_MUSIC = setOf("歌", "歌曲", "音乐", "专辑", "曲子", "新歌", "老歌", "首歌", "一首歌", "点音乐", "点歌", "的歌")
        private val BODY_SUFFIXES = listOf("这首歌曲", "这首歌", "这首", "那首歌", "那首")
        private val BODY_PREFIXES = listOf("歌曲", "歌手", "一首", "首")
        private val BAD_BODY_START = listOf("器", "量", "列表", "模式", "设置", "键", "按钮", "下一", "上一", "不", "懂", "见", "清", "到", "说", "话", "听")
        private val PLAY = Regex(
            "(?:" + listOf(
                "我想听听", "我要听听", "想听听", "我想听", "我要听", "想听", "听一下", "听听", "播放一首", "播放", "播一首", "播首", "播一下",
                "放一首", "放首", "放一下", "来一首", "来首", "听一首", "唱一首", "唱首", "给我放", "给我来一首", "放点", "来点"
            ).sortedByDescending { it.length }.joinToString("|") + ")" +
                "(?:一首|首|一下|一点)?(?<body>.+)"
        )
        /** "来点吃的", "放点水", "来点新闻": not music. */
        private val NOT_MUSIC = Regex("(?:好)?(?:吃|喝|玩|看)(?:的)?|热水|水|饭|茶|咖啡|零食|新闻|笑话|故事|电视|视频|电影|广播|假")
        private val PLAY_ARTIST_SONGS = Regex("(?:放|听|来点|播)(.+?)的(?:歌|歌曲|音乐|专辑)")

        private val TIMER_CANCEL = Regex("(?:取消|关掉|关闭|停止|停掉|结束)(?:倒计时|计时器|计时|定时器|定时|提醒)")
        private val TIMER_FORMS = listOf(
            Regex("(?:设置|设|定|开始|开|来)?(?:一个|个)?(?<d>.+?)(?:的)?(?:倒计时|计时器|计时|定时器|定时|闹钟)"),
            Regex("(?:倒计时|计时器|计时|定时器|定时)(?<d>.+)"),
            Regex("(?<d>.+?)(?:以)?后(?:提醒|叫|喊)(?:我|一下)?(?<label>.{0,12})"),
            // "过两分钟喊我一声", "等十分钟提醒我关火"
            Regex("(?:再)?(?:过|等)(?<d>.+?)(?:以后|之后|后)?(?:提醒|叫|喊)(?:我|一下)?(?<label>.{0,12})")
        )

        /** Reason clauses around a volume / media command ("孩子在睡觉"). */
        private const val REASON = "(?:因为)?(?:孩子|宝宝|小孩|老人|家人|有人|我|大家|别人|邻居|爸爸|妈妈|老婆|老公|他|她)?(?:在|要|正在|已经)?" +
            "(?:睡觉|睡了|睡着了|午睡|休息|学习|写作业|开会|打电话|接电话|看书|上课|上网课)(?:了|呢)?"
        private val REASON_TAIL = Regex("(.+?)$REASON")
        private val REASON_HEAD = Regex("$REASON(.+)")

        private val PAUSE_RE = Regex("(?:把)?(?:音乐|歌|歌曲|播放器|播放)?(?:先)?(?:暂停|停下来|停下|停止播放|停一停|停一会儿|停一会|停)(?:一会儿|一会|下)?(?:音乐|播放|歌曲)?")
        private val NEXT_RE = Regex("(?:切到|切换到|换到|跳到|放|播放|听|来)(?:下一首|下一曲|下首)(?:歌|歌曲)?")
        private val PREV_RE = Regex("(?:切到|切换到|换到|跳到|回到|放|播放|听|来)(?:上一首|上一曲|上首)(?:歌|歌曲)?")

        private val SMALL_TALK: Map<String, Set<String>> = mapOf(
            "greeting" to setOf("你好", "您好", "嗨", "哈喽", "hello", "hi", "早上好", "早安", "上午好", "中午好", "下午好", "晚上好", "早", "在", "在不在", "你在吗", "你在"),
            "thanks" to setOf("谢谢", "谢谢你", "谢谢啦", "多谢", "感谢", "谢了", "太感谢了", "非常感谢", "辛苦了", "辛苦你了", "麻烦你了", "谢谢小智"),
            "how_are_you" to setOf("你还好", "最近好", "最近怎么样", "你今天怎么样", "你今天好", "你过得怎么样", "你心情怎么样", "你心情好", "你最近怎么样"),
            "bye" to setOf("再见", "拜拜", "拜", "晚安", "回头见", "明天见", "下次见", "没事了", "好了没事了", "先这样"),
            "praise" to setOf("你真棒", "你真聪明", "你好厉害", "真厉害", "好厉害", "你真好", "真乖", "做得好", "干得漂亮", "你真厉害", "太棒了")
        )

        private const val CITY_RE = "(?<city>(?:" + "@CITIES@" + ")市?|[\u4e00-\u9fa5]{1,5}(?:市|县|区))"
        private const val DAY1 = "(?<d1>今天|今日|今儿个|今儿|明天|明日|明儿个|明儿|后天|现在|这会儿)"
        private const val DAY2 = "(?<d2>今天|今日|今儿个|今儿|明天|明日|明儿个|明儿|后天|现在|这会儿)"
        private val WHAT = "(?<what>天气|天气怎么样|天气如何|天气咋样|天气好|天气好不好|天气预报|天气情况|气温|气温多少|温度|温度多少|多少度|几度|热不热|冷不冷|会下雨|下雨|会不会下雨|下不下雨|有雨|有没有雨|要带伞|需要带伞|要不要带伞|用带伞|用不用带伞|得带伞|会下雪|下雪|会不会下雪|冷吗|热吗|很冷|很热|很冷吗|很热吗|会很冷吗|会很热吗|凉快吗|暖和吗|下雨吗|下雪吗|有雨吗)"
        private val WEATHER_Q = Regex(
            "(?:那)?(?:看看|看一下|查查|查一下|查询|说说|报一下|播报一下|播报)?(?:$DAY1)?(?:${CITY_RE.replace("@CITIES@", com.voicekiosk.app.ondevice.tools.CityTable.NAME_PATTERN)})?(?:$DAY2)?(?:的)?(?:外面|外边|室外|出门)?(?:会不会)?$WHAT(?:不|没|没有|呢)?"
        )
        private val WEATHER_ELLIPSIS = Regex(
            "(?:那)?(?:$DAY1)?(?:${CITY_RE.replace("@CITIES@", com.voicekiosk.app.ondevice.tools.CityTable.NAME_PATTERN)})?(?:$DAY2)?(?:的)?(?:呢|怎么样|如何|咋样|的呢)?"
        )
        private val NOT_PLACES = setOf("小区", "市区", "城区", "郊区", "山区", "景区", "园区", "社区", "地区")
        private val WEATHER_KNOWLEDGE = Regex("(为什么|怎么形成|原理|准不准|不准|怎么看|怎么查|是什么|什么意思|哪个|对比|比较)")

        private val MEMORY = Regex("^(记住|忘掉|忘记|删除记忆|更正记忆|修改记忆|清空记忆|清除记忆)")
        private val WEATHER = Regex("(天气|气温|温度|下雨|下雪|几度|冷不冷|热不热|带伞|空气质量|雾霾|刮风)")

        private val DUR_HOUR = Regex("(${N})(?:个)?(半)?(?:小时|钟头)")
        private val DUR_HALF_HOUR = Regex("半(?:个)?(?:小时|钟头)")
        private val DUR_MIN = Regex("(${N})(?:分钟|分)(?:(${N})(?:秒钟|秒))?")
        private val DUR_SEC = Regex("(${N})(?:秒钟|秒)")

        /** Seconds for "5分钟", "三十秒", "一个半小时", "半小时", "1分30秒"; null if not a duration. */
        fun parseDuration(d: String): Int? {
            DUR_HALF_HOUR.matchEntire(d)?.let { return 1800 }
            if (d == "半分钟") return 30
            DUR_HOUR.matchEntire(d)?.let { m ->
                val h = ChineseNumber.parse(m.groups[1]!!.value) ?: return null
                return h * 3600 + if (m.groups[2] != null) 1800 else 0
            }
            DUR_MIN.matchEntire(d)?.let { m ->
                val mins = ChineseNumber.parse(m.groups[1]!!.value) ?: return null
                val secs = m.groups[2]?.value?.let { ChineseNumber.parse(it) ?: return null } ?: 0
                return mins * 60 + secs
            }
            DUR_SEC.matchEntire(d)?.let { m -> return ChineseNumber.parse(m.groups[1]!!.value) }
            return null
        }
    }
}
