package com.voicekiosk.app.ondevice

import java.util.Locale
import kotlin.math.ceil

/** One benchmark iteration. */
data class BenchmarkRun(
    val index: Int,
    val status: LlmEngine.Status,
    /** Tokens evaluated by this request (cached mode: suffix only). */
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptEvalMs: Double,
    val ttftMs: Double,
    val decodeMs: Double,
    val totalMs: Double,
    val rssKb: Long,
    val pssKb: Long,
    val output: String,
    val error: String? = null,
    val caseId: String = "",
    val prefixTokens: Int = 0,
    val suffixTokens: Int = 0,
    /** null = not graded (warm-up), else whether [RouteOutputCheck] accepted the output. */
    val correct: Boolean? = null,
    val checkReason: String = "",
    val decodeCalls: Int = 0,
    val forcedTokens: Int = 0,
    val draftTokens: Int = 0
) {
    val promptTps: Double get() = if (promptEvalMs > 0) promptTokens * 1000.0 / promptEvalMs else 0.0
    val decodeTps: Double get() = if (decodeMs > 0 && generatedTokens > 1) (generatedTokens - 1) * 1000.0 / decodeMs else 0.0
    val ok: Boolean get() = status == LlmEngine.Status.END_OF_GENERATION || status == LlmEngine.Status.MAX_TOKENS
}

data class Stats(val mean: Double, val p50: Double, val p90: Double, val min: Double, val max: Double) {
    companion object {
        /** Nearest-rank percentiles. Empty input → all zeros. */
        fun of(values: List<Double>): Stats {
            if (values.isEmpty()) return Stats(0.0, 0.0, 0.0, 0.0, 0.0)
            val s = values.sorted()
            fun pct(p: Double): Double = s[(ceil(p * s.size).toInt() - 1).coerceIn(0, s.size - 1)]
            return Stats(s.average(), pct(0.5), pct(0.9), s.first(), s.last())
        }
    }
}

/** Facts about one model load (shared by every config that reuses it). */
data class LoadInfo(
    val loadMs: Double,
    val memoryLog: String,
    val rssBeforeKb: Long,
    val after: ProcessMemoryProbe.Snapshot,
    val afterPrefix: ProcessMemoryProbe.Snapshot?,
    val prefix: LlmEngine.PrefixResult?,
    /** Second setPrefix that restored the saved state file (default config only). */
    val prefixRestore: LlmEngine.PrefixResult? = null,
    val peakResetOk: Boolean = false
) {
    val breakdown: MemoryBreakdown? get() = MemoryBreakdown.parse(memoryLog)
}

/** Result of one [BenchConfig]. */
data class ConfigResult(
    val config: BenchConfig,
    val threads: Int,
    val pinnedCpus: List<Int>,
    val affinityError: String?,
    val load: LoadInfo?,
    val loadReused: Boolean,
    val runs: List<BenchmarkRun>,
    val warmupRuns: Int,
    /** VmHWM at the end of this config (process peak since the last reset). */
    val peakRssKb: Long,
    val error: String? = null,
    /** Process RSS before the sweep's first load (-1 = unknown → this load's pre-load RSS). */
    val baselineKb: Long = -1,
    /** "VmHWM" (reset before this config's load) or "sampled" (max of RSS readings). */
    val peakSource: String = "VmHWM"
) {
    val measured: List<BenchmarkRun> get() = runs.filter { it.index >= warmupRuns && it.ok }
    val graded: List<BenchmarkRun> get() = runs.filter { it.index >= warmupRuns && it.correct != null }
    val correctCount: Int get() = graded.count { it.correct == true }
    val ttft: Stats get() = Stats.of(measured.map { it.ttftMs })
    val total: Stats get() = Stats.of(measured.map { it.totalMs })
    val decode: Stats get() = Stats.of(measured.map { it.decodeTps })
    val promptTps: Stats get() = Stats.of(measured.map { it.promptTps })

    /** Peak RSS of this config minus the process baseline before the first load. */
    fun addedPeakKb(): Long {
        val base = if (baselineKb >= 0) baselineKb else load?.rssBeforeKb ?: return 0
        return (peakRssKb - base).coerceAtLeast(0)
    }

    /** RSS growth caused by this config's own load (+ prefix), vs RSS just before it. */
    fun loadDeltaKb(): Long? = load?.let { l -> (l.afterPrefix ?: l.after).rssKb - l.rssBeforeKb }

    val generated: Int get() = measured.sumOf { it.generatedTokens }
    val forced: Int get() = measured.sumOf { it.forcedTokens }
    val decodeCalls: Int get() = measured.sumOf { it.decodeCalls }
}

