package com.voicekiosk.app.bridge

import com.voicekiosk.app.AppConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeContractTest {

    @Test
    fun healthCheckParsesOk() {
        assertEquals("/health", BridgeHealth.PATH)
        val ok = BridgeHealth.parse(JSONObject("""{"ok":true}"""))
        assertTrue(ok.ok)
        val notOk = BridgeHealth.parse(JSONObject("""{"ok":false}"""))
        assertFalse(notOk.ok)
        val empty = BridgeHealth.parse(JSONObject("{}"))
        assertFalse(empty.ok)
    }

    @Test
    fun deviceTokenVisibleOnlyOnBoundOrigin() {
        val token = "secret-token"
        val bound = AppConfig.normalizeBridgeOrigin("http://192.168.0.202:8093")
        assertEquals("http://192.168.0.202:8093", bound)
        assertEquals(token, OriginBoundToken.forPage("http://192.168.0.202:8093/", bound, token))
        assertEquals(token, OriginBoundToken.forPage("http://192.168.0.202:8093/boards/1", bound, token))
        assertEquals("", OriginBoundToken.forPage("http://evil.example/", bound, token))
        assertEquals("", OriginBoundToken.forPage("https://192.168.0.202:8093/", bound, token))
        assertEquals("", OriginBoundToken.forPage("file:///android_asset/dashboard-placeholder.html", bound, token))
        assertEquals("", OriginBoundToken.forPage("http://192.168.0.202:8093/", bound, ""))
        assertEquals("", OriginBoundToken.forPage("http://192.168.0.202:8093/", "", token))
    }

    @Test
    fun hostMethodsOriginBoundAndPinId() {
        val bound = AppConfig.normalizeBridgeOrigin("http://192.168.0.202:8093/")
        assertTrue(OriginBoundHost.allowsPage("http://192.168.0.202:8093/dashboard", bound))
        assertFalse(OriginBoundHost.allowsPage("http://evil.example/", bound))
        assertFalse(OriginBoundHost.allowsPage("file:///android_asset/dashboard-placeholder.html", bound))
        assertFalse(OriginBoundHost.allowsPage("http://192.168.0.202:8093/", ""))
        assertTrue(OriginBoundHost.pinRequestIdOk("ap_1-ok"))
        assertFalse(OriginBoundHost.pinRequestIdOk(""))
        assertFalse(OriginBoundHost.pinRequestIdOk("bad id"))
        assertFalse(OriginBoundHost.pinRequestIdOk("x".repeat(65)))
        assertFalse(OriginBoundHost.pinRequestIdOk("../etc"))
    }

    @Test
    fun deriveDashboardUrlIsBridgeRoot() {
        assertEquals("http://192.168.0.202:8093/", AppConfig.deriveDashboardUrl("http://192.168.0.202:8093"))
        assertEquals("http://192.168.0.202:8093/", AppConfig.deriveDashboardUrl("http://192.168.0.202:8093/"))
        assertEquals("", AppConfig.deriveDashboardUrl("  "))
        assertFalse(AppConfig.deriveDashboardUrl("http://192.168.0.202:8093").endsWith("/dashboard"))
    }
}
