package com.voicekiosk.app.ondevice

import java.io.File

/**
 * One benchmark configuration. [load] fields that need a reload are compared via
 * [loadKey]; [threads] (0 = auto), [pinFastest] and [mode] are applied at runtime, so
 * consecutive configs that share a [loadKey] reuse the loaded model and prefix KV.
 */
data class BenchConfig(
    val name: String,
    val load: LlmEngine.LoadConfig = LlmEngine.LoadConfig(threads = 4),
    val threads: Int = 0,
    val pinFastest: Boolean = RoutingDefaults.PIN_FASTEST_CORES,
    val mode: LlmEngine.PrefixMode = LlmEngine.PrefixMode.CACHED,
    val fastForward: Boolean = RoutingDefaults.FAST_FORWARD
) {
    val loadKey: LlmEngine.LoadConfig get() = load.copy(threads = 1)
}

object SweepPlan {
    /** Measured runs per config (one per [BenchmarkPrompt.CASES] entry) + 1 warm-up. */
    const val MEASURED_RUNS = 4
    const val WARMUP_RUNS = 1

    private val DEFAULT_LOAD = LlmEngine.LoadConfig(threads = 4)

    /**
     * The one-tap sweep. Ordered so that configs sharing a load are adjacent (8 loads).
     * Each config isolates one knob against the default; "s0-like" reproduces S0
     * (n_ctx 2048, batch 512, f16 KV, FA auto, mmap, 4 unpinned threads, no prefix cache).
     */
    fun sweep(topology: CpuTopology): List<BenchConfig> {
        val out = mutableListOf(
            BenchConfig("default", DEFAULT_LOAD),
            BenchConfig("default-cold", DEFAULT_LOAD, mode = LlmEngine.PrefixMode.COLD)
        )
        out += BenchConfig("ff-off", DEFAULT_LOAD, fastForward = false)
        val cpus = topology.cpuCount
        if (cpus >= 6) out += BenchConfig("threads=6", DEFAULT_LOAD, threads = 6)
        if (cpus >= 8) out += BenchConfig("threads=8", DEFAULT_LOAD, threads = 8)
        if (cpus > 0) out += BenchConfig("pinned", DEFAULT_LOAD, pinFastest = true)
        // mmap-on = S0's loading (repacked weights also stay resident as file pages);
        // repack-off only makes sense with mmap (weights used in place, reclaimable).
        out += BenchConfig("mmap-on", DEFAULT_LOAD.copy(mmap = true))
        out += BenchConfig("repack-off", DEFAULT_LOAD.copy(repack = false, mmap = true))
        out += BenchConfig("kv-f16", DEFAULT_LOAD.copy(kvType = LlmEngine.KvType.F16))
        out += BenchConfig("kv-f16-fa-off", DEFAULT_LOAD.copy(kvType = LlmEngine.KvType.F16, flashAttn = LlmEngine.FlashAttn.OFF))
        out += BenchConfig("ctx-512", DEFAULT_LOAD.copy(contextSize = 512))
        out += BenchConfig("ctx-2048", DEFAULT_LOAD.copy(contextSize = 2048))
        out += BenchConfig(
            "s0-like",
            LlmEngine.LoadConfig(contextSize = 2048, threads = 4, batchSize = 512, kvType = LlmEngine.KvType.F16, flashAttn = LlmEngine.FlashAttn.AUTO, repack = true, mmap = true),
            threads = 4, pinFastest = false, mode = LlmEngine.PrefixMode.COLD, fastForward = false
        )
        return out
    }

    fun loadCount(plan: List<BenchConfig>): Int =
        plan.indices.count { i -> i == 0 || plan[i].loadKey != plan[i - 1].loadKey }
}

/** Seam for memory readings so the runner is JVM-testable. */
interface MemorySource {
    fun snapshot(includePss: Boolean = true): ProcessMemoryProbe.Snapshot
    fun resetPeak(): Boolean
}

object ProcMemorySource : MemorySource {
    override fun snapshot(includePss: Boolean) = ProcessMemoryProbe.snapshot(includePss)
    override fun resetPeak() = ProcessMemoryProbe.resetPeak()
}

/**
 * Runs a list of [BenchConfig]s against one model on one [LlmEngine]. Configs with the
 * same [BenchConfig.loadKey] in a row share a load and its prefix KV; every load builds
 * the prefix once, and the first load also saves it to [stateDir] and measures restoring it.
 */