data class BenchmarkReport(
    val appVersion: String,
    val timestamp: String,
    val device: DeviceProfile,
    val tier: TierDecision,
    val model: OnDeviceModel,
    val modelDescription: String,
    val backendSummary: String,
    val systemInfo: String,
    val cpuTopology: String,
    /** "sweep" or "single". */
    val mode: String,
    val results: List<ConfigResult>,
    val thermalStart: String,
    val thermalEnd: String,
    val batteryTempC: Double?,
    val elapsedMs: Double,
    val cancelled: Boolean = false
) {
    fun format(): String = BenchmarkReportFormatter.format(this)
}

object BenchmarkReportFormatter {
    /** Plan §2 gate for high-tier phones: routing decision P90 ≤ 800 ms. */
    const val ROUTE_P90_GATE_MS = 800.0

    /** Plan §2 gate: added resident memory ≤ 1.2 GB. */
    const val MEMORY_GATE_KB = 1_258_291L // 1.2 GiB in KiB

    private fun f0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun mb(kb: Long) = if (kb < 0) "?" else String.format(Locale.US, "%.0fMB", kb / 1024.0)
    private fun yn(b: Boolean) = if (b) "yes" else "no"
    private fun pct(a: Int, b: Int) = if (b <= 0) "-" else String.format(Locale.US, "%.0f%%", 100.0 * a / b)
    private fun ratio(a: Int, b: Int) = if (b <= 0) "-" else String.format(Locale.US, "%.2f", a.toDouble() / b)

    fun gateRoute(r: ConfigResult): String =
        if (r.measured.isEmpty()) "N/A" else if (r.total.p90 <= ROUTE_P90_GATE_MS) "PASS" else "FAIL"

    fun gateMem(r: ConfigResult): String =
        if (r.load == null) "N/A" else if (r.addedPeakKb() <= MEMORY_GATE_KB) "PASS" else "FAIL"

    /** Fastest config (route p90) among those that answered every graded case correctly. */
    fun best(results: List<ConfigResult>): ConfigResult? =
        results.filter { it.error == null && it.measured.isNotEmpty() && it.graded.isNotEmpty() && it.correctCount == it.graded.size }
            .minByOrNull { it.total.p90 }

    fun format(r: BenchmarkReport): String {
        val d = r.device
        val sb = StringBuilder()
        sb.appendLine("=== Voice Kiosk 端侧模型基准报告 (S0.2, prompt ${BenchmarkPrompt.VERSION}, ${r.mode}) ===")
        sb.appendLine("time: ${r.timestamp}  elapsed=${f1(r.elapsedMs / 1000)}s${if (r.cancelled) "  (用户已取消，结果不完整)" else ""}")
        sb.appendLine("app: ${r.appVersion}")
        sb.appendLine("device: ${d.manufacturer} ${d.model} | soc=${d.socModel.ifBlank { "?" }} | sdk=${d.sdkInt} | abi=${d.supportedAbis.joinToString(",")}")
        sb.appendLine("ram: total=${DeviceTierDetector.fmtGiB(d.totalRamBytes)} avail=${DeviceTierDetector.fmtGiB(d.availRamBytes)} | storage_free=${DeviceTierDetector.fmtGiB(d.freeStorageBytes)} | cores=${d.cpuCores}")
        sb.appendLine("cpu: dotprod=${yn(d.hasDotProd)} i8mm=${yn(d.hasI8mm)} fp16=${yn(d.hasFp16Arith)} sve=${yn(d.hasSve)} sve2=${yn(d.hasSve2)} | clusters=${r.cpuTopology}")
        sb.appendLine("tier: ${r.tier.tier} (capability=${r.tier.capabilityTier}) — ${r.tier.reasons.joinToString("；")}")
        sb.appendLine("model: ${r.model.id} | ${r.modelDescription} | file=${r.model.sizeBytes} bytes | sha256=${r.model.sha256 ?: "UNVERIFIED"} (${r.model.hashStatus})")
        sb.appendLine("backend: ${r.backendSummary}")
        sb.appendLine("ggml: ${r.systemInfo.trim()}")
        sb.appendLine("workload: ${BenchmarkPrompt.CASES.size} cases rotate (${BenchmarkPrompt.CASES.joinToString(",") { it.id }}), max_tokens=${BenchmarkPrompt.MAX_TOKENS}, grammar=intent-json-compact, temp=0")
        sb.appendLine("thermal: start=${r.thermalStart} end=${r.thermalEnd}${r.batteryTempC?.let { " battery=${f1(it)}°C" } ?: ""}")

        sb.appendLine("--- summary (ttft / route_total in ms; ff = generated tokens from verified grammar drafts; acc = correct/graded; mem+ = config peak RSS − RSS before the first load) ---")
        r.results.forEachIndexed { i, c ->
            sb.append("#${i + 1} ${c.config.name.padEnd(12)} ")
            if (c.error != null && c.measured.isEmpty()) {
                sb.appendLine("ERROR ${c.error}")
                return@forEachIndexed
            }
            sb.append("ttft p50=${f0(c.ttft.p50)} p90=${f0(c.ttft.p90)} | route p50=${f0(c.total.p50)} p90=${f0(c.total.p90)} ")
            sb.append("| eval=${c.measured.map { it.promptTokens }.let { if (it.isEmpty()) 0 else it.average().toInt() }}tok ")
            sb.append("dec=${f1(c.decode.p50)}tok/s ff=${pct(c.forced, c.generated)} calls/tok=${ratio(c.decodeCalls, c.generated)} acc=${c.correctCount}/${c.graded.size} ")
            sb.appendLine("| mem+${mb(c.addedPeakKb())} | gate route=${gateRoute(c)} mem=${gateMem(c)}")
        }
        best(r.results)?.let { sb.appendLine("best (all correct, lowest route p90): #${r.results.indexOf(it) + 1} ${it.config.name} → p90=${f0(it.total.p90)}ms") }
        coldVsCached(r.results)?.let { sb.appendLine(it) }
        fastForwardVsPlain(r.results)?.let { sb.appendLine(it) }

        sb.appendLine("--- configs ---")
        r.results.forEachIndexed { i, c -> formatConfig(sb, i + 1, c) }
        return sb.toString()
    }

