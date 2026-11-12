package com.voicekiosk.app.memory

import com.voicekiosk.app.ondevice.tools.CityTable

/**
 * Deterministic parts of memory extraction (all tiers):
 * - [eligible]: which utterances may be looked at at all (first-person, declarative, not a
 *   question / request / command / hypothetical / reported speech / negation / memory
 *   command, not sensitive). Applied before any extractor.
 * - [extract]: explicit statement patterns → canonical facts.
 * - [validate]: the canonical wording each category must have, and grounding (the variable
 *   part must be words the user actually said). Applied to model output too.
 */
object MemoryRules {
    private val QUESTION = Regex("(吗|呢|么|嘛)[。！!？?]*$|[？?]|什么|怎么|为什么|为啥|哪|谁|来着|几点了|多少|是不是|能不能|可不可以|有没有|要不要|对不对|好不好|行不行")
    private val NOT_STATEMENT = Regex(
        "(他说|她说|他们说|听说|据说|别人|有人|朋友说|如果|假如|要是|万一|假设|也许|可能|好像|" +
            "不|没|(?<!特)别|勿|" +
            "想|要|帮我|给我|请|麻烦|能|可以|让|" +
            "播放|放一|放点|来一|来点|打开|关掉|关闭|调|设置|设个|定个|提醒|计时|闹钟|" +
            "记住|记得|忘掉|忘记|忘了|删除|更正|修改)"
    )
    /** One-off events are not stable facts. */
    private val TRANSIENT = Regex("今天|明天|昨天|后天|前天|今晚|明早|明晚|昨晚|刚才|刚刚|现在|待会|一会|马上|最近|这周|下周|上周|这几天|前几天|这次|这回")
    /** Statements about other people (colleagues, friends, "he/she") are not the family's facts. */
    private val THIRD_PARTY = Regex("同事|朋友|同学|邻居|老板|领导|室友|亲戚|他|她|人家|大家都喜欢")
    private const val RELATIONS = "儿子|女儿|闺女|老婆|老公|妻子|丈夫|爱人|爸爸|妈妈|父亲|母亲|爷爷|奶奶|姥姥|姥爷|外公|外婆|哥哥|姐姐|弟弟|妹妹|孩子|宝宝"
    /** "我女儿喜欢…": the subject is a family member, only a FAMILY name fact may come from it. */
    private val RELATIVE_SUBJECT = Regex("^我(?:们家的|们家|家的|家|的)?(?:$RELATIONS)")
    private val FIRST_PERSON = Regex("我|咱家|俺")
    private val NICKNAME_FIRST = Regex("^(?:以后|你以后|以后你|你)?(?:就)?(?:叫|喊|称呼)我")

    private fun clean(t: String): String = t.trim().replace(Regex("[\\s，,。.！!～~]+$"), "").replace(Regex("\\s+"), "")

    /** Names may contain 不/没/别 (e.g. a singer called 毛不易): judge the rest of the sentence. */
    private val BAD_NAME = Regex("[我你他她谁]|不喜欢|不爱|没有")

    private fun maskNames(t: String): String {
        for (re in listOf(MUSIC_LIKE, MUSIC_LIKE2, FAV_SINGER, FAV_SONG)) {
            val g = re.matchEntire(t)?.groups?.get(1) ?: continue
            if (BAD_NAME.containsMatchIn(g.value)) return t
            return t.replaceRange(g.range, "某")
        }
        return t
    }

    fun eligible(utterance: String): Boolean {
        val raw = clean(utterance)
        if (raw.length !in 4..60) return false
        if (SensitiveFilter.isSensitive(raw)) return false
        val t = maskNames(raw)
        if (QUESTION.containsMatchIn(t)) return false
        val nick = NICKNAME_FIRST.containsMatchIn(t)
        val device = DEVICE_RE.matches(t)
        if (TRANSIENT.containsMatchIn(t) || THIRD_PARTY.containsMatchIn(t)) return false
        if (!nick && !device && NOT_STATEMENT.containsMatchIn(t)) return false
        if (!nick && !device && !FIRST_PERSON.containsMatchIn(t)) return false
        return true
    }

