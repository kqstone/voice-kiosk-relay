package com.voicekiosk.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class DashboardAndAodContractTest {

    private fun locateAssetFile(relativePath: String): File {
        val candidates = listOf(
            File("src/main/assets/$relativePath"),
            File("app/src/main/assets/$relativePath"),
            File("android/app/src/main/assets/$relativePath"),
            File("../android/app/src/main/assets/$relativePath")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertNotNull("Could not find asset file: $relativePath", file)
        return file!!
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
    fun testDashboardHtml_structureAndAodContract() {
        val htmlFile = locateAssetFile("dashboard-placeholder.html")
        val content = htmlFile.readText(StandardCharsets.UTF_8)

        // Pure black OLED background
        assertTrue("Must use pure black #000000 background", content.contains("#000000"))

        // Burn-in container positioned in upper viewport for HUD stage
        assertTrue(
            "Burn-in container must use flex-start alignment",
            content.contains("justify-content: flex-start")
        )
        assertTrue(
            "Burn-in container must have 9vh top padding",
            content.contains("padding-top: 9vh")
        )

        // Required DOM elements
        assertTrue("Must contain hours-minutes", content.contains("id=\"hours-minutes\""))
        assertTrue("Must contain seconds", content.contains("id=\"seconds\""))
        assertTrue("Must contain full-date", content.contains("id=\"full-date\""))
        assertTrue("Must contain weather-box", content.contains("id=\"weather-box\""))
        assertTrue("Must contain status-card", content.contains("id=\"status-card\""))
        assertTrue("Must contain status-indicator", content.contains("id=\"status-indicator\""))
        assertTrue("Must contain status-text", content.contains("id=\"status-text\""))

        // AOD Standby styling and class
        assertTrue("Body must include aod-mode class by default", content.contains("class=\"aod-mode\""))
        assertTrue("CSS must style body.aod-mode .time-box", content.contains("body.aod-mode .time-box"))
        assertTrue("CSS must style body.aod-mode .weather-box", content.contains("body.aod-mode .weather-box"))
        assertTrue("CSS must style body.aod-mode .status-container", content.contains("body.aod-mode .status-container"))

        // Duplicate speech & reply text elements must NOT be rendered in dashboard HTML
        assertFalse("Duplicate id=user-text should be removed", content.contains("id=\"user-text\""))
        assertFalse("Duplicate id=reply-text should be removed", content.contains("id=\"reply-text\""))

        // JavaScript API handlers
        assertTrue("Must define window.onWeatherUpdated", content.contains("window.onWeatherUpdated"))
        assertTrue("Must define window.onAodModeChanged", content.contains("window.onAodModeChanged"))
        assertTrue("Must define window.onWakeWord", content.contains("window.onWakeWord"))
        assertTrue("Must define window.onAsrDownloadProgress", content.contains("window.onAsrDownloadProgress"))
        assertTrue("Must define window.onAsrPartial", content.contains("window.onAsrPartial"))
        assertTrue("Must define window.onAsrFinal", content.contains("window.onAsrFinal"))
        assertTrue("Must define window.onChatReply", content.contains("window.onChatReply"))
        assertTrue("Must define window.onChatOffline", content.contains("window.onChatOffline"))
        assertTrue("Must define window.onFollowUpListening", content.contains("window.onFollowUpListening"))
        assertTrue("Must define window.onPipelineStateChanged", content.contains("window.onPipelineStateChanged"))
        assertTrue("Must define window.onNetworkStateChanged", content.contains("window.onNetworkStateChanged"))
        assertTrue("Must define window.onBridgeNotice", content.contains("window.onBridgeNotice"))
    }

    @Test
    fun testMainActivity_aodHudIntegration() {
        val mainFile = locateSourceFile("MainActivity.kt")
        val content = mainFile.readText(StandardCharsets.UTF_8)

        // Voice listening overlay views
        assertTrue("Must declare listeningOverlayContainer", content.contains("listeningOverlayContainer"))
        assertTrue("Must declare voiceOverlay", content.contains("voiceOverlay"))

        // Touch wake duration and handler
        assertTrue("TOUCH_WAKE_DURATION_MS must be 8000L", content.contains("TOUCH_WAKE_DURATION_MS = 8000L"))
        assertTrue("Must handle onUserInteractionWake", content.contains("onUserInteractionWake()"))
        assertTrue("Must intercept touch via dispatchTouchEvent", content.contains("override fun dispatchTouchEvent"))

        // AOD vs Active management
        assertTrue("Must declare updateDisplayPowerAndAodState", content.contains("fun updateDisplayPowerAndAodState()"))
        assertTrue("Must dispatch onAodModeChanged", content.contains("onAodModeChanged"))

        // Weather integration
        assertTrue("Must call WeatherManager.fetchWeatherIfNeeded", content.contains("WeatherManager.fetchWeatherIfNeeded"))
        assertTrue("Must dispatch onWeatherUpdated", content.contains("onWeatherUpdated"))
        assertTrue("Must add VoiceKioskHost JS interface", content.contains("addJavascriptInterface"))
        assertTrue("VoiceKioskHost must expose deviceToken", content.contains("fun deviceToken()"))
        assertTrue("VoiceKioskHost must expose speak", content.contains("fun speak("))
        assertTrue("VoiceKioskHost must expose requestPin", content.contains("fun requestPin("))
        assertTrue("PIN callback is onPinEntered", content.contains("onPinEntered"))
        assertTrue("questionOpened is origin-bound", content.contains("fun questionOpened("))
        assertTrue("approvalOpened is origin-bound", content.contains("fun approvalOpened("))

        // Burn-in animation
        assertTrue("Must animate burn-in translation with 900L duration", content.contains(".setDuration(900L)"))
    }
}
