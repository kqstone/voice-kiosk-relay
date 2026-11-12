package com.voicekiosk.app.ondevice.eval

import com.voicekiosk.app.ondevice.route.Intents
import com.voicekiosk.app.ondevice.route.Route
import com.voicekiosk.app.ondevice.route.RouteDecision
import com.voicekiosk.app.ondevice.route.RouteSource

/**
 * Offline routing eval set (S2). Each case: utterance, what the pipeline should do
 * (local intent + key slots, or cloud), and optional history (decisions of the previous
 * turns, oldest first) for ellipsis. Most important are the must-cloud negatives: a
 * question routed locally gets a wrong or canned answer, a missed local only costs
 * latency. Phrases are colloquial ASR-style Chinese (no punctuation).
 */
object RouteEvalSet {
    data class Case(
        val id: String,
        val category: String,
        val text: String,
        /** Expected intent (for cloud cases: what the model should call it). */
        val intent: String,
        val local: Boolean,
        val slots: Map<String, String> = emptyMap(),
        val history: List<RouteDecision> = emptyList()
    )

    fun h(intent: String, vararg slots: Pair<String, String>): RouteDecision = RouteDecision(
        intent, mapOf(*slots),
        if (intent in setOf(Intents.CLOUD_QUESTION, Intents.MEMORY, Intents.BOARD)) Route.CLOUD else Route.LOCAL,
        0.95, RouteSource.RULE
    )

    private val cases = mutableListOf<Case>()
    private fun add(cat: String, text: String, intent: String, local: Boolean, slots: Map<String, String> = emptyMap(), history: List<RouteDecision> = emptyList()) {
        cases += Case("$cat-${cases.count { it.category == cat } + 1}", cat, text, intent, local, slots, history)
    }
    private fun loc(cat: String, text: String, intent: String, vararg slots: Pair<String, String>, history: List<RouteDecision> = emptyList()) =
        add(cat, text, intent, true, mapOf(*slots), history)
    private fun cloud(cat: String, text: String, intent: String = Intents.CLOUD_QUESTION, history: List<RouteDecision> = emptyList()) =
        add(cat, text, intent, false, emptyMap(), history)

    private val W_BJ = h(Intents.WEATHER, "city" to "北京", "day" to "today")
    private val W_SH_TOM = h(Intents.WEATHER, "city" to "上海", "day" to "tomorrow")
    private val W_DEFAULT = h(Intents.WEATHER, "day" to "today")
    private val VOL_UP = h(Intents.VOLUME, "direction" to "up")
    private val VOL_DOWN = h(Intents.VOLUME, "direction" to "down")
    private val PLAY_JAY = h(Intents.MEDIA_PLAY, "artist" to "周杰伦")
    private val CLOUD = h(Intents.CLOUD_QUESTION)
    private val TIME = h(Intents.TIME)

    /** Stratified subset (~[n] cases, every category kept, deterministic) for the phone quick mode. */
    fun quick(n: Int = 60): List<Case> {
        val all = CASES
        val byCat = all.groupBy { it.category }
        return byCat.values.flatMap { cs ->
            val k = maxOf(1, Math.round(cs.size * n.toDouble() / all.size).toInt())
            val step = cs.size.toDouble() / k
            (0 until k).map { cs[(it * step).toInt()] }
        }
    }