    private const val CJK = "[\\u4e00-\\u9fa5A-Za-z0-9·]"
    private val MUSIC_LIKE = Regex("^我(?:这人)?(?:就)?(?:最|特别|比较|很|超|一直|平时|一般|都)?(?:喜欢|爱)听($CJK{1,12}?)(?:的歌|的音乐|唱的歌|的曲子)?(?:了|啦)?$")
    private val MUSIC_LIKE2 = Regex("^($CJK{1,12}?)的歌我(?:最|特别|比较|很|超|一直)?(?:喜欢|爱)(?:听)?(?:了|啦)?$")
    private val FAV_SINGER = Regex("^我最(?:喜欢|爱)的(?:歌手|歌星|乐队|组合)是($CJK{1,12})$")
    private val FAV_SONG = Regex("^我最(?:喜欢|爱)的歌是($CJK{1,16})$")
    private val NICKNAME = Regex("^(?:以后|你以后|以后你|你)?(?:就)?(?:叫|喊|称呼)我($CJK{1,6}?)(?:吧|就行|就好|就可以|好了)?$")
    private val NICK_BAD = Regex("起床|起来|吃饭|一声|一下|去|过来|回家|名字|什么|干嘛|睡觉|点")
    private const val NUM = "[零一二两三四五六七八九十\\d]"
    private val ROUTINE = Regex(
        "^我(?:每天|一般|通常|平时|基本|都是|一般都)(?:是|都)?(早上|晚上|中午|下午|凌晨)?($NUM{1,3}点(?:半|$NUM{1,3}分?)?)(?:左右)?(?:就)?(起床|睡觉|上床|午睡|下班|到家|吃晚饭|吃早饭|吃午饭)(?:了)?$"
    )
    private val CITY = Regex("^我(?:们)?(?:家|一家人|一家)?(?:住在|家在|生活在|定居在|常住)($CJK{2,6}?)(?:市)?$")
    private val FAMILY = Regex(
        "^我(?:们家的|们家|家的|家|的)?(儿子|女儿|闺女|老婆|老公|妻子|丈夫|爱人|爸爸|妈妈|父亲|母亲|爷爷|奶奶|姥姥|姥爷|外公|外婆|哥哥|姐姐|弟弟|妹妹|孩子|宝宝|狗狗|猫咪|狗|猫|小狗|小猫)(?:的名字)?叫($CJK{1,4})$"
    )
    private val EAT = Regex("^我(?:最|特别|比较|很|超|一直)?(?:喜欢|爱)(吃|喝)($CJK{1,10}?)(?:了|啦)?$")
    private val DEVICE_RE = Regex("^(?:以后|你以后|以后你)?(?:你)?(说话|回答|回复|讲话)(?:能|可以)?(?:再)?(简短|短|慢|简单|简洁)一(?:点|些)(?:吧|就好|就行)?$")

    fun extract(utterance: String): List<MemoryFact> {
        if (!eligible(utterance)) return emptyList()
        val t = clean(utterance)
        val out = mutableListOf<MemoryFact>()
        fun add(c: MemoryCategory, content: String, key: String = "") {
            val f = MemoryFact(c, content, key, MemoryFact.Source.RULE, t)
            if (validate(f, listOf(t))) out += f
        }
        MUSIC_LIKE.matchEntire(t)?.let { add(MemoryCategory.MUSIC, "喜欢听${it.groupValues[1]}的歌") }
        MUSIC_LIKE2.matchEntire(t)?.let { add(MemoryCategory.MUSIC, "喜欢听${it.groupValues[1]}的歌") }
        FAV_SINGER.matchEntire(t)?.let { add(MemoryCategory.MUSIC, "最喜欢的歌手是${it.groupValues[1]}", "最喜欢的歌手") }
        FAV_SONG.matchEntire(t)?.let { add(MemoryCategory.MUSIC, "最喜欢的歌是${it.groupValues[1]}", "最喜欢的歌") }
        NICKNAME.matchEntire(t)?.let { m -> if (!NICK_BAD.containsMatchIn(m.groupValues[1])) add(MemoryCategory.NICKNAME, "希望被称呼为${m.groupValues[1]}", "称呼") }
        ROUTINE.matchEntire(t)?.let { m ->
            val (part, time, act) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
            add(MemoryCategory.ROUTINE, "通常$part$time$act", "作息·$act")
        }
        CITY.matchEntire(t)?.let { m -> if (CityTable.find(m.groupValues[1]) != null) add(MemoryCategory.CITY, "住在${m.groupValues[1].removeSuffix("市")}", "所在城市") }
        FAMILY.matchEntire(t)?.let { m ->
            val rel = when (m.groupValues[1]) { "狗狗" -> "狗"; "猫咪" -> "猫"; else -> m.groupValues[1] }
            val who = if (rel in setOf("狗", "猫", "小狗", "小猫")) "家里的$rel" else rel
            add(MemoryCategory.FAMILY, "${who}叫${m.groupValues[2]}", "称呼·$who")
        }
        EAT.matchEntire(t)?.let { add(MemoryCategory.PREFERENCE, "喜欢${it.groupValues[1]}${it.groupValues[2]}") }
        DEVICE_RE.matchEntire(t)?.let { m ->
            val what = if (m.groupValues[2] == "慢") "希望说话慢一点" else "希望回答简短一些"
            add(MemoryCategory.DEVICE, what, if (what.startsWith("希望说话")) "语速" else "回答风格")
        }
        return out.distinctBy { it.content }
    }

    private val GENERIC_MUSIC = setOf("歌", "歌曲", "音乐", "曲子", "音乐会", "歌儿", "老歌", "新歌")

