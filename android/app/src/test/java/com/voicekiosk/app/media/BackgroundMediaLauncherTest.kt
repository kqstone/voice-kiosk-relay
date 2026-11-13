package com.voicekiosk.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class BackgroundMediaLauncherTest {

    private lateinit var fakeActiveSession: FakeActiveSessionClient
    private lateinit var fakeMediaBrowser: FakeMediaBrowserClient
    private lateinit var launcher: BackgroundMediaLauncher

    @Before
    fun setUp() {
        fakeActiveSession = FakeActiveSessionClient()
        fakeMediaBrowser = FakeMediaBrowserClient()
        launcher = BackgroundMediaLauncher(
            activeSessionClient = fakeActiveSession,
            mediaBrowserClient = fakeMediaBrowser,
            connectionTimeoutMs = 3000L
        )
    }

    private fun locateSourceFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/java/com/voicekiosk/app/$relativePath"),
            File("app/src/main/java/com/voicekiosk/app/$relativePath"),
            File("android/app/src/main/java/com/voicekiosk/app/$relativePath"),
            File("../android/app/src/main/java/com/voicekiosk/app/$relativePath")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not find source file: $relativePath", file)
        return file!!
    }

    @Test
    fun testDispatchParameters_viaActiveSession_allParameters() {
        fakeActiveSession.shouldSucceed = true

        val result = launcher.launch(
            targetPackage = "com.netease.cloudmusic",
            query = "周杰伦 晴天",
            artist = "周杰伦",
            song = "晴天"
        )

        assertEquals(BackgroundLaunchResult.SUCCESS, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals("com.netease.cloudmusic", fakeActiveSession.lastPackage)
        assertEquals("周杰伦 晴天", fakeActiveSession.lastQuery)
        assertEquals("周杰伦", fakeActiveSession.lastArtist)
        assertEquals("晴天", fakeActiveSession.lastSong)

        // Active session succeeded, MediaBrowser should not be contacted (tier 1 priority)
        assertEquals(0, fakeMediaBrowser.callCount)
    }

    @Test
    fun testDispatchParameters_viaActiveSession_queryOnly() {
        fakeActiveSession.shouldSucceed = true

        val result = launcher.launch(
            targetPackage = "com.tencent.qqmusic",
            query = "七里香"
        )

        assertEquals(BackgroundLaunchResult.SUCCESS, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals("com.tencent.qqmusic", fakeActiveSession.lastPackage)
        assertEquals("七里香", fakeActiveSession.lastQuery)
        assertNull(fakeActiveSession.lastArtist)
        assertNull(fakeActiveSession.lastSong)
        assertEquals(0, fakeMediaBrowser.callCount)
    }

    @Test
    fun testDispatchParameters_viaMediaBrowser_whenNoActiveSession() {
        // Active session not available, but MediaBrowser succeeds
        fakeActiveSession.shouldSucceed = false
        fakeMediaBrowser.shouldSucceed = true

        val result = launcher.launch(
            targetPackage = "com.tencent.qqmusic",
            query = "周杰伦 稻香",
            artist = "周杰伦",
            song = "稻香"
        )

        assertEquals(BackgroundLaunchResult.SUCCESS, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals(1, fakeMediaBrowser.callCount)
        assertEquals("com.tencent.qqmusic", fakeMediaBrowser.lastPackage)
        assertEquals("周杰伦 稻香", fakeMediaBrowser.lastQuery)
        assertEquals("周杰伦", fakeMediaBrowser.lastArtist)
        assertEquals("稻香", fakeMediaBrowser.lastSong)
        assertEquals(3000L, fakeMediaBrowser.lastTimeoutMs)
    }

    @Test
    fun testDispatchParameters_viaMediaBrowser_queryOnly() {
        fakeActiveSession.shouldSucceed = false
        fakeMediaBrowser.shouldSucceed = true

        val result = launcher.launch(
            targetPackage = "com.android.music",
            query = "夜曲"
        )

        assertEquals(BackgroundLaunchResult.SUCCESS, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals(1, fakeMediaBrowser.callCount)
        assertEquals("com.android.music", fakeMediaBrowser.lastPackage)
        assertEquals("夜曲", fakeMediaBrowser.lastQuery)
        assertNull(fakeMediaBrowser.lastArtist)
        assertNull(fakeMediaBrowser.lastSong)
        assertEquals(3000L, fakeMediaBrowser.lastTimeoutMs)
    }

    @Test
    fun testNotSupported_whenNeitherActiveSessionNorMediaBrowserAvailable() {
        // Neither active session nor MediaBrowser is supported (e.g. YouTube)
        fakeActiveSession.shouldSucceed = false
        fakeMediaBrowser.shouldSucceed = false

        val result = launcher.launch(
            targetPackage = "com.google.android.youtube",
            query = "Never Gonna Give You Up"
        )

        assertEquals(BackgroundLaunchResult.NOT_SUPPORTED, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals(1, fakeMediaBrowser.callCount)
        assertEquals("com.google.android.youtube", fakeActiveSession.lastPackage)
        assertEquals("com.google.android.youtube", fakeMediaBrowser.lastPackage)
    }

    @Test
    fun testNotSupported_onConnectionTimeout() {
        // MediaBrowser connection times out
        fakeActiveSession.shouldSucceed = false
        fakeMediaBrowser.simulateTimeout = true

        val result = launcher.launch(
            targetPackage = "com.netease.cloudmusic",
            query = "晴天"
        )

        assertEquals(BackgroundLaunchResult.NOT_SUPPORTED, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals(1, fakeMediaBrowser.callCount)
        assertTrue(fakeMediaBrowser.simulateTimeout)
        assertEquals(3000L, fakeMediaBrowser.lastTimeoutMs)
    }

    @Test
    fun testCustomTimeout_passedToMediaBrowserClient() {
        val customTimeoutLauncher = BackgroundMediaLauncher(
            activeSessionClient = fakeActiveSession,
            mediaBrowserClient = fakeMediaBrowser,
            connectionTimeoutMs = 5000L
        )
        fakeActiveSession.shouldSucceed = false
        fakeMediaBrowser.shouldSucceed = true

        customTimeoutLauncher.launch("com.netease.cloudmusic", "晴天")

        assertEquals(5000L, fakeMediaBrowser.lastTimeoutMs)
    }

    @Test
    fun testEmptyOrBlankPackageName_returnsNotSupportedWithoutCallingClients() {
        val rEmpty = launcher.launch("", "query")
        val rBlank = launcher.launch("   \t\n", "query")

        assertEquals(BackgroundLaunchResult.NOT_SUPPORTED, rEmpty)
        assertEquals(BackgroundLaunchResult.NOT_SUPPORTED, rBlank)
        assertEquals(0, fakeActiveSession.callCount)
        assertEquals(0, fakeMediaBrowser.callCount)
    }

    @Test
    fun testFailure_whenActiveSessionThrowsException() {
        fakeActiveSession.throwException = true

        val result = launcher.launch("com.netease.cloudmusic", "query")

        assertEquals(BackgroundLaunchResult.FAILED, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals(0, fakeMediaBrowser.callCount)
    }

    @Test
    fun testFailure_whenMediaBrowserThrowsException() {
        fakeActiveSession.shouldSucceed = false
        fakeMediaBrowser.throwException = true

        val result = launcher.launch("com.netease.cloudmusic", "query")

        assertEquals(BackgroundLaunchResult.FAILED, result)
        assertEquals(1, fakeActiveSession.callCount)
        assertEquals(1, fakeMediaBrowser.callCount)
    }

    @Test
    fun testSourceContract_mainThreadBranchDoesNotReturnTrueUnconditionally() {
        val file = locateSourceFile("media/BackgroundMediaLauncher.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        // Verify the main looper branch returns false, not true
        assertTrue("Must check Looper.myLooper() == Looper.getMainLooper()",
            content.contains("Looper.myLooper() == Looper.getMainLooper()"))
        assertFalse(
            "Main-thread branch must not return true unconditionally",
            content.contains("mainHandler.postDelayed({\n                try {\n                    browser?.disconnect()\n                } catch (_: Exception) {}\n            }, timeoutMs)\n            return true")
        )
        assertTrue(
            "Main-thread branch must return false and log asynchronous observation",
            content.contains("outcome will be observed via playback state") &&
                content.contains("return false")
        )
    }

    @Test
    fun testSourceContract_twoTierFallbackArchitecture() {
        val file = locateSourceFile("media/BackgroundMediaLauncher.kt")
        val content = file.readText(StandardCharsets.UTF_8)

        assertTrue(content.contains("activeSessionClient.playFromActiveSession"))
        assertTrue(content.contains("mediaBrowserClient.playFromMediaBrowser"))
        assertTrue(content.contains("ACTION_MEDIA_BROWSER_SERVICE"))
        assertTrue(content.contains("ACTION_MEDIA_BROWSER_SERVICE_COMPAT"))
    }

    /** Fake implementation of [ActiveSessionClient] for unit testing. */
    class FakeActiveSessionClient : ActiveSessionClient {
        var shouldSucceed: Boolean = false
        var lastPackage: String? = null
        var lastQuery: String? = null
        var lastArtist: String? = null
        var lastSong: String? = null
        var callCount: Int = 0
        var throwException: Boolean = false

        override fun playFromActiveSession(
            packageName: String,
            query: String,
            artist: String?,
            song: String?
        ): Boolean {
            callCount++
            lastPackage = packageName
            lastQuery = query
            lastArtist = artist
            lastSong = song
            if (throwException) throw RuntimeException("Simulated ActiveSession exception")
            return shouldSucceed
        }
    }

    /** Fake implementation of [MediaBrowserClient] for unit testing. */
    class FakeMediaBrowserClient : MediaBrowserClient {
        var shouldSucceed: Boolean = false
        var lastPackage: String? = null
        var lastQuery: String? = null
        var lastArtist: String? = null
        var lastSong: String? = null
        var lastTimeoutMs: Long? = null
        var callCount: Int = 0
        var simulateTimeout: Boolean = false
        var throwException: Boolean = false

        override fun playFromMediaBrowser(
            packageName: String,
            query: String,
            artist: String?,
            song: String?,
            timeoutMs: Long
        ): Boolean {
            callCount++
            lastPackage = packageName
            lastQuery = query
            lastArtist = artist
            lastSong = song
            lastTimeoutMs = timeoutMs
            if (throwException) throw RuntimeException("Simulated MediaBrowser exception")
            if (simulateTimeout) return false
            return shouldSucceed
        }
    }
}