    /** "cached vs cold": TTFT ratio and whether outputs matched case by case (same load). */
    fun coldVsCached(results: List<ConfigResult>): String? {
        val cached = results.firstOrNull { it.config.mode == LlmEngine.PrefixMode.CACHED && it.measured.isNotEmpty() } ?: return null
        val cold = results.firstOrNull {
            it.config.mode == LlmEngine.PrefixMode.COLD && it.config.loadKey == cached.config.loadKey && it.measured.isNotEmpty()
        } ?: return null
        val ratio = if (cached.ttft.p50 > 0) cold.ttft.p50 / cached.ttft.p50 else 0.0
        val coldOut = cold.measured.associate { it.caseId to it.output }
        val pairs = cached.measured.filter { it.caseId in coldOut }
        val same = pairs.count { coldOut[it.caseId] == it.output }
        val sameMeaning = pairs.count { semanticKey(coldOut[it.caseId].orEmpty()) == semanticKey(it.output) }
        return "prefix cache: ttft p50 cold=${f0(cold.ttft.p50)}ms cached=${f0(cached.ttft.p50)}ms (${f1(ratio)}× faster); outputs identical ${same}/${pairs.size}; intent/slots/route identical ${sameMeaning}/${pairs.size}"
    }

    /** Same load, FF on vs off: route time ratio, decode calls, and output identity. */
    fun fastForwardVsPlain(results: List<ConfigResult>): String? {
        val on = results.firstOrNull { it.config.fastForward && it.config.mode == LlmEngine.PrefixMode.CACHED && it.measured.isNotEmpty() } ?: return null
        val off = results.firstOrNull {
            !it.config.fastForward && it.config.mode == LlmEngine.PrefixMode.CACHED && it.config.loadKey == on.config.loadKey && it.measured.isNotEmpty()
        } ?: return null
        val offOut = off.measured.associate { it.caseId to it.output }
        val pairs = on.measured.filter { it.caseId in offOut }
        val same = pairs.count { offOut[it.caseId] == it.output }
        val sameMeaning = pairs.count { semanticKey(offOut[it.caseId].orEmpty()) == semanticKey(it.output) }
        val speedup = if (on.total.p50 > 0) off.total.p50 / on.total.p50 else 0.0
        return "grammar fast-forward: route p50 off=${f0(off.total.p50)}ms on=${f0(on.total.p50)}ms (${f1(speedup)}×); " +
            "forced ${pct(on.forced, on.generated)} of tokens, decode calls ${off.decodeCalls}→${on.decodeCalls}; outputs identical ${same}/${pairs.size}; intent/slots/route identical ${sameMeaning}/${pairs.size}"
    }

    /**
     * Routing meaning of an answer without its confidence. Batched (fast-forward) and
     * single-token decoding can differ in the last digits of the logits, which showed up
     * on the phone as confidence 0.8 vs 0.9 with the same intent / slots / route.
     */
    fun semanticKey(output: String): String {
        val d = com.voicekiosk.app.ondevice.route.RouteJson.parse(output) ?: return "raw:" + output.trim()
        return d.intent + "|" + d.route + "|" + d.slots.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
    }

