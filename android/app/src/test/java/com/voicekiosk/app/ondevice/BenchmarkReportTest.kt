package com.voicekiosk.app.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkReportTest {

    private fun run(i: Int, total: Double, ok: Boolean = true, case: String = "c$i", out: String = "{\"intent\":\"weather_query\"}", correct: Boolean? = true) = BenchmarkRun(
        index = i,
        status = if (ok) LlmEngine.Status.END_OF_GENERATION else LlmEngine.Status.TIMEOUT,
        promptTokens = 370, generatedTokens = 33,
        promptEvalMs = 370.0, ttftMs = total - 300, decodeMs = 320.0, totalMs = total,
        rssKb = 700_000, pssKb = 650_000,
        output = out, error = if (ok) null else "deadline", caseId = case,
        correct = if (i == 0 || !ok) null else correct
    )

    private val snap = ProcessMemoryProbe.Snapshot(600_000, 600_000, 550_000, 450_000, 150_000)

    private fun loadInfo() = LoadInfo(
        loadMs = 812.3, memoryLog = FakeNative.MEMLOG, rssBeforeKb = 100_000, after = snap, afterPrefix = snap,
        prefix = LlmEngine.PrefixResult.Success(420, false, true, 0.2, 2000.0, 0.0, 5.0, 4_194_304),
        prefixRestore = LlmEngine.PrefixResult.Success(420, true, false, 0.2, 0.0, 3.5, 0.0, 4_194_304),
        peakResetOk = true
    )

    private fun result(name: String, runs: List<BenchmarkRun>, mode: LlmEngine.PrefixMode = LlmEngine.PrefixMode.CACHED, peak: Long = 720_000, reused: Boolean = false) =
        ConfigResult(BenchConfig(name, mode = mode), 4, listOf(4, 5, 6, 7), null, loadInfo(), reused, runs, 1, peak)

    private fun report(results: List<ConfigResult>) = BenchmarkReport(
        appVersion = "0.1.0 (2) debug",
        timestamp = "2026-11-10 09:30:00 +0800",
        device = DeviceProfile(8L shl 30, 4L shl 30, 20L shl 30, listOf("arm64-v8a"),
            setOf("asimddp", "i8mm", "asimdhp"), 8, 34, "Acme", "Phone 1", "QTI SM8550"),
        tier = TierDecision(DeviceTier.HIGH, DeviceTier.HIGH, listOf("内存 8.0GB ≥ 8GB 档且支持 dotprod")),
        model = OnDeviceModelCatalog.MODELS.first(),
        modelDescription = "qwen2 1B Q4_K - Medium",
        backendSummary = "variant=android_armv8.6_1; CPU (ARMv8)",
        systemInfo = "CPU : NEON = 1 | DOTPROD = 1 | MATMUL_INT8 = 1",
        cpuTopology = "1×3.20GHz[7] + 4×2.80GHz[3-6] + 3×2.00GHz[0-2]",
        mode = "sweep", results = results, thermalStart = "none", thermalEnd = "light",
        batteryTempC = 31.5, elapsedMs = 150_000.0
    )

    @Test
    fun statsUseNearestRankPercentiles() {
        val s = Stats.of(listOf(5.0, 1.0, 4.0, 2.0, 3.0, 10.0, 7.0, 6.0, 9.0, 8.0))
        assertEquals(5.5, s.mean, 1e-9)
        assertEquals(5.0, s.p50, 1e-9)
        assertEquals(9.0, s.p90, 1e-9)
        assertEquals(1.0, s.min, 1e-9)
        assertEquals(10.0, s.max, 1e-9)
        assertEquals(0.0, Stats.of(emptyList()).p90, 0.0)
        assertEquals(7.0, Stats.of(listOf(7.0)).p90, 0.0)
    }

    @Test
    fun runDerivedSpeeds() {
        val r = run(1, 700.0)
        assertEquals(1000.0, r.promptTps, 1e-9)
        assertEquals(100.0, r.decodeTps, 1e-9) // 32 tokens / 320 ms
    }

    @Test
    fun configResultExcludesWarmupAndFailures() {
        val c = result("default", listOf(run(0, 5000.0), run(1, 600.0), run(2, 700.0), run(3, 9999.0, ok = false), run(4, 650.0, correct = false)))
        assertEquals(3, c.measured.size)
        assertEquals(3, c.graded.size)
        assertEquals(2, c.correctCount)
        assertEquals(700.0, c.total.p90, 0.0)
        assertEquals(620_000L, c.addedPeakKb())
        assertEquals("PASS", BenchmarkReportFormatter.gateRoute(c))
        assertEquals("PASS", BenchmarkReportFormatter.gateMem(c))
    }

    @Test
    fun sweepReportHasSummaryBreakdownAndColdVsCached() {
        val cached = result("default", listOf(run(0, 900.0), run(1, 600.0, case = "a", out = "A"), run(2, 700.0, case = "b", out = "B")))
        val cold = result("default-cold", listOf(run(0, 5000.0), run(1, 4300.0, case = "a", out = "A"), run(2, 4400.0, case = "b", out = "X")), mode = LlmEngine.PrefixMode.COLD, reused = true)
        val heavy = result("ctx-2048", listOf(run(0, 1.0), run(1, 900.0), run(2, 950.0)), peak = 2_000_000)
        val text = report(listOf(cached, cold, heavy)).format()
        assertTrue(text, text.contains("(S0.2, prompt ${BenchmarkPrompt.VERSION}, sweep)"))
        assertTrue(text.contains("#1 default"))
        assertTrue(text.contains("gate route=PASS mem=PASS"))
        assertTrue(text.contains("gate route=FAIL mem=FAIL"))
        assertTrue(text.contains("prefix cache: ttft p50 cold=4000ms cached=300ms (13.3× faster); outputs identical 1/2"))
        assertTrue(text.contains("best (all correct, lowest route p90): #1 default"))
        assertTrue(text.contains("weights_mmap=1060MiB repacked=738MiB"))
        assertTrue(text.contains("kv=14.9MiB compute=148.0MiB"))
        assertTrue(text.contains("prefix: 420tok build=2000.0ms (one-off) saved=4096KiB"))
        assertTrue(text.contains("prefix_restore: restored 4096KiB in 3.5ms"))
        assertTrue(text.contains("anon=439MB file=146MB"))
        assertTrue(text.contains("load: reused from previous config"))
        assertTrue(text.contains("pin=none"))
        assertTrue(text, text.contains("memory: peak_rss=703MB (VmHWM) baseline_before_first_load=98MB peak_over_baseline=+605MB this_load_delta=+488MB"))
        assertTrue(text.contains("mem+ = config peak RSS − RSS before the first load"))
        assertTrue(text.contains("fast_forward=on:"))
        assertTrue(text.contains("clusters=1×3.20GHz[7]"))
        assertTrue(text.contains("thermal: start=none end=light"))
        assertTrue(text.contains("sha256=74a4da8c"))
        assertTrue(text.contains("    → A"))
    }

    @Test
    fun failedConfigIsReportedNotDropped() {
        val failed = ConfigResult(BenchConfig("repack-off"), 4, emptyList(), null, null, false, emptyList(), 1, 0, "load failed: oom")
        val text = report(listOf(failed)).format()
        assertTrue(text.contains("#1 repack-off   ERROR load failed: oom"))
        assertEquals(null, BenchmarkReportFormatter.best(listOf(failed)))
        assertEquals(null, BenchmarkReportFormatter.coldVsCached(listOf(failed)))
    }

    @Test
    fun bestRequiresAllCorrect() {
        val fastWrong = result("fast", listOf(run(0, 1.0), run(1, 300.0, correct = false)))
        val slowRight = result("slow", listOf(run(0, 1.0), run(1, 900.0)))
        assertEquals("slow", BenchmarkReportFormatter.best(listOf(fastWrong, slowRight))!!.config.name)
    }

    @Test
    fun fileNameIsSafe() {
        assertEquals("vk-llm-bench-20261109-233000-qwen3-0.6b-q4_k_m.txt",
            BenchmarkReportFormatter.fileName("20261109-233000", "qwen3-0.6b-q4_k_m"))
        assertEquals("vk-llm-bench-x-a_b_c-sweep.txt", BenchmarkReportFormatter.fileName("x", "a/b c", "sweep"))
    }

    @Test
    fun procStatusParsing() {
        val status = "Name:\tcom.voicekiosk.app:llmbench\nVmHWM:\t  812345 kB\nVmRSS:\t  700001 kB\nRssAnon:\t  500000 kB\nRssFile:\t  190000 kB\n"
        assertEquals(700001L, ProcessMemoryProbe.parseStatusField(status, "VmRSS"))
        assertEquals(812345L, ProcessMemoryProbe.parseStatusField(status, "VmHWM"))
        assertEquals(-1L, ProcessMemoryProbe.parseStatusField(status, "VmSwap"))
        val s = ProcessMemoryProbe.parse(status, 42)
        assertEquals(500000L, s.rssAnonKb)
        assertEquals(190000L, s.rssFileKb)
        assertEquals(42L, s.pssKb)
        assertFalse(s.rssKb < 0)
    }

    @Test
    fun semanticKeyIgnoresConfidence() {
        val a = "{\"intent\":\"weather_query\",\"slots\":{\"city\":\"上海\",\"day\":\"tomorrow\"},\"route\":\"local\",\"confidence\":0.8}"
        val b = a.replace("0.8", "0.9")
        val c = a.replace("上海", "北京")
        assertEquals(BenchmarkReportFormatter.semanticKey(a), BenchmarkReportFormatter.semanticKey(b))
        assertFalse(BenchmarkReportFormatter.semanticKey(a) == BenchmarkReportFormatter.semanticKey(c))
        assertEquals("raw:oops", BenchmarkReportFormatter.semanticKey("oops "))
    }
}