    private val TEMPLATES: Map<MemoryCategory, List<Regex>> = mapOf(
        MemoryCategory.MUSIC to listOf(Regex("^喜欢听(.{1,12})的歌$"), Regex("^最喜欢的(?:歌手|歌)是(.{1,16})$")),
        MemoryCategory.NICKNAME to listOf(Regex("^希望被称呼为(.{1,6})$")),
        MemoryCategory.ROUTINE to listOf(Regex("^通常(?:早上|晚上|中午|下午|凌晨)?($NUM{1,3}点(?:半|$NUM{1,3}分?)?)(?:起床|睡觉|上床|午睡|下班|到家|吃晚饭|吃早饭|吃午饭)$")),
        MemoryCategory.CITY to listOf(Regex("^住在(.{2,6})$")),
        MemoryCategory.DEVICE to listOf(Regex("^希望说话慢一点$"), Regex("^希望回答简短一些$")),
        MemoryCategory.FAMILY to listOf(Regex("^(?:家里的)?(?:儿子|女儿|闺女|老婆|老公|妻子|丈夫|爱人|爸爸|妈妈|父亲|母亲|爷爷|奶奶|姥姥|姥爷|外公|外婆|哥哥|姐姐|弟弟|妹妹|孩子|宝宝|狗|猫|小狗|小猫)叫(.{1,4})$")),
        MemoryCategory.PREFERENCE to listOf(Regex("^喜欢(?:吃|喝)(.{1,10})$"))
    )

    /**
     * Canonical wording for the category, not sensitive, and grounded: the variable part
     * (and for routines the time) appears verbatim in one of [utterances].
     */
    fun validate(f: MemoryFact, utterances: List<String>): Boolean {
        if (SensitiveFilter.isSensitive(f.content) || SensitiveFilter.isSensitive(f.key)) return false
        val m = TEMPLATES[f.category].orEmpty().firstNotNullOfOrNull { it.matchEntire(f.content) } ?: return false
        val variable = m.groupValues.getOrNull(1).orEmpty()
        // ground on the utterance(s) that contain the variable part
        val src = utterances.map { clean(it) }.filter { variable.isEmpty() || it.contains(variable) }
        if (src.isEmpty()) return false
        if (f.category != MemoryCategory.FAMILY && src.all { RELATIVE_SUBJECT.containsMatchIn(it) }) return false
        val said = src.joinToString("\n")
        when (f.category) {
            MemoryCategory.FAMILY -> {
                if (!said.contains("叫$variable")) return false
                val rel = f.content.substringBefore("叫").removePrefix("家里的").removePrefix("小")
                if (!said.contains(rel)) return false
            }
            MemoryCategory.NICKNAME -> if (!Regex("(?:叫|喊|称呼)(?:我)?" + Regex.escape(variable)).containsMatchIn(said)) return false
            MemoryCategory.PREFERENCE -> if (!said.contains(f.content.substring(2, 3))) return false // 吃 / 喝
            MemoryCategory.ROUTINE -> {
                val act = Regex("(起床|睡觉|上床|午睡|下班|到家|吃晚饭|吃早饭|吃午饭)$").find(f.content)?.value.orEmpty()
                val root = when (act) { "睡觉" -> "睡"; "起床" -> "起"; "上床" -> "床"; "午睡" -> "午睡"; else -> act }
                if (!said.contains(root)) return false
                val part = Regex("(早上|晚上|中午|下午|凌晨)").find(f.content)?.value
                if (part != null && !said.contains(part + variable)) return false
            }
            else -> {}
        }
        if (f.category == MemoryCategory.CITY && CityTable.find(variable) == null) return false
        if (f.category == MemoryCategory.MUSIC && variable in GENERIC_MUSIC) return false
        if (f.category == MemoryCategory.NICKNAME && NICK_BAD.containsMatchIn(variable)) return false
        if (f.category == MemoryCategory.DEVICE && !utterances.any { DEVICE_RE.matches(clean(it)) }) return false
        return true
    }

    /** Model output → the wording the rules would produce (e.g. keeps the part of day the user said). */
    fun canonicalize(f: MemoryFact, utterances: List<String>): MemoryFact {
        if (f.category != MemoryCategory.ROUTINE) return f
        val m = Regex("^通常($NUM{1,3}点(?:半|$NUM{1,3}分?)?)(.+)$").matchEntire(f.content) ?: return f
        val part = utterances.firstNotNullOfOrNull { Regex("(早上|晚上|中午|下午|凌晨)" + Regex.escape(m.groupValues[1])).find(clean(it))?.groupValues?.get(1) } ?: return f
        return f.copy(content = "通常$part${m.groupValues[1]}${m.groupValues[2]}")
    }

    /** Spaces/punctuation-insensitive form for dedupe (bridge dedupes on whitespace + case). */
    fun norm(s: String): String = s.replace(Regex("[\\s，,。.！!？?、；;：:\"'“”‘’]"), "").lowercase()
}