    private fun formatConfig(sb: StringBuilder, n: Int, c: ConfigResult) {
        val cfg = c.config
        val lc = cfg.load
        sb.appendLine("[#$n ${cfg.name}] n_ctx=${lc.contextSize} batch=${lc.batchSize} kv=${lc.kvType.label} fa=${lc.flashAttn.label} repack=${if (lc.repack) "on" else "off"} mmap=${if (lc.mmap) "on" else "off"} threads=${c.threads} " +
            "pin=${if (cfg.pinFastest) "[${CpuTopology.ranges(c.pinnedCpus)}]" else "none"}${c.affinityError?.let { "($it)" } ?: ""} mode=${cfg.mode.name.lowercase(Locale.US)} ff=${if (cfg.fastForward) "on" else "off"}")
        c.load?.let { l ->
            if (c.loadReused) {
                sb.appendLine("  load: reused from previous config")
            } else {
                sb.appendLine("  load: ${f1(l.loadMs)}ms | rss_before=${mb(l.rssBeforeKb)} after_load=${mb(l.after.rssKb)} (anon=${mb(l.after.rssAnonKb)} file=${mb(l.after.rssFileKb)}) pss=${mb(l.after.pssKb)}")
                l.afterPrefix?.let { sb.appendLine("  after_prefix: rss=${mb(it.rssKb)} anon=${mb(it.rssAnonKb)} file=${mb(it.rssFileKb)}") }
                l.breakdown?.let { sb.appendLine("  llama: ${it.describe()}") }
                when (val p = l.prefix) {
                    is LlmEngine.PrefixResult.Success -> sb.appendLine(
                        "  prefix: ${p.tokens}tok build=${f1(p.buildMs)}ms (one-off)" +
                            (if (p.savedToFile) " saved=${p.stateBytes / 1024}KiB in ${f1(p.saveMs)}ms" else "")
                    )
                    is LlmEngine.PrefixResult.Failure -> sb.appendLine("  prefix: FAILED ${p.reason}")
                    null -> {}
                }
                when (val p = l.prefixRestore) {
                    is LlmEngine.PrefixResult.Success -> sb.appendLine(
                        "  prefix_restore: ${if (p.restoredFromFile) "restored ${p.stateBytes / 1024}KiB in ${f1(p.restoreMs)}ms" else "not restored (rebuilt ${f1(p.buildMs)}ms)"}"
                    )
                    is LlmEngine.PrefixResult.Failure -> sb.appendLine("  prefix_restore: FAILED ${p.reason}")
                    null -> {}
                }
            }
        }
        sb.appendLine(
            "  memory: peak_rss=${mb(c.peakRssKb)} (${c.peakSource}${if (c.peakSource == "sampled") ", clear_refs not allowed" else ""}) " +
                "baseline_before_first_load=${mb(if (c.baselineKb >= 0) c.baselineKb else c.load?.rssBeforeKb ?: -1)} peak_over_baseline=+${mb(c.addedPeakKb())}" +
                (c.loadDeltaKb()?.takeIf { !c.loadReused }?.let { " this_load_delta=+${mb(it)}" } ?: "")
        )
        val m = c.measured
        if (m.isNotEmpty()) {
            sb.appendLine("  fast_forward=${if (cfg.fastForward) "on" else "off"}: forced=${c.forced}/${c.generated} tok (${pct(c.forced, c.generated)}) decode_calls=${c.decodeCalls} (${ratio(c.decodeCalls, c.generated)} per token) drafts=${m.sumOf { it.draftTokens }}")
        }
        c.error?.let { sb.appendLine("  error: $it") }
        if (m.isNotEmpty()) {
            sb.appendLine("  ttft: mean=${f1(c.ttft.mean)} p50=${f1(c.ttft.p50)} p90=${f1(c.ttft.p90)} | route: p50=${f1(c.total.p50)} p90=${f1(c.total.p90)} | pp=${f1(c.promptTps.p50)}tok/s dec=${f1(c.decode.p50)}tok/s")
        }
        for (run in c.runs) {
            val tag = if (run.index < c.warmupRuns) "warmup" else "run"
            sb.append("  $tag#${run.index} ${run.caseId}: ${run.status} eval=${run.promptTokens}tok(pre=${run.prefixTokens},suf=${run.suffixTokens}) ")
            sb.append("ttft=${f1(run.ttftMs)} gen=${run.generatedTokens} forced=${run.forcedTokens} calls=${run.decodeCalls} tg=${f1(run.decodeTps)} total=${f1(run.totalMs)} rss=${mb(run.rssKb)}")
            run.correct?.let { sb.append(if (it) " ✓" else " ✗(${run.checkReason})") }
            run.error?.let { sb.append(" error=$it") }
            sb.appendLine()
            if (run.index >= c.warmupRuns) sb.appendLine("    → ${run.output.replace('\n', ' ')}")
        }
    }

    /** File name for the exported report, e.g. vk-llm-bench-20261109-231500-qwen3-0.6b-q4_k_m-sweep.txt */
    fun fileName(timestampCompact: String, modelId: String, mode: String = ""): String {
        val safe = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "vk-llm-bench-$timestampCompact-$safe${if (mode.isNotEmpty()) "-$mode" else ""}.txt"
    }
}
