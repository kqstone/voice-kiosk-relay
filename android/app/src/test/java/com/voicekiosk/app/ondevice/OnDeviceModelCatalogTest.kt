package com.voicekiosk.app.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceModelCatalogTest {

    @Test
    fun catalogIsInternallyConsistent() {
        assertEquals(emptyList<String>(), OnDeviceModelCatalog.validateCatalog())
    }

    @Test
    fun everyTierWithModelsHasCandidates() {
        assertTrue(OnDeviceModelCatalog.forTier(DeviceTier.MID).isNotEmpty())
        assertTrue(OnDeviceModelCatalog.forTier(DeviceTier.HIGH).isNotEmpty())
        assertTrue(OnDeviceModelCatalog.forTier(DeviceTier.LOW).isEmpty())
    }

    @Test
    fun s02TierDefaultsOnlyHighGetsQwen25_15b() {
        assertEquals("qwen2.5-1.5b-instruct-q4_k_m", OnDeviceModelCatalog.defaultFor(DeviceTier.HIGH)!!.id)
        assertNull(OnDeviceModelCatalog.defaultFor(DeviceTier.MID))
        assertNull(OnDeviceModelCatalog.defaultFor(DeviceTier.LOW))
        assertEquals(1, OnDeviceModelCatalog.MODELS.count { it.tierDefault })
        // the rest stay selectable in the benchmark as experimental
        for (id in listOf("qwen3-0.6b-q4_k_m", "qwen2.5-0.5b-instruct-q4_k_m", "qwen3-1.7b-q4_k_m")) {
            val m = OnDeviceModelCatalog.MODELS.firstOrNull { it.id == id }
            assertNotNull(id, m)
            assertTrue(id, m!!.experimental)
        }
        assertEquals(OnDeviceModelCatalog.defaultFor(DeviceTier.HIGH)!!.sizeBytes, DeviceTierDetector.requiredModelBytes(DeviceTier.HIGH))
        assertEquals(0L, DeviceTierDetector.requiredModelBytes(DeviceTier.MID))
    }

    @Test
    fun verifiedEntriesHaveLowercase64HexHashesAndPinnedSources() {
        for (m in OnDeviceModelCatalog.MODELS) {
            assertEquals(HashStatus.VERIFIED, m.hashStatus)
            assertTrue(m.id, Regex("^[0-9a-f]{64}$").matches(m.sha256!!))
            assertNull(m.id, OnDeviceModelCatalog.usabilityError(m, allowUnverified = false))
            assertTrue(m.id, m.urls.all { it.startsWith("https://") })
            // The HuggingFace URL must pin a commit, not a moving branch.
            val hf = m.urls.first { it.contains("huggingface.co") }
            assertTrue(hf, Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(hf))
            assertTrue(m.urls.any { it.contains("modelscope.cn") })
            assertTrue(m.urls.all { it.endsWith("/" + m.fileName) })
        }
    }

    @Test
    fun knownHashesMatchDocumentedValues() {
        // Cross-checked against HF LFS oid, ModelScope Sha256 and local sha256sum (docs/MODELS.md).
        assertEquals(
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            OnDeviceModelCatalog.byId("qwen2.5-0.5b-instruct-q4_k_m")!!.sha256
        )
        assertEquals(
            "b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897",
            OnDeviceModelCatalog.byId("qwen3-1.7b-q4_k_m")!!.sha256
        )
    }

    private val base = OnDeviceModelCatalog.MODELS.first()

    @Test
    fun unverifiedIsRefusedUnlessDebugAllowed() {
        val unverified = base.copy(id = "x", sha256 = null, hashStatus = HashStatus.UNVERIFIED)
        assertNotNull(OnDeviceModelCatalog.usabilityError(unverified, allowUnverified = false))
        assertNull(OnDeviceModelCatalog.usabilityError(unverified, allowUnverified = true))
    }

    @Test
    fun malformedHashesAndUrlsAreRejected() {
        assertNotNull(OnDeviceModelCatalog.usabilityError(base.copy(sha256 = "ABC"), false))
        assertNotNull(OnDeviceModelCatalog.usabilityError(base.copy(sha256 = base.sha256!!.uppercase()), false))
        assertNotNull(OnDeviceModelCatalog.usabilityError(base.copy(urls = listOf("http://huggingface.co/a.gguf")), false))
        assertNotNull(OnDeviceModelCatalog.usabilityError(base.copy(urls = listOf("https://evil.example.com/a.gguf")), false))
        assertNotNull(OnDeviceModelCatalog.usabilityError(base.copy(urls = emptyList()), false))
        assertNotNull(OnDeviceModelCatalog.usabilityError(base.copy(fileName = "../x.gguf"), false))
        assertNotNull(OnDeviceModelCatalog.usabilityError(
            base.copy(hashStatus = HashStatus.UNVERIFIED, sha256 = "zz"), true))
    }

    @Test
    fun duplicateIdsAreDetected() {
        val problems = OnDeviceModelCatalog.validateCatalog(listOf(base, base))
        assertTrue(problems.contains("duplicate id"))
        assertFalse(problems.isEmpty())
    }
}