    val CASES: List<Case> by lazy {
        cases.clear()
        val T = Intents.TIME
        listOf("现在几点", "几点了", "现在几点钟了", "现在什么时间", "今天几号", "今天星期几", "明天星期几", "今天是几月几号", "告诉我现在的时间", "报时")
            .forEach { loc("time", it, T) }

        val V = Intents.VOLUME
        loc("volume", "声音大一点", V, "direction" to "up")
        loc("volume", "大声点", V, "direction" to "up")
        loc("volume", "太吵了", V, "direction" to "down")
        loc("volume", "声音小一点", V, "direction" to "down")
        loc("volume", "音量调到50", V, "level" to "50")
        loc("volume", "音量调到一半", V, "level" to "50")
        loc("volume", "把声音调到百分之三十", V, "level" to "30")
        loc("volume", "静音", V, "direction" to "mute")
        loc("volume", "取消静音", V, "direction" to "unmute")
        loc("volume", "音量最大", V, "level" to "100")
        loc("volume", "声音太小了听不清", V, "direction" to "up")
        loc("volume", "声音开大点儿", V, "direction" to "up")
        loc("volume", "把声音关小一点", V, "direction" to "down")
        loc("volume", "能不能把音量调高一些", V, "direction" to "up")
        loc("volume", "声音再大一点点", V, "direction" to "up")
        loc("volume", "有点听不见大声一点", V, "direction" to "up")
        loc("volume", "小声一点孩子在睡觉", V, "direction" to "down")
        loc("volume", "再大点", V, "direction" to "up", history = listOf(VOL_UP))
        loc("volume", "再小一点", V, "direction" to "down", history = listOf(VOL_DOWN))
        loc("volume", "还是太吵", V, "direction" to "down", history = listOf(VOL_DOWN))

        val M = Intents.MEDIA_CONTROL
        loc("media", "暂停", M, "action" to "pause")
        loc("media", "先停一下", M, "action" to "pause")
        loc("media", "别放了", M, "action" to "pause")
        loc("media", "继续播放", M, "action" to "resume")
        loc("media", "接着放", M, "action" to "resume")
        loc("media", "下一首", M, "action" to "next")
        loc("media", "换一首", M, "action" to "next")
        loc("media", "这首不好听换一首", M, "action" to "next")
        loc("media", "上一首", M, "action" to "prev")
        loc("media", "切到下一首歌吧", M, "action" to "next")
        loc("media", "音乐先暂停一下", M, "action" to "pause")

        val P = Intents.MEDIA_PLAY
        loc("play", "播放周杰伦的晴天", P, "artist" to "周杰伦", "song" to "晴天")
        loc("play", "我想听晴天", P, "song" to "晴天")
        loc("play", "来一首稻香", P, "song" to "稻香")
        loc("play", "放点周杰伦的歌", P, "artist" to "周杰伦")
        loc("play", "播放陈奕迅的十年", P, "artist" to "陈奕迅", "song" to "十年")
        loc("play", "来首邓紫棋的光年之外", P, "artist" to "邓紫棋", "song" to "光年之外")
        loc("play", "我要听孙燕姿的歌", P, "artist" to "孙燕姿")
        loc("play", "放一首青花瓷", P, "song" to "青花瓷")
        loc("play", "给我放首小幸运", P, "song" to "小幸运")
        loc("play", "听一下林俊杰的江南", P, "artist" to "林俊杰", "song" to "江南")
        loc("play", "换成五月天的歌", P, "artist" to "五月天")
        loc("play", "来点王菲的", P, "artist" to "王菲")

        val TM = Intents.TIMER
        loc("timer", "五分钟后提醒我", TM, "minutes" to "5")
        loc("timer", "计时十分钟", TM, "minutes" to "10")
        loc("timer", "定个三分钟的计时器", TM, "minutes" to "3")
        loc("timer", "设一个20分钟倒计时", TM, "minutes" to "20")
        loc("timer", "半小时后叫我", TM, "minutes" to "30")
        loc("timer", "一个小时后提醒我", TM, "minutes" to "60")
        loc("timer", "十五分钟后提醒我关火", TM, "minutes" to "15")
        loc("timer", "帮我计个时八分钟", TM, "minutes" to "8")
        loc("timer", "过两分钟喊我一声", TM, "minutes" to "2")

        val W = Intents.WEATHER
        loc("weather", "今天天气怎么样", W, "day" to "today")
        loc("weather", "天气怎么样", W, "day" to "today")
        loc("weather", "明天会下雨吗", W, "day" to "tomorrow")
        loc("weather", "后天天气", W, "day" to "after_tomorrow")
        loc("weather", "北京明天天气怎么样", W, "city" to "北京", "day" to "tomorrow")
        loc("weather", "上海今天多少度", W, "city" to "上海", "day" to "today")
        loc("weather", "明天要带伞吗", W, "day" to "tomorrow")
        loc("weather", "外面冷不冷", W, "day" to "today")
        loc("weather", "今天热不热", W, "day" to "today")
        loc("weather", "广州后天下雨吗", W, "city" to "广州", "day" to "after_tomorrow")
        loc("weather", "杭州天气", W, "city" to "杭州")
        loc("weather", "成都今天天气如何", W, "city" to "成都", "day" to "today")
        loc("weather", "深圳明天气温多少", W, "city" to "深圳", "day" to "tomorrow")
        loc("weather", "现在外面多少度", W, "day" to "today")
        loc("weather", "今天有雨吗", W, "day" to "today")
        loc("weather", "明天天气好不好", W, "day" to "tomorrow")
        loc("weather", "查一下南京的天气", W, "city" to "南京")
        loc("weather", "明天出门要带伞不", W, "day" to "tomorrow")
        loc("weather", "看看武汉后天天气", W, "city" to "武汉", "day" to "after_tomorrow")
        loc("weather", "今天会不会很冷", W, "day" to "today")

        loc("weather_ellipsis", "那明天呢", W, "city" to "北京", "day" to "tomorrow", history = listOf(W_BJ))
        loc("weather_ellipsis", "后天呢", W, "city" to "北京", "day" to "after_tomorrow", history = listOf(W_BJ))
        loc("weather_ellipsis", "上海呢", W, "city" to "上海", "day" to "today", history = listOf(W_BJ))
        loc("weather_ellipsis", "那广州明天呢", W, "city" to "广州", "day" to "tomorrow", history = listOf(W_BJ))
        loc("weather_ellipsis", "杭州怎么样", W, "city" to "杭州", "day" to "today", history = listOf(W_BJ))
        loc("weather_ellipsis", "那北京呢", W, "city" to "北京", "day" to "tomorrow", history = listOf(W_SH_TOM))
        loc("weather_ellipsis", "后天怎么样", W, "city" to "上海", "day" to "after_tomorrow", history = listOf(W_SH_TOM))
        loc("weather_ellipsis", "明天呢", W, "day" to "tomorrow", history = listOf(TIME, W_DEFAULT))
        loc("weather_ellipsis", "那明天会下雨吗", W, "city" to "北京", "day" to "tomorrow", history = listOf(W_BJ))
        loc("weather_ellipsis", "那明天的呢", W, "day" to "tomorrow", history = listOf(W_DEFAULT))

        val R = Intents.RESET
        listOf("重新开始", "换个话题", "我们重新聊吧", "清空对话").forEach { loc("reset", it, R) }

        val C = Intents.CHITCHAT
        listOf("你好", "嗨", "谢谢", "谢谢你", "多谢", "早上好", "晚上好", "你最近怎么样", "再见", "拜拜", "晚安", "你真棒", "辛苦了", "太感谢了")
            .forEach { loc("chitchat", it, C) }

        // ---------------- must go to the cloud ----------------
        listOf(
            "怎么把音量调大", "音量键在哪里", "为什么声音这么小", "怎么设置闹钟", "你能帮我调亮屏幕吗", "帮我打开蓝牙",
            "打电话给妈妈", "设个明天早上七点的闹钟", "提醒我下午三点开会", "打开空调", "把灯关了", "帮我订个外卖",
            "导航到最近的加油站", "拍张照片", "音量开太大会伤耳朵吗", "声音是不是有点大", "现在放的是什么歌", "下一首歌叫什么",
            "暂停是什么意思", "这个播放器好用吗"
        ).forEach { cloud("cloud_device", it) }
        listOf("纽约现在几点", "伦敦几点了", "东京现在是几号", "美国现在是晚上吗", "离过年还有几天", "下个月一号是星期几")
            .forEach { cloud("cloud_time", it) }
        listOf(
            "天为什么是蓝的", "珠穆朗玛峰有多高", "周杰伦是哪年出生的", "晴天这首歌是谁唱的", "周杰伦最新的歌是什么",
            "推荐几首好听的歌", "什么歌适合睡觉听", "一百二十三乘以四十五等于多少", "三加五等于几", "地球到月亮有多远",
            "鲁迅原名叫什么", "猫为什么怕水"
        ).forEach { cloud("cloud_knowledge", it) }
        listOf(
            "为什么会下雨", "天气预报准不准", "台风是怎么形成的", "明天适合去爬山吗", "北京和上海哪个冷", "下周天气怎么样",
            "这周末会下雨吗", "昨天北京多少度", "明天出门穿什么衣服好", "今年冬天会不会特别冷", "雾霾对身体有什么危害", "下雨天适合做什么"
        ).forEach { cloud("cloud_weather_like", it) }
        listOf(
            "我头疼怎么办", "晚饭吃什么好", "我该不该换工作", "感冒了吃什么药", "怎么才能睡得好", "孩子不爱吃饭怎么办"
        ).forEach { cloud("cloud_advice", it) }
        listOf(
            "帮我写一首关于秋天的诗", "把你好翻译成英文", "讲个笑话", "讲个故事", "给我出个谜语", "用英语怎么说谢谢", "帮我想个生日祝福"
        ).forEach { cloud("cloud_writing", it) }
        listOf("今天有什么新闻", "茅台股价多少", "比特币现在多少钱", "今晚有什么球赛", "最近有什么好看的电影")
            .forEach { cloud("cloud_realtime", it) }
        cloud("cloud_memory", "记住我喜欢喝茶", Intents.MEMORY)
        cloud("cloud_memory", "忘掉我的生日", Intents.MEMORY)
        cloud("cloud_memory", "你还记得我叫什么吗")
        cloud("cloud_board", "切换到日历看板", Intents.BOARD)
        cloud("cloud_board", "显示一下待办事项", Intents.BOARD)
        listOf("你是谁", "你叫什么名字", "你会做什么", "我今天心情不好", "你喜欢什么颜色", "你觉得我胖吗", "你好我想问一下怎么减肥", "谢谢你刚才说的是真的吗")
            .forEach { cloud("cloud_chat", it) }

        cloud("cloud_ellipsis", "那明天呢", history = listOf(CLOUD))
        cloud("cloud_ellipsis", "再说一个", history = listOf(CLOUD))
        cloud("cloud_ellipsis", "为什么", history = listOf(CLOUD))
        cloud("cloud_ellipsis", "那纽约呢", history = listOf(TIME))
        cloud("cloud_ellipsis", "那下周呢", history = listOf(W_BJ))
        cloud("cloud_ellipsis", "那大后天呢", history = listOf(W_BJ))
        cloud("cloud_ellipsis", "为什么这么冷", history = listOf(W_BJ))
        cloud("cloud_ellipsis", "那穿什么好", history = listOf(W_BJ))
        cloud("cloud_ellipsis", "他还有什么歌", history = listOf(PLAY_JAY))
        cloud("cloud_ellipsis", "详细说说", history = listOf(CLOUD))
        cloud("cloud_ellipsis", "那上海呢", history = listOf(CLOUD))
        cloud("cloud_ellipsis", "还有吗", history = listOf(CLOUD))

        // ---------------- holdout: written after the guards were tuned, never tuned on ----------------
        loc("holdout_local", "把声音调低点", V, "direction" to "down")
        loc("holdout_local", "音量开到七十", V, "level" to "70")
        loc("holdout_local", "关掉声音", V, "direction" to "mute")
        loc("holdout_local", "停一停", M, "action" to "pause")
        loc("holdout_local", "切到上一首", M, "action" to "prev")
        loc("holdout_local", "播放邓丽君的月亮代表我的心", P, "artist" to "邓丽君", "song" to "月亮代表我的心")
        loc("holdout_local", "我想听听毛不易的消愁", P, "artist" to "毛不易", "song" to "消愁")
        loc("holdout_local", "三分钟以后叫我", TM, "minutes" to "3")
        loc("holdout_local", "定个45分钟的闹钟", TM, "minutes" to "45")
        loc("holdout_local", "明天冷不冷", W, "day" to "tomorrow")
        loc("holdout_local", "西安今天下雨吗", W, "city" to "西安", "day" to "today")
        loc("holdout_local", "后天要带伞吗", W, "day" to "after_tomorrow")
        loc("holdout_local", "重庆明天天气", W, "city" to "重庆", "day" to "tomorrow")
        loc("holdout_local", "今儿个天气咋样", W, "day" to "today")
        loc("holdout_local", "明天的天气呢", W, "city" to "北京", "day" to "tomorrow", history = listOf(W_BJ))
        loc("holdout_local", "天津呢", W, "city" to "天津", "day" to "today", history = listOf(W_BJ))
        loc("holdout_local", "早安", C)
        loc("holdout_local", "下午好", C)
        listOf(
            "把屏幕亮度调低", "空调温度调到26度", "帮我设个早上六点半的闹钟", "每天晚上十点提醒我吃药", "明天下午有雨的话提醒我收衣服",
            "下周三天气怎么样", "明天晚上会下雨吗", "一会儿去跑步合适吗", "为什么今天这么热", "哪里能买到伞", "这首歌叫什么名字",
            "周杰伦有哪些好听的歌", "放什么歌好", "音乐能治病吗", "计时器怎么用", "五分钟能做什么菜", "十分钟能跑多远", "你几岁了",
            "你能听懂方言吗", "我好无聊", "北京有什么好玩的", "上海到北京多远", "美元汇率多少", "帮我查一下快递", "声音大的音箱推荐一下",
            "冷笑话讲一个", "下雪了怎么开车"
        ).forEach { cloud("holdout_cloud", it) }
        cases.toList()
    }
}
