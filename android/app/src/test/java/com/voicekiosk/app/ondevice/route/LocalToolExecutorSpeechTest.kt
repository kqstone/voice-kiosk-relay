package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import com.voicekiosk.app.ondevice.tools.PlaySearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 unit tests verifying exact spoken reply wording across 5 distinct outcomes:
 * 1. Success with a named player (and background silent playback announcement)
 * 2. BACKGROUND_NOT_SUPPORTED guidance
 * 3. Uninstalled-config fallback announcement with fallback player name
 * 4. No player at all with settings suggestion
 * 5. Connection failure with retry suggestion
 */
class LocalToolExecutorSpeechTest {

    private fun d(intent: String, vararg slots: Pair<String, String>, source: RouteSource = RouteSource.RULE) =
        RouteDecision(intent, mapOf(*slots), Route.LOCAL, 0.98, source)

    @Test
    fun testSuccessWithNamedPlayer() {
        val platform = FakePlatform()
        platform.playResult = PlaySearchResult.STARTED
        platform.customPlayerName = "网易云音乐"
        val executor = LocalToolExecutor(platform)

        // Scenario 1a: Artist and song
        val res1 = executor.execute(d(Intents.MEDIA_PLAY, "artist" to "周杰伦", "song" to "晴天"))
        assertTrue("Tool must handle play intent successfully", res1.handled)
        assertFalse("Tool must not fall back to cloud on success", res1.fallbackToCloud)
        assertEquals("已让网易云音乐在后台搜索播放周杰伦的「晴天」。", res1.reply)

        // Scenario 1b: Song only
        platform.customPlayerName = "系统音乐"
        val res2 = executor.execute(d(Intents.MEDIA_PLAY, "song" to "青花瓷"))
        assertTrue(res2.handled)
        assertFalse(res2.fallbackToCloud)
        assertEquals("已让系统音乐在后台搜索播放「青花瓷」。", res2.reply)

        // Scenario 1c: Artist only ("陈奕迅的歌" per Phase 5 acceptance criteria)
        platform.customPlayerName = "网易云音乐"
        val res3 = executor.execute(d(Intents.MEDIA_PLAY, "artist" to "陈奕迅"))
        assertTrue(res3.handled)
        assertFalse(res3.fallbackToCloud)
        assertEquals("已让网易云音乐在后台搜索播放陈奕迅的歌。", res3.reply)

        // Scenario 1d: Query only
        platform.customPlayerName = "QQ音乐"
        val res4 = executor.execute(d(Intents.MEDIA_PLAY, "query" to "稻香"))
        assertTrue(res4.handled)
        assertFalse(res4.fallbackToCloud)
        assertEquals("已让QQ音乐在后台搜索播放「稻香」。", res4.reply)
    }

    @Test
    fun testBackgroundNotSupportedGuidance() {
        val platform = FakePlatform()
        platform.playResult = PlaySearchResult.BACKGROUND_NOT_SUPPORTED
        platform.customPlayerName = "YouTube"
        val executor = LocalToolExecutor(platform)

        // Scenario 2a: Named unsupported player (e.g., YouTube)
        val res1 = executor.execute(d(Intents.MEDIA_PLAY, "query" to "晴天"))
        assertTrue(res1.handled)
        assertFalse(res1.fallbackToCloud)
        assertEquals("【YouTube】不支持后台静默点歌，请在设置中切换为网易云音乐或系统音乐。", res1.reply)

        // Scenario 2b: Unnamed / null player
        platform.customPlayerName = null
        val res2 = executor.execute(d(Intents.MEDIA_PLAY, "query" to "晴天"))
        assertTrue(res2.handled)
        assertFalse(res2.fallbackToCloud)
        assertEquals("当前播放器不支持后台静默点歌，请在设置中切换为网易云音乐或系统音乐。", res2.reply)
    }

    @Test
    fun testUninstalledConfigFallbackAnnouncement() {
        val platform = FakePlatform()
        platform.playResult = PlaySearchResult.UNINSTALLED_FALLBACK
        platform.customPlayerName = "网易云音乐"
        val executor = LocalToolExecutor(platform)

        // Scenario 3a: Fallback with artist + song
        val res1 = executor.execute(d(Intents.MEDIA_PLAY, "artist" to "周杰伦", "song" to "晴天"))
        assertTrue(res1.handled)
        assertFalse(res1.fallbackToCloud)
        assertEquals("之前设置的播放器已卸载，已自动切换为网易云音乐在后台搜索播放周杰伦的「晴天」。", res1.reply)

        // Scenario 3b: Fallback with song only
        platform.customPlayerName = "系统音乐"
        val res2 = executor.execute(d(Intents.MEDIA_PLAY, "song" to "稻香"))
        assertTrue(res2.handled)
        assertFalse(res2.fallbackToCloud)
        assertEquals("之前设置的播放器已卸载，已自动切换为系统音乐在后台搜索播放「稻香」。", res2.reply)

        // Scenario 3c: Fallback with artist only
        platform.customPlayerName = "网易云音乐"
        val res3 = executor.execute(d(Intents.MEDIA_PLAY, "artist" to "陈奕迅"))
        assertTrue(res3.handled)
        assertFalse(res3.fallbackToCloud)
        assertEquals("之前设置的播放器已卸载，已自动切换为网易云音乐在后台搜索播放陈奕迅的歌。", res3.reply)
    }

    @Test
    fun testNoPlayerAtAll() {
        val platform = FakePlatform()
        platform.playResult = PlaySearchResult.NO_PLAYER
        val executor = LocalToolExecutor(platform)

        val res = executor.execute(d(Intents.MEDIA_PLAY, "query" to "稻香"))
        assertTrue(res.handled)
        assertFalse(res.fallbackToCloud)
        assertEquals("本机没有找到支持后台点歌的播放器，请在设置中查看。", res.reply)
    }

    @Test
    fun testConnectionFailure() {
        val platform = FakePlatform()
        platform.playResult = PlaySearchResult.FAILED
        platform.customPlayerName = "QQ音乐"
        val executor = LocalToolExecutor(platform)

        // Scenario 5a: Named player connection failure
        val res1 = executor.execute(d(Intents.MEDIA_PLAY, "query" to "晴天"))
        assertTrue(res1.handled)
        assertFalse(res1.fallbackToCloud)
        assertEquals("无法连接到【QQ音乐】，请稍后重试。", res1.reply)

        // Scenario 5b: Unnamed / null player connection failure
        platform.customPlayerName = null
        val res2 = executor.execute(d(Intents.MEDIA_PLAY, "query" to "晴天"))
        assertTrue(res2.handled)
        assertFalse(res2.fallbackToCloud)
        assertEquals("无法连接到播放器，请稍后重试。", res2.reply)
    }
}
