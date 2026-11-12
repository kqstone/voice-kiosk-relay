package com.voicekiosk.app.ondevice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BenchmarkSweepTest {
    private val topo = CpuTopology((0..7).associateWith { if (it >= 4) 3_000_000L else 2_000_000L })
    private lateinit var dir: File
    private lateinit var modelFile: File
    private val model = OnDeviceModelCatalog.MODELS.first()

    private class FakeMemory : MemorySource {
        var resets = 0
        var rss = 100_000L
        override fun snapshot(includePss: Boolean) = ProcessMemoryProbe.Snapshot(rss, rss + 50_000, -1, rss / 2, rss / 2)
        override fun resetPeak(): Boolean { resets++; return true }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sweep").toFile()
        modelFile = File(dir, "m.gguf").apply {
            outputStream().use { it.write("GGUF".toByteArray()); it.write(ByteArray(GgufFileValidator.MIN_MODEL_BYTES.toInt())) }
        }
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    @Test
    fun sweepPlanShape() {
        val plan = SweepPlan.sweep(topo)
        val names = plan.map { it.name }
        assertEquals(listOf("default", "default-cold", "ff-off", "threads=6", "threads=8", "pinned", "mmap-on", "repack-off", "kv-f16", "kv-f16-fa-off", "ctx-512", "ctx-2048", "s0-like"), names)
        assertEquals(8, SweepPlan.loadCount(plan))
        // every config is loadable (no q8_0 + FA off) and differs from default in one knob
        plan.forEach { assertEquals(it.name, null, it.load.validate()) }
        val def = plan[0]
        assertEquals(LlmEngine.PrefixMode.CACHED, def.mode)
        assertEquals(1024, def.load.contextSize)
        assertEquals(LlmEngine.KvType.Q8_0, def.load.kvType)
        // S0.2 defaults: no pinning (it hurt on SM8650), grammar fast-forward on
        assertFalse(def.pinFastest)
        assertTrue(def.fastForward)
        assertFalse(def.load.mmap)
        assertTrue(def.load.repack)
        val s0 = plan.last()
        assertEquals(LlmEngine.PrefixMode.COLD, s0.mode)
        assertEquals(2048, s0.load.contextSize)
        assertFalse(s0.pinFastest)
        assertFalse(s0.fastForward)
        val ffOff = plan.first { it.name == "ff-off" }
        assertFalse(ffOff.fastForward)
        assertEquals(def.loadKey, ffOff.loadKey)
        val pinned = plan.first { it.name == "pinned" }
        assertTrue(pinned.pinFastest)
        assertEquals(def.loadKey, pinned.loadKey)
        // small phones skip thread counts they do not have
        val quad = CpuTopology((0..3).associateWith { 2_000_000L })
        assertFalse(SweepPlan.sweep(quad).any { it.name.startsWith("threads=") })
    }

    @Test
    fun runnerReusesLoadsAndBuildsPrefixOncePerLoad() {
        val fake = FakeNative()
        val mem = FakeMemory()
        val plan = SweepPlan.sweep(topo)
        val statuses = mutableListOf<String>()
        val results = LlmEngine(fake).use { e ->
            BenchmarkRunner(e, modelFile, model, topo, mem, File(dir, "state"), onStatus = { statuses += it }).run(plan)
        }
        assertEquals(plan.size, results.size)
        assertEquals(8, fake.calls.count { it == "load" })
        // one forced build per load + one restore on the first load
        assertEquals(9, fake.calls.count { it == "prefix" })
        assertEquals(8, mem.resets)
        assertEquals(plan.size * 5, fake.calls.count { it == "generate" })
        val first = results[0]
        val restore = first.load!!.prefixRestore as LlmEngine.PrefixResult.Success
        assertTrue(restore.restoredFromFile)
        assertTrue(results[1].loadReused)
        assertTrue(results[2].loadReused) // ff-off reuses the default load
        assertTrue(results[5].loadReused) // pinned reuses too
        assertFalse(results[6].loadReused) // mmap-on reloads
        assertEquals(NativeLoadParams(1024, 4, 256, 8, 1, true, useMmap = false), fake.loadParamsHistory[0])
        assertEquals(NativeLoadParams(1024, 4, 256, 8, 1, true, useMmap = true), fake.loadParamsHistory[1])
        assertEquals(NativeLoadParams(1024, 4, 256, 8, 1, false, useMmap = true), fake.loadParamsHistory[2])
        // all four cases graded correct by the fake, cached runs evaluate only the suffix
        assertEquals(4, first.correctCount)
        assertTrue(first.measured.all { it.promptTokens == FakeNative.SUFFIX_TOKENS })
        assertTrue(results[1].measured.all { it.promptTokens == FakeNative.PREFIX_TOKENS + FakeNative.SUFFIX_TOKENS })
        assertEquals(BenchmarkPrompt.CASES.map { it.id }, first.measured.map { it.caseId })
        // pinning: none by default, 4 fastest cores for "pinned"; affinity reset at the end
        assertEquals(emptyList<Int>(), first.pinnedCpus)
        assertEquals(listOf(4, 5, 6, 7), results[5].pinnedCpus)
        assertEquals(8, results[4].threads)
        assertEquals(emptyList<Int>(), fake.affinity)
        assertTrue(statuses.first().startsWith("[1/${plan.size} default]"))
        val report = BenchmarkReportFormatter.coldVsCached(results)
        assertNotNull(report)
        assertTrue(report!!, report.contains("outputs identical 4/4"))
        // fast-forward metrics flow through; ff-off has none
        assertEquals(4 * FakeNative.FF_FORCED, first.forced)
        assertEquals(4 * (30 - FakeNative.FF_FORCED), first.decodeCalls)
        assertEquals(0, results[2].forced)
        assertEquals(4 * 30, results[2].decodeCalls)
        val ff = BenchmarkReportFormatter.fastForwardVsPlain(results)
        assertNotNull(ff)
        assertTrue(ff!!, ff.contains("outputs identical 4/4"))
        assertTrue(ff, ff.contains("decode calls 120→72"))
        assertTrue(ff, ff.contains("forced 40%"))
        // memory baseline is the RSS before the first load, the same for every config
        assertTrue(results.all { it.baselineKb == 100_000L })
        assertEquals("VmHWM", first.peakSource)
    }

    @Test
    fun peakFallsBackToSampledMaxWhenResetIsDenied() {
        val fake = FakeNative()
        val mem = object : MemorySource {
            var rss = 100_000L
            override fun snapshot(includePss: Boolean): ProcessMemoryProbe.Snapshot {
                val s = ProcessMemoryProbe.Snapshot(rss, 9_999_999L, -1, rss / 2, rss / 2)
                rss += 1_000
                return s
            }
            override fun resetPeak(): Boolean = false
        }
        val plan = SweepPlan.sweep(topo).take(1)
        val results = LlmEngine(fake).use { e -> BenchmarkRunner(e, modelFile, model, topo, mem, null).run(plan) }
        val r = results.single()
        assertEquals("sampled", r.peakSource)
        assertTrue(r.peakRssKb < 9_999_999L)
        assertEquals(100_000L, r.baselineKb)
        assertEquals(r.peakRssKb - 100_000L, r.addedPeakKb())
    }

    @Test
    fun fastForwardFlagReachesNative() {
        val fake = FakeNative()
        val plan = SweepPlan.sweep(topo).filter { it.name == "ff-off" }
        LlmEngine(fake).use { e -> BenchmarkRunner(e, modelFile, model, topo, FakeMemory(), null).run(plan) }
        assertEquals(false, fake.lastFastForward)
    }

    @Test
    fun loadFailureIsRecordedAndSweepContinues() {
        val fake = FakeNative().apply { failLoadWhen = { !it.useExtraBufts } }
        val results = LlmEngine(fake).use { e ->
            BenchmarkRunner(e, modelFile, model, topo, FakeMemory(), null).run(SweepPlan.sweep(topo))
        }
        val repackOff = results.first { it.config.name == "repack-off" }
        assertTrue(repackOff.error!!.contains("load failed"))
        assertTrue(repackOff.runs.isEmpty())
        val next = results.first { it.config.name == "kv-f16" }
        assertEquals(null, next.error)
        assertEquals(5, next.runs.size)
        assertFalse(next.loadReused)
    }

    @Test
    fun cancellationStopsEarly() {
        var n = 0
        val fake = FakeNative()
        val results = LlmEngine(fake).use { e ->
            BenchmarkRunner(e, modelFile, model, topo, FakeMemory(), null, cancelled = { n++ > 3 }).run(SweepPlan.sweep(topo))
        }
        assertTrue(results.size <= 1)
        assertTrue(fake.calls.count { it == "generate" } < 5)
    }

    @Test
    fun stateFileIsKeyedAndUnderStateDir() {
        LlmEngine(FakeNative()).use { e ->
            val r = BenchmarkRunner(e, modelFile, model, topo, FakeMemory(), File(dir, "state"))
            val a = r.stateFileFor(LlmEngine.LoadConfig())!!
            val b = r.stateFileFor(LlmEngine.LoadConfig(kvType = LlmEngine.KvType.F16))!!
            assertTrue(a.name.startsWith("prefix-") && a.name.endsWith(".state"))
            assertTrue(a != b)
            assertEquals(File(dir, "state"), a.parentFile)
        }
    }
}
