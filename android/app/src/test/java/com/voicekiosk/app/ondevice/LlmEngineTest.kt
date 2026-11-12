package com.voicekiosk.app.ondevice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LlmEngineTest {

    private lateinit var dir: File
    private lateinit var model: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("llm-test").toFile()
        model = File(dir, "m.gguf")
        model.outputStream().use { out ->
            out.write("GGUF".toByteArray())
            out.write(ByteArray(GgufFileValidator.MIN_MODEL_BYTES.toInt()))
        }
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun missingModelFailsWithoutTouchingNative() {
        val fake = FakeNative()
        LlmEngine(fake).use { e ->
            val r = e.load(File(dir, "nope.gguf")).get()
            assertTrue(r is LlmEngine.LoadResult.Failure)
            assertTrue((r as LlmEngine.LoadResult.Failure).reason.contains("not found"))
        }
        assertFalse(fake.calls.contains("load"))
    }

    @Test
    fun corruptHeaderIsRejected() {
        val bad = File(dir, "bad.gguf").apply { writeBytes(ByteArray(2 * 1024 * 1024) { 1 }) }
        LlmEngine(FakeNative()).use { e ->
            val r = e.load(bad).get() as LlmEngine.LoadResult.Failure
            assertTrue(r.reason.contains("GGUF"))
        }
        val tiny = File(dir, "tiny.gguf").apply { writeBytes("GGUF".toByteArray()) }
        assertTrue(GgufFileValidator.validate(tiny)!!.contains("too small"))
        assertEquals(null, GgufFileValidator.validate(model))
    }

    @Test
    fun missingNativeLibraryIsReportedNotThrown() {
        val fake = FakeNative().apply { initError = UnsatisfiedLinkError("dlopen failed: libvk_llm.so") }
        LlmEngine(fake).use { e ->
            val r = e.load(model).get() as LlmEngine.LoadResult.Failure
            assertTrue(r.reason.contains("native library unavailable"))
            assertFalse(e.isLoaded)
        }
    }

    @Test
    fun nativeLoadErrorIsPropagated() {
        val fake = FakeNative().apply { loadError = "llama_model_load_from_file failed" }
        LlmEngine(fake).use { e ->
            val r = e.load(model).get() as LlmEngine.LoadResult.Failure
            assertEquals("llama_model_load_from_file failed", r.reason)
        }
    }

    @Test
    fun generateBeforeLoadFails() {
        LlmEngine(FakeNative()).use { e ->
            val r = e.generate(LlmEngine.GenerateRequest("hi")).get()
            assertEquals(LlmEngine.Status.ERROR, r.status)
            assertEquals("model not loaded", r.error)
        }
    }

    @Test
    fun loadGenerateReleaseHappyPathAndMetrics() {
        val fake = FakeNative()
        LlmEngine(fake).use { e ->
            val l = e.load(model).get() as LlmEngine.LoadResult.Success
            assertEquals("fake 0.5B Q4_K", l.description)
            assertTrue(l.backendSummary.contains("variant="))
            val g = e.generate(LlmEngine.GenerateRequest("prompt", grammar = "root ::= \"x\"")).get()
            assertTrue(g.isSuccess)
            assertEquals(750.0, g.promptTokensPerSecond, 0.01)
            assertEquals(50.0, g.decodeTokensPerSecond, 0.01) // (11-1) tokens / 200ms
            e.release().get()
            assertFalse(e.isLoaded)
        }
        assertEquals(listOf("init", "load", "generate", "release"), fake.calls.toList())
        assertEquals(0, fake.lastPrefixMode)
    }

    @Test
    fun requestsAreSerialisedOnOneThread() {
        val fake = FakeNative()
        LlmEngine(fake).use { e ->
            e.load(model).get()
            val futures = (1..6).map { e.generate(LlmEngine.GenerateRequest("p$it")) }
            futures.forEach { assertTrue(it.get().isSuccess) }
        }
        assertEquals(1, fake.maxConcurrent)
    }

    @Test
    fun cancelInterruptsInFlightGeneration() {
        val fake = FakeNative().apply { generateLatch = CountDownLatch(1) }
        LlmEngine(fake).use { e ->
            e.load(model).get()
            val f = e.generate(LlmEngine.GenerateRequest("p"))
            Thread.sleep(50)
            e.cancel()
            assertEquals(LlmEngine.Status.CANCELLED, f.get(5, TimeUnit.SECONDS).status)
        }
    }

    @Test
    fun invalidArgumentsAreRejected() {
        LlmEngine(FakeNative()).use { e ->
            assertTrue(e.load(model, LlmEngine.LoadConfig(contextSize = 16)).get() is LlmEngine.LoadResult.Failure)
            e.load(model).get()
            assertEquals(LlmEngine.Status.ERROR, e.generate(LlmEngine.GenerateRequest("p", maxTokens = 0)).get().status)
            assertEquals(LlmEngine.Status.ERROR, e.generate(LlmEngine.GenerateRequest("")).get().status)
        }
    }

    @Test
    fun statusCodesMapLikeNative() {
        assertEquals(LlmEngine.Status.TIMEOUT, LlmEngine.Status.fromCode(3))
        assertEquals(LlmEngine.Status.CONTEXT_FULL, LlmEngine.Status.fromCode(5))
        assertEquals(LlmEngine.Status.ERROR, LlmEngine.Status.fromCode(99))
    }

    @Test
    fun defaultThreadCountIsSane() {
        assertEquals(4, LlmEngine.defaultThreadCount(8))
        assertEquals(2, LlmEngine.defaultThreadCount(4))
        assertEquals(1, LlmEngine.defaultThreadCount(1))
    }

    @Test
    fun loadConfigIsForwardedToNative() {
        val fake = FakeNative()
        LlmEngine(fake).use { e ->
            val cfg = LlmEngine.LoadConfig(contextSize = 512, threads = 3, batchSize = 128, kvType = LlmEngine.KvType.F16, flashAttn = LlmEngine.FlashAttn.OFF, repack = false, mmap = false)
            val l = e.load(model, cfg).get() as LlmEngine.LoadResult.Success
            assertEquals(NativeLoadParams(512, 3, 128, 1, 0, false, false), fake.lastLoadParams)
            assertTrue(l.memoryLog.contains("CPU_REPACK"))
            e.load(model).get()
            assertEquals(NativeLoadParams(1024, LlmEngine.defaultThreadCount(), 256, 8, 1, true, false), fake.lastLoadParams)
        }
    }

    @Test
    fun defaultsAreRoutingDefaults() {
        val d = LlmEngine.LoadConfig()
        assertEquals(1024, d.contextSize)
        assertEquals(LlmEngine.KvType.Q8_0, d.kvType)
        assertEquals(LlmEngine.FlashAttn.ON, d.flashAttn)
        assertEquals(256, d.batchSize)
        assertTrue(d.repack)
        assertFalse(d.mmap)
        assertEquals(null, d.validate())
    }

    @Test
    fun quantizedKvWithoutFlashAttentionIsRejectedBeforeNative() {
        val fake = FakeNative()
        LlmEngine(fake).use { e ->
            val r = e.load(model, LlmEngine.LoadConfig(kvType = LlmEngine.KvType.Q8_0, flashAttn = LlmEngine.FlashAttn.OFF)).get()
            assertTrue((r as LlmEngine.LoadResult.Failure).reason.contains("flash attention"))
            assertTrue(e.load(model, LlmEngine.LoadConfig(kvType = LlmEngine.KvType.F16, flashAttn = LlmEngine.FlashAttn.OFF)).get() is LlmEngine.LoadResult.Success)
            assertTrue(e.load(model, LlmEngine.LoadConfig(kvType = LlmEngine.KvType.Q8_0, flashAttn = LlmEngine.FlashAttn.AUTO)).get() is LlmEngine.LoadResult.Success)
        }
        assertFalse(fake.loadParamsHistory.any { it.kvType == 8 && it.flashAttn == 0 })
    }

    @Test
    fun prefixThenCachedGenerateOnlyEvaluatesSuffix() {
        val fake = FakeNative()
        LlmEngine(fake).use { e ->
            assertTrue(e.setPrefix("P").get() is LlmEngine.PrefixResult.Failure) // not loaded
            e.load(model).get()
            // cached without a prefix → native error surfaces as ERROR, no crash
            val noPrefix = e.generate(LlmEngine.GenerateRequest("s", prefixMode = LlmEngine.PrefixMode.CACHED)).get()
            assertEquals(LlmEngine.Status.ERROR, noPrefix.status)
            val p = e.setPrefix("PREFIX-TEXT").get() as LlmEngine.PrefixResult.Success
            assertEquals(FakeNative.PREFIX_TOKENS, p.tokens)
            val cached = e.generate(LlmEngine.GenerateRequest("suffix", prefixMode = LlmEngine.PrefixMode.CACHED)).get()
            val cold = e.generate(LlmEngine.GenerateRequest("suffix", prefixMode = LlmEngine.PrefixMode.COLD)).get()
            assertEquals(FakeNative.SUFFIX_TOKENS, cached.promptTokens)
            assertEquals(FakeNative.PREFIX_TOKENS + FakeNative.SUFFIX_TOKENS, cold.promptTokens)
            assertEquals(FakeNative.PREFIX_TOKENS, cached.prefixTokens)
            assertEquals(cold.text, cached.text)
            assertTrue(cold.ttftMs > cached.ttftMs)
            assertEquals(1, fake.lastPrefixMode)
        }
    }

    @Test
    fun prefixStateFileIsSavedThenRestored() {
        val fake = FakeNative()
        val state = File(dir, "cache/prefix.state")
        LlmEngine(fake).use { e ->
            e.load(model).get()
            val first = e.setPrefix("PFX", state, forceRebuild = false).get() as LlmEngine.PrefixResult.Success
            assertFalse(first.restoredFromFile)
            assertTrue(first.savedToFile)
            assertTrue(state.exists())
            val second = e.setPrefix("PFX", state).get() as LlmEngine.PrefixResult.Success
            assertTrue(second.restoredFromFile)
            assertEquals(0.0, second.buildMs, 0.0)
            val forced = e.setPrefix("PFX", state, forceRebuild = true).get() as LlmEngine.PrefixResult.Success
            assertFalse(forced.restoredFromFile)
            // different prefix text never restores another prefix's state
            val other = e.setPrefix("OTHER", state).get() as LlmEngine.PrefixResult.Success
            assertFalse(other.restoredFromFile)
        }
    }

    @Test
    fun threadsAndAffinityRunOnInferenceThread() {
        val fake = FakeNative()
        LlmEngine(fake).use { e ->
            assertFalse(e.setThreads(4).get()) // not loaded
            e.load(model).get()
            assertTrue(e.setThreads(6).get())
            assertFalse(e.setThreads(0).get())
            assertEquals(6, fake.threads)
            assertEquals(null, e.setCpuAffinity(listOf(4, 5, 6, 7)).get())
            assertEquals(listOf(4, 5, 6, 7), fake.affinity)
            assertTrue(fake.affinityThread!!.startsWith("vk-llm-inference"))
            fake.affinityErrno = 22
            assertEquals("sched_setaffinity errno=22", e.setCpuAffinity(emptyList()).get())
        }
    }
}
