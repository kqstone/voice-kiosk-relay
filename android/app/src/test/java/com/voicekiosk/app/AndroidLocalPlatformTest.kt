package com.voicekiosk.app

import com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class AndroidLocalPlatformTest {

    private fun locateManifestFile(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("android/app/src/main/AndroidManifest.xml"),
            File("../android/app/src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not find AndroidManifest.xml", file)
        return file!!
    }

    @Test
    fun testScanPlayers_filtersOutOwnPackage() {
        val ownPkg = "com.voicekiosk.app"
        val scanner = SimulatedMusicPlayerScanner(
            activityCandidates = listOf(
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.voicekiosk.app", "Voice Kiosk App"),
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.netease.cloudmusic", "网易云音乐")
            ),
            serviceCandidates = listOf(
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.voicekiosk.app", "Voice Kiosk Service"),
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.android.music", "系统音乐")
            )
        )

        val players = AndroidLocalPlatform.scanPlayers(ownPkg, scanner)

        assertFalse("Own package must be excluded from player candidates", players.any { it.first == ownPkg })
        assertEquals(2, players.size)
        assertEquals("com.netease.cloudmusic", players[0].first)
        assertEquals("网易云音乐", players[0].second)
        assertEquals("com.android.music", players[1].first)
        assertEquals("系统音乐", players[1].second)
    }

    @Test
    fun testScanPlayers_deduplicatesAcrossActivityAndServiceDimensions() {
        val ownPkg = "com.voicekiosk.app"
        val scanner = SimulatedMusicPlayerScanner(
            activityCandidates = listOf(
                // Play-from-search activity
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.netease.cloudmusic", "网易云音乐"),
                // Category music activity
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.netease.cloudmusic", "网易云音乐"),
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.tencent.qqmusic", "QQ音乐")
            ),
            serviceCandidates = listOf(
                // Standard MediaBrowserService
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.netease.cloudmusic", "网易云音乐"),
                // AndroidX MediaBrowserServiceCompat
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.netease.cloudmusic", "网易云音乐"),
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.android.music", "系统音乐")
            )
        )

        val players = AndroidLocalPlatform.scanPlayers(ownPkg, scanner)

        // NetEase was returned in 4 queries, but must appear exactly once
        assertEquals(3, players.size)
        val neteaseMatches = players.filter { it.first == "com.netease.cloudmusic" }
        assertEquals(1, neteaseMatches.size)
        assertEquals("网易云音乐", neteaseMatches.first().second)

        val packages = players.map { it.first }
        assertEquals(listOf("com.netease.cloudmusic", "com.tencent.qqmusic", "com.android.music"), packages)
    }

    @Test
    fun testFormatPlayerLabel_uninstalledPackageHandling() {
        val available = listOf(
            "com.android.music" to "系统音乐"
        )
        val uninstalledPkg = "com.netease.cloudmusic"

        // Candidate not in available list and isPackageInstalled returns false
        val label = AndroidLocalPlatform.formatPlayerLabel(
            storedPackage = uninstalledPkg,
            availablePlayers = available,
            isInstalled = { false },
            installedLabel = { null }
        )

        assertEquals("com.netease.cloudmusic（已卸载）", label)
    }

    @Test
    fun testFormatPlayerLabel_installedPackageNotInScannedList() {
        val available = listOf(
            "com.android.music" to "系统音乐"
        )
        val storedPkg = "com.kugou.android"

        val label = AndroidLocalPlatform.formatPlayerLabel(
            storedPackage = storedPkg,
            availablePlayers = available,
            isInstalled = { it == storedPkg },
            installedLabel = { if (it == storedPkg) "酷狗音乐" else null }
        )

        assertEquals("酷狗音乐", label)
    }

    @Test
    fun testFormatPlayerLabel_defaultEmptyPackage() {
        val available = listOf("com.android.music" to "系统音乐")
        assertEquals("自动选择（推荐）", AndroidLocalPlatform.formatPlayerLabel("", available, { true }))
        assertEquals("自动选择（推荐）", AndroidLocalPlatform.formatPlayerLabel("   ", available, { true }))
    }

    @Test
    fun testFormatPlayerLabel_matchingScannedPlayer() {
        val available = listOf(
            "com.netease.cloudmusic" to "网易云音乐",
            "com.android.music" to "系统音乐"
        )
        val label = AndroidLocalPlatform.formatPlayerLabel(
            storedPackage = "com.netease.cloudmusic",
            availablePlayers = available,
            isInstalled = { true }
        )
        assertEquals("网易云音乐", label)
    }

    @Test
    fun testScanPlayers_handlesScannerExceptionGracefully() {
        val ownPkg = "com.voicekiosk.app"
        val failingScanner = SimulatedMusicPlayerScanner(
            activityCandidates = listOf(
                AndroidLocalPlatform.Companion.DiscoveredCandidate("com.android.music", "系统音乐")
            ),
            throwOnServices = true
        )

        val players = AndroidLocalPlatform.scanPlayers(ownPkg, failingScanner)
        assertEquals(1, players.size)
        assertEquals("com.android.music", players[0].first)
    }

    @Test
    fun testAndroidManifest_queriesDeclarationContract() {
        val manifest = locateManifestFile()
        val content = manifest.readText(StandardCharsets.UTF_8)

        assertTrue(
            "AndroidManifest queries must declare MEDIA_PLAY_FROM_SEARCH",
            content.contains("android.media.action.MEDIA_PLAY_FROM_SEARCH")
        )
        assertTrue(
            "AndroidManifest queries must declare CATEGORY_APP_MUSIC",
            content.contains("android.intent.category.APP_MUSIC")
        )
        assertTrue(
            "AndroidManifest queries must declare MediaBrowserService",
            content.contains("android.media.browse.MediaBrowserService")
        )
        assertTrue(
            "AndroidManifest queries must declare MediaBrowserServiceCompat",
            content.contains("androidx.media.MediaBrowserServiceCompat")
        )
    }

    private class SimulatedMusicPlayerScanner(
        private val activityCandidates: List<AndroidLocalPlatform.Companion.DiscoveredCandidate> = emptyList(),
        private val serviceCandidates: List<AndroidLocalPlatform.Companion.DiscoveredCandidate> = emptyList(),
        private val installedPackages: Set<String> = emptySet(),
        private val labelsByPackage: Map<String, String> = emptyMap(),
        private val throwOnActivities: Boolean = false,
        private val throwOnServices: Boolean = false
    ) : AndroidLocalPlatform.Companion.MusicPlayerScanner {

        override fun queryActivityPlayers(): List<AndroidLocalPlatform.Companion.DiscoveredCandidate> {
            if (throwOnActivities) throw RuntimeException("Simulated activity query failure")
            return activityCandidates
        }

        override fun queryServicePlayers(): List<AndroidLocalPlatform.Companion.DiscoveredCandidate> {
            if (throwOnServices) throw RuntimeException("Simulated service query failure")
            return serviceCandidates
        }

        override fun isPackageInstalled(packageName: String): Boolean =
            installedPackages.contains(packageName)

        override fun getApplicationLabel(packageName: String): String? =
            labelsByPackage[packageName]
    }
}
