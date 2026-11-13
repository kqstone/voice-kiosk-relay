package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.ondevice.tools.LocalPlatform
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import com.voicekiosk.app.ondevice.tools.MediaAction
import com.voicekiosk.app.ondevice.tools.PlaySearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolExecutorTest {
    private val p = FakePlatform()
    private val x = LocalToolExecutor(p)
    private fun d(intent: String, vararg slots: Pair<String, String>, source: RouteSource = RouteSource.RULE) =
        RouteDecision(intent, mapOf(*slots), Route.LOCAL, 0.98, source)

    @Test
    fun timeAndDate() {
        assertEquals("现在是下午3点5分。", x.execute(d(Intents.TIME, "kind" to "time")).reply)
        assertEquals("今天是2026年11月10日，星期二。", x.execute(d(Intents.TIME, "kind" to "date", "day_offset" to "0")).reply)
        assertEquals("明天是星期三。", x.execute(d(Intents.TIME, "kind" to "weekday", "day_offset" to "1")).reply)
        assertEquals("早上8点整", LocalToolExecutor.spokenTime(8, 0))
        assertEquals("凌晨12点30分", LocalToolExecutor.spokenTime(0, 30))
        assertEquals("中午12点整", LocalToolExecutor.spokenTime(12, 0))
        assertEquals("晚上11点59分", LocalToolExecutor.spokenTime(23, 59))
    }

    @Test
    fun volume() {
        assertEquals("音量已调到33%。", x.execute(d(Intents.VOLUME, "level" to "30")).reply)
        assertEquals(5, p.volIndex)
        x.execute(d(Intents.VOLUME, "direction" to "up"))
        assertEquals(7, p.volIndex)
        x.execute(d(Intents.VOLUME, "direction" to "down"))
        assertEquals(5, p.volIndex)
        p.volIndex = 1
        x.execute(d(Intents.VOLUME, "direction" to "down"))
        assertEquals("smaller never mutes", 1, p.volIndex)
        p.volIndex = 15
        assertEquals("已经是最大音量了。", x.execute(d(Intents.VOLUME, "direction" to "up")).reply)
        x.execute(d(Intents.VOLUME, "direction" to "mute"))
        assertTrue(p.mutedState)
        x.execute(d(Intents.VOLUME, "level" to "50"))
        assertFalse("setting a level unmutes", p.mutedState)
    }

    @Test
    fun mediaKeysAndSearch() {
        assertEquals("已暂停。", x.execute(d(Intents.MEDIA_CONTROL, "action" to "pause")).reply)
        x.execute(d(Intents.MEDIA_CONTROL, "action" to "next"))
        x.execute(d(Intents.MEDIA_CONTROL, "action" to "resume"))
        assertEquals(listOf(MediaAction.PAUSE, MediaAction.NEXT, MediaAction.PLAY), p.mediaActions)
        val r = x.execute(d(Intents.MEDIA_PLAY, "artist" to "周杰伦", "song" to "晴天"))
        assertEquals("已让QQ音乐在后台搜索播放周杰伦的「晴天」。", r.reply)
        assertEquals(Triple("周杰伦 晴天", "周杰伦", "晴天"), p.lastSearch)
        p.playResult = PlaySearchResult.UNINSTALLED_FALLBACK
        val fallback = x.execute(d(Intents.MEDIA_PLAY, "artist" to "周杰伦", "song" to "晴天"))
        assertTrue("honest answer, no cloud fallback", fallback.handled && !fallback.fallbackToCloud)
        assertEquals("之前设置的播放器已卸载，已自动切换为网易云音乐在后台搜索播放周杰伦的「晴天」。", fallback.reply)
        p.playResult = PlaySearchResult.FAILED
        val failed = x.execute(d(Intents.MEDIA_PLAY, "query" to "晴天"))
        assertTrue("honest answer, no cloud fallback", failed.handled && !failed.fallbackToCloud)
        assertEquals("无法连接到播放器，请稍后重试。", failed.reply)
        p.playResult = PlaySearchResult.NO_PLAYER
        val none = x.execute(d(Intents.MEDIA_PLAY, "query" to "稻香"))
        assertTrue("honest answer, no cloud fallback", none.handled && !none.fallbackToCloud)
        assertTrue(none.reply.contains("没有找到"))
        p.playResult = PlaySearchResult.BACKGROUND_NOT_SUPPORTED
        val unsupported = x.execute(d(Intents.MEDIA_PLAY, "query" to "七里香"))
        assertTrue("honest answer, no cloud fallback", unsupported.handled && !unsupported.fallbackToCloud)
        assertEquals("当前播放器不支持后台静默点歌，请在设置中切换为网易云音乐或系统音乐。", unsupported.reply)
        val customPlatform = object : LocalPlatform by p {
            override fun playFromSearch(query: String, artist: String?, song: String?): Pair<PlaySearchResult, String?> {
                return PlaySearchResult.BACKGROUND_NOT_SUPPORTED to "YouTube"
            }
        }
        val customX = LocalToolExecutor(customPlatform)
        val namedUnsupported = customX.execute(d(Intents.MEDIA_PLAY, "query" to "七里香"))
        assertTrue("honest answer with player name", namedUnsupported.handled && !namedUnsupported.fallbackToCloud)
        assertEquals("【YouTube】不支持后台静默点歌，请在设置中切换为网易云音乐或系统音乐。", namedUnsupported.reply)
        p.mediaOk = false
        assertTrue(x.execute(d(Intents.MEDIA_CONTROL, "action" to "pause")).fallbackToCloud)
    }

    @Test
    fun timers() {
        assertEquals("好的，开始计时5分钟。", x.execute(d(Intents.TIMER, "seconds" to "300")).reply)
        assertEquals("好的，半小时后提醒你关火。", x.execute(d(Intents.TIMER, "seconds" to "1800", "label" to "关火")).reply)
        assertEquals("model gives minutes", "好的，开始计时2分钟。", x.execute(d(Intents.TIMER, "minutes" to "2", source = RouteSource.MODEL)).reply)
        assertEquals(3, p.timers.size)
        assertEquals("已取消计时。", x.execute(d(Intents.TIMER, "action" to "cancel")).reply)
        assertEquals("现在没有正在进行的计时。", x.execute(d(Intents.TIMER, "action" to "cancel")).reply)
        assertEquals("1小时30分钟5秒", LocalToolExecutor.spokenDuration(5405))
        assertEquals("1个半小时", LocalToolExecutor.spokenDuration(5400))
    }

    @Test
    fun validationRejectsAnythingOffTheWhitelist() {
        assertNotNull(x.validate(d(Intents.VOLUME, "level" to "150")))
        assertNotNull(x.validate(d(Intents.VOLUME, "level" to "abc")))
        assertNotNull(x.validate(d(Intents.VOLUME)))
        assertNotNull(x.validate(d(Intents.VOLUME, "direction" to "sideways")))
        assertNotNull(x.validate(d(Intents.MEDIA_CONTROL, "action" to "delete_all")))
        assertNotNull(x.validate(d(Intents.MEDIA_PLAY)))
        assertNotNull(x.validate(d(Intents.MEDIA_PLAY, "song" to "x".repeat(40))))
        assertNotNull(x.validate(d(Intents.TIMER, "minutes" to "999")))
        assertNotNull(x.validate(d(Intents.TIMER)))
        assertNotNull(x.validate(d(Intents.WEATHER, "city" to "上海")))
        assertNotNull(x.validate(d(Intents.CHITCHAT)))
        assertNotNull(x.validate(d(Intents.MEMORY)))
        assertNotNull(x.validate(d(Intents.TIME, "day_offset" to "30")))
        assertNull(x.validate(d(Intents.VOLUME, "level" to "30")))
        assertTrue(x.execute(d(Intents.WEATHER)).fallbackToCloud)
    }
}
