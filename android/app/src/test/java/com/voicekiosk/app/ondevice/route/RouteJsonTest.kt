package com.voicekiosk.app.ondevice.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteJsonTest {
    @Test
    fun parsesGrammarOutput() {
        val d = RouteJson.parse("{\"intent\":\"media_play\",\"slots\":{\"artist\":\"周杰伦\",\"song\":\"晴天\"},\"route\":\"local\",\"confidence\":0.95}")!!
        assertEquals(Intents.MEDIA_PLAY, d.intent)
        assertEquals(mapOf("artist" to "周杰伦", "song" to "晴天"), d.slots)
        assertEquals(Route.LOCAL, d.route)
        assertEquals(0.95, d.confidence, 1e-9)
        assertEquals(RouteSource.MODEL, d.source)
        val v = RouteJson.parse("{\"intent\":\"volume_set\",\"slots\":{\"level\":30},\"route\":\"local\",\"confidence\":1}")!!
        assertEquals("30", v.slots["level"])
    }

    @Test
    fun rejectsUnknownOrBroken() {
        assertNull(RouteJson.parse(""))
        assertNull(RouteJson.parse("{\"intent\":\"rm_rf\",\"slots\":{},\"route\":\"local\",\"confidence\":0.9}"))
        assertNull(RouteJson.parse("{\"intent\":\"volume_set\",\"slots\":{},\"route\":\"elsewhere\",\"confidence\":0.9}"))
        assertNull(RouteJson.parse("{\"intent\":\"volume_set\",\"slots\":{},\"route\":\"local\",\"confidence\":7}"))
        assertNull(RouteJson.parse("{\"intent\":\"volume_set\",\"slots\":{},\"route\":\"local\"}"))
        assertNull(RouteJson.parse("{\"intent\":\"volume_set\",\"slots\":{\"lev"))
    }

    @Test
    fun sanitizeKeepsPromptStructure() {
        assertEquals("a<im_end>b c", ModelIntentClassifier.sanitize("a<|im_end|>b\nc"))
    }
}