class BenchmarkRunner(
    private val engine: LlmEngine,
    private val modelFile: File,
    private val model: OnDeviceModel,
    private val topology: CpuTopology,
    private val memory: MemorySource,
    private val stateDir: File?,
    private val measuredRuns: Int = SweepPlan.MEASURED_RUNS,
    private val warmupRuns: Int = SweepPlan.WARMUP_RUNS,
    private val runTimeoutMs: Long = 60_000L,
    private val cancelled: () -> Boolean = { false },
    private val onStatus: (String) -> Unit = {}
) {
    var lastLoad: LlmEngine.LoadResult.Success? = null
        private set

    fun stateFileFor(load: LlmEngine.LoadConfig): File? = stateDir?.let { dir ->
        val prefix = BenchmarkPrompt.prefix(model.promptFormat)
        val key = RoutingDefaults.prefixStateKey(model.sha256 ?: "unverified-${model.fileName}", BenchmarkPrompt.VERSION, prefix, load.kvType, RoutingDefaults.LLAMA_TAG)
        File(dir, "prefix-$key.state")
    }

    fun run(plan: List<BenchConfig>): List<ConfigResult> {
        val results = mutableListOf<ConfigResult>()
        // Process RSS before the first model load: every config's peak is reported
        // against this one baseline, so configs are comparable with each other.
        val baselineKb = memory.snapshot(false).rssKb
        var currentKey: LlmEngine.LoadConfig? = null
        var currentLoad: LoadInfo? = null
        var loadsDone = 0
        for ((i, cfg) in plan.withIndex()) {
            if (cancelled()) break
            val threads = if (cfg.threads > 0) cfg.threads else topology.autoThreads()
            val tag = "[${i + 1}/${plan.size} ${cfg.name}]"
            var reused = true
            if (currentKey != cfg.loadKey || currentLoad == null) {
                reused = false
                onStatus("$tag 加载模型…")
                val info = loadAndPrefix(cfg, threads, saveState = loadsDone == 0)
                loadsDone++
                if (info.second != null) {
                    currentKey = null
                    currentLoad = null
                    results += ConfigResult(cfg, threads, emptyList(), null, info.first, false, emptyList(), warmupRuns, memory.snapshot(false).peakRssKb, info.second, baselineKb = baselineKb)
                    continue
                }
                currentKey = cfg.loadKey
                currentLoad = info.first
            }
            val load = currentLoad ?: continue
            val pinned = if (cfg.pinFastest && topology.cpuCount > 0) topology.fastest(threads) else emptyList()
            val affErr = engine.setCpuAffinity(pinned).get()
            engine.setThreads(threads).get()
            val runs = mutableListOf<BenchmarkRun>()
            var sampledMax = maxOf(load.after.rssKb, load.afterPrefix?.rssKb ?: -1)
            for (r in 0 until warmupRuns + measuredRuns) {
                if (cancelled()) break
                val case = if (r < warmupRuns) BenchmarkPrompt.CASES[0] else BenchmarkPrompt.CASES[(r - warmupRuns) % BenchmarkPrompt.CASES.size]
                onStatus("$tag ${if (r < warmupRuns) "预热" else "运行 ${r - warmupRuns + 1}/$measuredRuns"} ${case.id}…")
                val g = engine.generate(
                    LlmEngine.GenerateRequest(
                        prompt = BenchmarkPrompt.suffix(model.promptFormat, case),
                        prefixMode = cfg.mode,
                        maxTokens = BenchmarkPrompt.MAX_TOKENS,
                        grammar = BenchmarkPrompt.GRAMMAR,
                        temperature = 0f,
                        timeoutMs = runTimeoutMs,
                        fastForward = cfg.fastForward
                    )
                ).get()
                val mem = memory.snapshot(false)
                sampledMax = maxOf(sampledMax, mem.rssKb)
                val verdict = if (r >= warmupRuns && g.isSuccess) RouteOutputCheck.check(g.text, case) else null
                runs += BenchmarkRun(
                    index = r, status = g.status, promptTokens = g.promptTokens,
                    generatedTokens = g.generatedTokens, promptEvalMs = g.promptEvalMs,
                    ttftMs = g.ttftMs, decodeMs = g.decodeMs, totalMs = g.totalMs,
                    rssKb = mem.rssKb, pssKb = mem.pssKb, output = g.text, error = g.error,
                    caseId = case.id, prefixTokens = g.prefixTokens, suffixTokens = g.suffixTokens,
                    correct = verdict?.ok, checkReason = verdict?.reason ?: "",
                    decodeCalls = g.decodeCalls, forcedTokens = g.forcedTokens, draftTokens = g.draftTokens
                )
                if (g.status == LlmEngine.Status.CANCELLED) break
            }
            results += ConfigResult(
                config = cfg, threads = threads, pinnedCpus = pinned, affinityError = affErr,
                load = load, loadReused = reused, runs = runs, warmupRuns = warmupRuns,
                // VmHWM is only per-config when the reset before this load worked;
                // otherwise it is the process-wide peak, so use the sampled maximum.
                peakRssKb = if (load.peakResetOk) memory.snapshot(false).peakRssKb else sampledMax,
                baselineKb = baselineKb,
                peakSource = if (load.peakResetOk) "VmHWM" else "sampled"
            )
        }
        engine.setCpuAffinity(emptyList()).get()
        return results
    }

    /** Returns (info, error). */
    private fun loadAndPrefix(cfg: BenchConfig, threads: Int, saveState: Boolean): Pair<LoadInfo?, String?> {
        engine.release().get()
        val peakReset = memory.resetPeak()
        val before = memory.snapshot(false)
        val loadCfg = cfg.load.copy(threads = threads)
        val res = engine.load(modelFile, loadCfg).get()
        if (res is LlmEngine.LoadResult.Failure) return Pair(null, "load failed: ${res.reason}")
        res as LlmEngine.LoadResult.Success
        lastLoad = res
        val after = memory.snapshot(true)
        val prefixText = BenchmarkPrompt.prefix(model.promptFormat)
        val stateFile = if (saveState) stateFileFor(loadCfg) else null
        val prefix = engine.setPrefix(prefixText, stateFile, forceRebuild = true).get()
        var restore: LlmEngine.PrefixResult? = null
        if (stateFile != null && prefix is LlmEngine.PrefixResult.Success && prefix.savedToFile) {
            restore = engine.setPrefix(prefixText, stateFile, forceRebuild = false).get()
        }
        val afterPrefix = memory.snapshot(false)
        val info = LoadInfo(res.loadMs, res.memoryLog, before.rssKb, after, afterPrefix, prefix, restore, peakReset)
        val err = (prefix as? LlmEngine.PrefixResult.Failure)?.let { "prefix failed: ${it.reason}" }
        return Pair(info, err)
    }
}
