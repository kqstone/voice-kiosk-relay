package com.voicekiosk.app.ondevice.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleRouterTest {
    private val r = RuleRouter()

    private fun local(text: String, intent: String, vararg slots: Pair<String, String>, prev: RouteDecision? = null) {
        val d = r.route(text, prev)
        assertTrue("'$text' should match $intent, got $d", d != null)
        assertEquals("'$text'", intent, d!!.intent)
        assertEquals("'$text'", Route.LOCAL, d.route)
        assertEquals("'$text'", mapOf(*slots), d.slots)
        assertEquals(RouteSource.RULE, d.source)
    }

    private fun none(text: String, prev: RouteDecision? = null) {
        val d = r.route(text, prev)
        assertTrue("'$text' must not be handled locally, got $d", d == null || d.route == Route.CLOUD)
    }

    private fun cloud(text: String, intent: String) {
        val d = r.route(text)
        assertEquals("'$text'", Route.CLOUD, d?.route)
        assertEquals("'$text'", intent, d?.intent)
    }

    @Test
    fun timeQueries() {
        for (t in listOf("现在几点了", "现在几点", "几点了", "几点了？", "现在几点钟", "几点钟了", "现在是几点", "现在什么时间",
                "现在时间", "报时", "小智，现在几点了", "请问现在几点了", "北京时间几点", "告诉我现在几点了", "现在 几点 了")) {
            local(t, Intents.TIME, "kind" to "time")
        }
    }

    @Test
    fun dateAndWeekdayQueries() {
        local("今天几号", Intents.TIME, "kind" to "date", "day_offset" to "0")
        local("今天是几月几号", Intents.TIME, "kind" to "date", "day_offset" to "0")
        local("今天几月几号星期几", Intents.TIME, "kind" to "date", "day_offset" to "0")
        local("今天日期", Intents.TIME, "kind" to "date", "day_offset" to "0")
        local("明天几号", Intents.TIME, "kind" to "date", "day_offset" to "1")
        local("后天是几号", Intents.TIME, "kind" to "date", "day_offset" to "2")
        local("昨天几号", Intents.TIME, "kind" to "date", "day_offset" to "-1")
        local("今天星期几", Intents.TIME, "kind" to "weekday", "day_offset" to "0")
        local("今天礼拜几", Intents.TIME, "kind" to "weekday", "day_offset" to "0")
        local("今天周几", Intents.TIME, "kind" to "weekday", "day_offset" to "0")
        local("星期几", Intents.TIME, "kind" to "weekday", "day_offset" to "0")
        local("明天星期几", Intents.TIME, "kind" to "weekday", "day_offset" to "1")
        local("现在几月", Intents.TIME, "kind" to "date", "day_offset" to "0")
    }

    @Test
    fun timeNegativesGoToCloud() {
        for (t in listOf("纽约现在几点", "伦敦现在几点了", "现在几点天黑", "几点下班", "明天几点日出", "国庆节是几号", "今天是什么日子",
                "下周三是几号", "几点了你知道吗我想问问天气", "今天", "现在", "时间过得真快", "春节放几天假", "一年有多少天")) {
            none(t)
        }
    }

    @Test
    fun volumeLevels() {
        local("把音量调到30", Intents.VOLUME, "level" to "30")
        local("音量调到三十", Intents.VOLUME, "level" to "30")
        local("音量30", Intents.VOLUME, "level" to "30")
        local("声音调到百分之五十", Intents.VOLUME, "level" to "50")
        local("音量调到50%", Intents.VOLUME, "level" to "50")
        local("音量设为八十", Intents.VOLUME, "level" to "80")
        local("声音开到一百", Intents.VOLUME, "level" to "100")
        local("音量调到零", Intents.VOLUME, "level" to "0")
        local("音量调到最大", Intents.VOLUME, "level" to "100")
        local("最大音量", Intents.VOLUME, "level" to "100")
        local("声音开到最大", Intents.VOLUME, "level" to "100")
        local("音量调到一半", Intents.VOLUME, "level" to "50")
        local("音量调到最小", Intents.VOLUME, "level" to "10")
        none("音量调到一百五")
        none("音量调到150")
    }

    @Test
    fun volumeRelative() {
        for (t in listOf("声音大一点", "大点声", "大声点", "大声一点", "再大点声", "音量调大", "调大音量", "调高音量", "提高音量",
                "把声音调大一点", "声音再大一点", "音量大一些", "声音太小了", "太小声了", "音量加", "调大一点", "声音响一点", "增大音量吧")) {
            local(t, Intents.VOLUME, "direction" to "up")
        }
        for (t in listOf("声音小一点", "小点声", "小声点", "小声一点", "再小点声", "音量调小", "调小音量", "调低音量", "降低音量",
                "把声音调小一点", "声音太大了", "太吵了", "太响了", "轻一点", "音量减小", "关小一点")) {
            local(t, Intents.VOLUME, "direction" to "down")
        }
    }

    @Test
    fun volumeMute() {
        for (t in listOf("静音", "静音模式", "打开静音", "设置静音", "把声音关掉")) local(t, Intents.VOLUME, "direction" to "mute")
        for (t in listOf("取消静音", "关闭静音", "解除静音", "恢复声音", "打开声音")) local(t, Intents.VOLUME, "direction" to "unmute")
    }

    @Test
    fun bareRelativeOnlyAfterVolumeTurn() {
        val prevVol = RouteDecision(Intents.VOLUME, mapOf("direction" to "up"), Route.LOCAL, 0.98, RouteSource.RULE)
        local("再大点", Intents.VOLUME, "direction" to "up", prev = prevVol)
        local("大一点", Intents.VOLUME, "direction" to "up", prev = prevVol)
        local("再小一点", Intents.VOLUME, "direction" to "down", prev = prevVol)
        none("再大点")
        none("大一点")
        val prevWeather = RouteDecision(Intents.WEATHER, emptyMap(), Route.CLOUD, 0.9, RouteSource.MODEL)
        none("小一点", prevWeather)
    }

    @Test
    fun volumeNegatives() {
        for (t in listOf("怎么调大音量", "音量调大会伤耳朵吗", "声音大一点的英文怎么说", "为什么声音这么小", "音量键坏了",
                "把空调温度调到26度", "调大字体", "声音好听的歌手", "把亮度调到30", "音量多少合适")) {
            none(t)
        }
    }

    @Test
    fun mediaControl() {
        for (t in listOf("暂停", "暂停一下", "暂停播放", "停止播放", "别放了", "停一下", "关掉音乐", "暂停音乐吧", "先停一下", "不听了"))
            local(t, Intents.MEDIA_CONTROL, "action" to "pause")
        for (t in listOf("下一首", "下一曲", "切歌", "换一首", "换首歌", "下一首歌", "跳过这首", "播放下一首"))
            local(t, Intents.MEDIA_CONTROL, "action" to "next")
        for (t in listOf("上一首", "上一曲", "播放上一首", "回到上一首"))
            local(t, Intents.MEDIA_CONTROL, "action" to "prev")
        for (t in listOf("继续播放", "接着放", "恢复播放", "播放", "播放音乐", "放音乐", "放点音乐", "来点音乐", "放首歌", "来首歌", "听歌"))
            local(t, Intents.MEDIA_CONTROL, "action" to "resume")
    }

    @Test
    fun bareContinueOnlyAfterMedia() {
        val prevMedia = RouteDecision(Intents.MEDIA_CONTROL, mapOf("action" to "pause"), Route.LOCAL, 0.98, RouteSource.RULE)
        local("继续", Intents.MEDIA_CONTROL, "action" to "resume", prev = prevMedia)
        // after a cloud answer "继续" means "go on talking"
        none("继续")
        none("继续", RouteDecision(Intents.CLOUD_QUESTION, emptyMap(), Route.CLOUD, 0.9, RouteSource.MODEL))
    }

    @Test
    fun mediaNegatives() {
        for (t in listOf("下一首歌叫什么", "上一首是谁唱的", "暂停键在哪", "这首歌叫什么名字", "播放量最高的歌是什么", "播放器坏了",
                "继续说", "接着讲刚才的故事", "停车场在哪", "放假安排", "放大字体", "播放谁的歌好听")) {
            none(t)
        }
    }

    @Test
    fun playNamedSongOrArtist() {
        local("播放周杰伦的晴天", Intents.MEDIA_PLAY, "artist" to "周杰伦", "song" to "晴天")
        local("我想听周杰伦的晴天", Intents.MEDIA_PLAY, "artist" to "周杰伦", "song" to "晴天")
        local("放一首邓紫棋的光年之外", Intents.MEDIA_PLAY, "artist" to "邓紫棋", "song" to "光年之外")
        local("来一首晴天", Intents.MEDIA_PLAY, "query" to "晴天")
        local("放一首晴天这首歌", Intents.MEDIA_PLAY, "query" to "晴天")
        local("播放稻香", Intents.MEDIA_PLAY, "query" to "稻香")
        local("我想听周杰伦的歌", Intents.MEDIA_PLAY, "artist" to "周杰伦")
        local("放周杰伦的歌", Intents.MEDIA_PLAY, "artist" to "周杰伦")
        local("听陈奕迅的歌", Intents.MEDIA_PLAY, "artist" to "陈奕迅")
        local("来点周杰伦的歌", Intents.MEDIA_PLAY, "artist" to "周杰伦")
        local("给我放一首孙燕姿的遇见", Intents.MEDIA_PLAY, "artist" to "孙燕姿", "song" to "遇见")
        local("可以播放周杰伦的稻香吗", Intents.MEDIA_PLAY, "artist" to "周杰伦", "song" to "稻香")
        local("播放歌曲晴天", Intents.MEDIA_PLAY, "query" to "晴天")
    }

    @Test
    fun timers() {
        local("设一个5分钟的计时器", Intents.TIMER, "seconds" to "300")
        local("定一个十分钟的倒计时", Intents.TIMER, "seconds" to "600")
        local("计时5分钟", Intents.TIMER, "seconds" to "300")
        local("倒计时三十秒", Intents.TIMER, "seconds" to "30")
        local("计时器两分钟", Intents.TIMER, "seconds" to "120")
        local("3分钟计时", Intents.TIMER, "seconds" to "180")
        local("半小时后提醒我", Intents.TIMER, "seconds" to "1800")
        local("五分钟后叫我", Intents.TIMER, "seconds" to "300")
        local("10分钟以后提醒我关火", Intents.TIMER, "seconds" to "600", "label" to "关火")
        local("一个半小时后提醒我", Intents.TIMER, "seconds" to "5400")
        local("设置一个1分30秒的计时", Intents.TIMER, "seconds" to "90")
        local("取消计时", Intents.TIMER, "action" to "cancel")
        local("关掉计时器", Intents.TIMER, "action" to "cancel")
    }

    @Test
    fun timerNegatives() {
        for (t in listOf("计时器怎么用", "五分钟有多少秒", "明天早上七点叫我", "设个闹钟明天早上七点", "计时十个小时", "一小时后天气怎么样",
                "提醒我买牛奶")) {
            none(t)
        }
    }

    @Test
    fun weatherQueries() {
        local("今天天气怎么样", Intents.WEATHER, "day" to "today", "kind" to "general")
        local("天气怎么样", Intents.WEATHER, "day" to "today", "kind" to "general")
        local("明天会下雨吗", Intents.WEATHER, "day" to "tomorrow", "kind" to "rain")
        local("北京明天天气怎么样", Intents.WEATHER, "day" to "tomorrow", "kind" to "general", "city" to "北京")
        local("上海市今天多少度", Intents.WEATHER, "day" to "today", "kind" to "temp", "city" to "上海")
        local("明天北京的天气", Intents.WEATHER, "day" to "tomorrow", "kind" to "general", "city" to "北京")
        local("后天要带伞吗", Intents.WEATHER, "day" to "after_tomorrow", "kind" to "rain")
        local("外面冷不冷", Intents.WEATHER, "day" to "today", "kind" to "temp")
        local("今天会不会很冷", Intents.WEATHER, "day" to "today", "kind" to "temp")
        local("昆山市明天下雨吗", Intents.WEATHER, "day" to "tomorrow", "kind" to "rain", "city" to "昆山市")
    }

    @Test
    fun weatherNegatives() {
        // junk "cities", other days, advice: never a local weather rule
        for (t in listOf("小区天气怎么样", "下周天气怎么样", "周末天气", "昨天天气怎么样", "大后天天气", "明天适合爬山吗",
                "天气真好", "我喜欢下雨天", "外面下雨了")) {
            none(t)
        }
    }

    @Test
    fun weatherEllipsisOnlyAfterWeather() {
        val prev = RouteDecision(Intents.WEATHER, mapOf("city" to "北京", "day" to "today", "kind" to "general"), Route.LOCAL, 0.98, RouteSource.RULE)
        local("那明天呢", Intents.WEATHER, "day" to "tomorrow", "kind" to "general", "city" to "北京", prev = prev)
        local("后天呢", Intents.WEATHER, "day" to "after_tomorrow", "kind" to "general", "city" to "北京", prev = prev)
        local("上海呢", Intents.WEATHER, "day" to "today", "kind" to "general", "city" to "上海", prev = prev)
        local("那广州明天呢", Intents.WEATHER, "day" to "tomorrow", "kind" to "general", "city" to "广州", prev = prev)
        local("明天会下雨吗", Intents.WEATHER, "day" to "tomorrow", "kind" to "rain", "city" to "北京", prev = prev)
        none("那明天呢")
        none("那下周呢", prev)
        none("那大后天呢", prev)
        none("那小区呢", prev)
        val prevTime = RouteDecision(Intents.TIME, mapOf("kind" to "time"), Route.LOCAL, 0.98, RouteSource.RULE)
        none("那上海呢", prevTime)
    }

    @Test
    fun smallTalkIsExactOnly() {
        local("你好", Intents.CHITCHAT, "kind" to "greeting", "phrase" to "你好")
        local("谢谢你", Intents.CHITCHAT, "kind" to "thanks", "phrase" to "谢谢你")
        local("再见", Intents.CHITCHAT, "kind" to "bye", "phrase" to "再见")
        local("你真棒", Intents.CHITCHAT, "kind" to "praise", "phrase" to "你真棒")
        local("你最近怎么样", Intents.CHITCHAT, "kind" to "how_are_you", "phrase" to "你最近怎么样")
        for (t in listOf("你好我想问一下怎么减肥", "谢谢你刚才说的是真的吗", "你是谁", "我今天心情不好", "你好厉害你会写诗吗")) none(t)
    }

    @Test
    fun colloquialVolumeAndMedia() {
        local("声音太小了听不清", Intents.VOLUME, "direction" to "up")
        local("听不见大声点", Intents.VOLUME, "direction" to "up")
        local("这首不好听换一首", Intents.MEDIA_CONTROL, "action" to "next")
        local("不喜欢这首歌下一首", Intents.MEDIA_CONTROL, "action" to "next")
    }

    @Test
    fun reset() {
        for (t in listOf("重新开始", "换个话题", "我们重新开始吧", "换一个话题", "重来", "开始新对话")) local(t, Intents.RESET)
    }

    @Test
    fun cloudShortcuts() {
        cloud("天气预报为什么不准", Intents.CLOUD_QUESTION)
        cloud("台风是怎么形成的天气", Intents.CLOUD_QUESTION)
        cloud("北京和上海哪个天气好", Intents.CLOUD_QUESTION)
        cloud("记住我喜欢周杰伦", Intents.MEMORY)
        cloud("忘掉我的生日", Intents.MEMORY)
    }

    @Test
    fun knowledgeAndChatGoToCloudOrModel() {
        for (t in listOf("今天英伟达股价多少", "帮我写一首关于秋天的诗", "给我讲个笑话", "你好吗我想问个问题", "你是谁", "一加一等于几",
                "把这句话翻译成英文", "周杰伦是哪一年出道的", "晴天这首歌讲的是什么", "最近有什么新闻", "", "   ", "。。。")) {
            none(t)
        }
    }

    @Test
    fun normalizeStripsFillersAndPunctuation() {
        assertEquals("几点了", RuleRouter.normalize("小智，请问 现在几点了？".replace("请问 现在", "")))
        assertEquals("暂停", RuleRouter.normalize("麻烦帮我暂停一下吧！"))
        assertEquals("音量调到50%", RuleRouter.normalize("音量调到 50%。"))
    }

    @Test
    fun durationParser() {
        assertEquals(300, RuleRouter.parseDuration("5分钟"))
        assertEquals(1800, RuleRouter.parseDuration("半小时"))
        assertEquals(5400, RuleRouter.parseDuration("一个半小时"))
        assertEquals(7200, RuleRouter.parseDuration("两个小时"))
        assertEquals(90, RuleRouter.parseDuration("1分30秒"))
        assertEquals(30, RuleRouter.parseDuration("半分钟"))
        assertNull(RuleRouter.parseDuration("一会儿"))
        assertNull(RuleRouter.parseDuration("明天"))
    }

    @Test
    fun fastEnough() {
        val utterances = listOf("现在几点了", "把音量调到三十", "播放周杰伦的晴天", "今天英伟达股价多少", "帮我写一首关于秋天的诗", "设一个5分钟的计时器")
        repeat(50) { utterances.forEach { r.route(it) } } // warm up
        val t0 = System.nanoTime()
        val n = 2000
        repeat(n) { utterances.forEach { r.route(it) } }
        val perCallMs = (System.nanoTime() - t0) / 1e6 / (n * utterances.size)
        assertTrue("L0 too slow: $perCallMs ms", perCallMs < 2.0)
    }

    @Test
    fun s31PhrasingsFromPhoneEval() {
        local("小声一点孩子在睡觉", Intents.VOLUME, "direction" to "down")
        local("孩子在睡觉小声点", Intents.VOLUME, "direction" to "down")
        local("声音大一点我在打电话", Intents.VOLUME, "direction" to "up")
        local("暂停一下我要接电话", Intents.MEDIA_CONTROL, "action" to "pause")
        local("音乐先暂停一下", Intents.MEDIA_CONTROL, "action" to "pause")
        local("把音乐停一下", Intents.MEDIA_CONTROL, "action" to "pause")
        local("歌先停下", Intents.MEDIA_CONTROL, "action" to "pause")
        local("切到上一首", Intents.MEDIA_CONTROL, "action" to "prev")
        local("回到上一首歌", Intents.MEDIA_CONTROL, "action" to "prev")
        local("切到下一首", Intents.MEDIA_CONTROL, "action" to "next")
        local("听一下林俊杰的江南", Intents.MEDIA_PLAY, "artist" to "林俊杰", "song" to "江南")
        local("我想听听毛不易的消愁", Intents.MEDIA_PLAY, "artist" to "毛不易", "song" to "消愁")
        local("听听晴天", Intents.MEDIA_PLAY, "query" to "晴天")
        local("来点王菲的", Intents.MEDIA_PLAY, "artist" to "王菲")
        local("过两分钟喊我一声", Intents.TIMER, "seconds" to "120")
        local("等十分钟提醒我关火", Intents.TIMER, "seconds" to "600", "label" to "关火")
        local("明天出门要带伞不", Intents.WEATHER, "day" to "tomorrow", "kind" to "rain")
        local("看看武汉后天天气", Intents.WEATHER, "day" to "after_tomorrow", "kind" to "general", "city" to "武汉")
        local("查一下南京的天气", Intents.WEATHER, "day" to "today", "kind" to "general", "city" to "南京")
        val prev = RouteDecision(Intents.WEATHER, mapOf("city" to "北京", "day" to "today", "kind" to "general"), Route.LOCAL, 0.98, RouteSource.RULE)
        local("明天的天气呢", Intents.WEATHER, "day" to "tomorrow", "kind" to "general", "city" to "北京", prev = prev)
        local("早安", Intents.CHITCHAT, "kind" to "greeting", "phrase" to "早安")
        local("下午好", Intents.CHITCHAT, "kind" to "greeting", "phrase" to "下午好")
    }

    @Test
    fun s31Negatives() {
        for (t in listOf("孩子在睡觉", "我在学习", "听不清", "听懂了吗", "听见了", "暂停是什么意思", "停车", "上一首是什么歌",
                "过两天提醒我", "等一下叫我", "过一会儿叫我", "看看新闻", "查一下快递", "明天的会议呢", "来点吃的", "听说你会唱歌")) {
            none(t)
        }
    }
}
