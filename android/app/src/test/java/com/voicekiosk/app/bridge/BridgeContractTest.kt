package com.voicekiosk.app.bridge

import com.voicekiosk.app.AppConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeContractTest {

    @Test
    fun pairingRequestUsesEightDigitCodeAndDefaultName() {
        assertEquals("12345678", PairingWire.parseCode("12345678"))
        assertNull(PairingWire.parseCode("1234567"))
        assertNull(PairingWire.parseCode("1234567a"))
        assertEquals("12345678", PairingWire.parseCode(" 12345678"))
        assertNull(PairingWire.parseCode("1234 5678"))
        val json = PairingWire.requestJson("12345678", "  ")
        assertEquals("12345678", json.getString("code"))
        assertEquals(PairingWire.DEFAULT_DEVICE_NAME, json.getString("device_name"))
        val named = PairingWire.requestJson("87654321", "  客厅屏幕\u0000  ")
        assertEquals("客厅屏幕", named.getString("device_name"))
        assertEquals("/api/v2/pairing", PairingWire.PATH)
    }

    @Test
    fun pairingResponseRequiresDeviceToken() {
        val ok = PairingWire.parseResponse(JSONObject("""{"device_token":"tok-abc","device_id":"dev1"}"""))!!
        assertEquals("tok-abc", ok.deviceToken)
        assertEquals("dev1", ok.deviceId)
        assertNull(PairingWire.parseResponse(JSONObject("""{"device_id":"dev1"}""")))
        assertNull(PairingWire.parseResponse(JSONObject("""{"device_token":"  "}""")))
    }

    @Test
    fun metaHandshakeBlocksCloudOnlyOnApiMismatch() {
        val match = BridgeMeta.decide(BridgeMeta.Info(apiVersion = 2, minClientVersion = 1), clientVersionCode = 5)
        assertTrue(match.cloudAllowed)
        assertNull(match.notice)

        val tooOld = BridgeMeta.decide(BridgeMeta.Info(apiVersion = 2, minClientVersion = 10), clientVersionCode = 5)
        assertTrue("cloud still attempted when api_version matches", tooOld.cloudAllowed)
        assertEquals(BridgeMeta.NOTICE_CLIENT_TOO_OLD, tooOld.notice)

        val mismatch = BridgeMeta.decide(BridgeMeta.Info(apiVersion = 1, minClientVersion = 1), clientVersionCode = 99)
        assertFalse(mismatch.cloudAllowed)
        assertEquals(BridgeMeta.NOTICE_API_MISMATCH, mismatch.notice)

        val parsed = BridgeMeta.parse(JSONObject("""{"api_version":2,"min_client_version":3,"server_version":"0.2.0"}"""))
        assertEquals(2, parsed.apiVersion)
        assertEquals(3, parsed.minClientVersion)
        assertEquals("0.2.0", parsed.serverVersion)
    }

    @Test
    fun deviceTokenVisibleOnlyOnBoundOrigin() {
        val token = "secret-token"
        val bound = AppConfig.normalizeBridgeOrigin("http://192.168.1.8:8093/studio")
        assertEquals("http://192.168.1.8:8093", bound)
        assertEquals(token, OriginBoundToken.forPage("http://192.168.1.8:8093/", bound, token))
        assertEquals(token, OriginBoundToken.forPage("http://192.168.1.8:8093/boards/1", bound, token))
        assertEquals("", OriginBoundToken.forPage("http://evil.example/", bound, token))
        assertEquals("", OriginBoundToken.forPage("https://192.168.1.8:8093/", bound, token))
        assertEquals("", OriginBoundToken.forPage("file:///android_asset/dashboard-placeholder.html", bound, token))
        assertEquals("", OriginBoundToken.forPage("http://192.168.1.8:8093/", bound, ""))
        assertEquals("", OriginBoundToken.forPage("http://192.168.1.8:8093/", "", token))
    }

    @Test
    fun hostMethodsOriginBoundAndPinId() {
        val bound = AppConfig.normalizeBridgeOrigin("http://192.168.1.8:8093/")
        assertTrue(OriginBoundHost.allowsPage("http://192.168.1.8:8093/dashboard", bound))
        assertFalse(OriginBoundHost.allowsPage("http://evil.example/", bound))
        assertFalse(OriginBoundHost.allowsPage("file:///android_asset/dashboard-placeholder.html", bound))
        assertFalse(OriginBoundHost.allowsPage("http://192.168.1.8:8093/", ""))
        assertTrue(OriginBoundHost.pinRequestIdOk("ap_1-ok"))
        assertFalse(OriginBoundHost.pinRequestIdOk(""))
        assertFalse(OriginBoundHost.pinRequestIdOk("bad id"))
        assertFalse(OriginBoundHost.pinRequestIdOk("x".repeat(65)))
        assertFalse(OriginBoundHost.pinRequestIdOk("../etc"))
    }

    @Test
    fun deriveDashboardUrlIsBridgeRoot() {
        assertEquals("http://127.0.0.1:8093/", AppConfig.deriveDashboardUrl("http://127.0.0.1:8093"))
        assertEquals("http://127.0.0.1:8093/", AppConfig.deriveDashboardUrl("http://127.0.0.1:8093/"))
        assertEquals("", AppConfig.deriveDashboardUrl("  "))
        assertFalse(AppConfig.deriveDashboardUrl("http://127.0.0.1:8093").endsWith("/dashboard"))
    }
}
