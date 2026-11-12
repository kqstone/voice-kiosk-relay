package com.voicekiosk.app.ondevice

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM stand-in for the native layer. Models the prefix cache: COLD evaluates
 * PREFIX_TOKENS + SUFFIX_TOKENS, CACHED only SUFFIX_TOKENS (and fails without a prefix).
 * Answers routing suffixes from [BenchmarkPrompt.CASES] with the expected JSON.
 */
class FakeNative : NativeLlm {
    companion object {
        const val PREFIX_TOKENS = 420
        const val FF_FORCED = 12
        const val SUFFIX_TOKENS = 25
        const val MEMLOG = "load_tensors:   CPU_Mapped model buffer size =  1059.89 MiB\n" +
            "load_tensors:   CPU_REPACK model buffer size =   738.00 MiB\n" +
            "llama_kv_cache:        CPU KV buffer size =    14.88 MiB\n" +
            "sched_reserve:        CPU compute buffer size =   148.01 MiB\n"

        fun answer(suffix: String): String {
            val c = BenchmarkPrompt.CASES.firstOrNull { suffix.contains(it.utterance) }
                ?: return "{\"intent\":\"chitchat\"}"
            val slots = c.expectedSlots.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
            return "{\"intent\":\"${c.expectedIntent}\",\"slots\":{$slots},\"route\":\"${c.expectedRoute}\",\"confidence\":0.9}"
        }
    }

    var initError: Throwable? = null
    var loadError: String? = null
    /** Fails loads whose params match. */
    var failLoadWhen: (NativeLoadParams) -> Boolean = { false }
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
    val concurrent = AtomicInteger(0)
    var maxConcurrent = 0
    var generateLatch: CountDownLatch? = null
    @Volatile var cancelled = false
    var lastLoadParams: NativeLoadParams? = null
    val loadParamsHistory = mutableListOf<NativeLoadParams>()
    var lastPrefixMode = -1
    var lastFastForward: Boolean? = null
    var prefix: String? = null
    var threads = 0
    var affinity: List<Int> = emptyList()
    var affinityThread: String? = null
    var affinityErrno = 0
    val generateModes = Collections.synchronizedList(mutableListOf<Int>())

    override fun backendInit(nativeLibDir: String): String {
        initError?.let { throw it }
        calls += "init"
        return "variant=android_armv8.2_1; CPU (fake)"
    }

    override fun systemInfo() = "CPU : NEON = 1 | DOTPROD = 1"

    override fun load(path: String, params: NativeLoadParams): NativeLoadResult {
        calls += "load"
        lastLoadParams = params
        loadParamsHistory += params
        prefix = null
        threads = params.threads
        return if (loadError != null) NativeLoadResult(0, loadError, 0.0, 0)
        else if (failLoadWhen(params)) NativeLoadResult(0, "llama_init_from_model failed", 0.0, 0)
        else NativeLoadResult(7L, null, 12.5, 1234, MEMLOG)
    }

    override fun modelDescription(handle: Long) = "fake 0.5B Q4_K"

    override fun setPrefix(handle: Long, prefix: String, statePath: String?, forceRebuild: Boolean): NativePrefixResult {
        calls += "prefix"
        this.prefix = prefix
        val f = statePath?.takeIf { it.isNotEmpty() }?.let(::File)
        if (f != null && !forceRebuild && f.exists() && f.readText() == prefix) {
            return NativePrefixResult(true, null, PREFIX_TOKENS, true, false, 0.1, 0.0, 3.0, 0.0, 4096)
        }
        var saved = false
        if (f != null) { f.writeText(prefix); saved = true }
        return NativePrefixResult(true, null, PREFIX_TOKENS, false, saved, 0.2, 2000.0, 0.0, 5.0, if (saved) 4096 else 0)
    }

    override fun generate(handle: Long, prompt: String, prefixMode: Int, maxTokens: Int, grammar: String?, temperature: Float, timeoutMs: Long, seed: Int, fastForward: Boolean): NativeGenerateResult {
        val now = concurrent.incrementAndGet()
        synchronized(this) { maxConcurrent = maxOf(maxConcurrent, now) }
        try {
            calls += "generate"
            lastFastForward = fastForward
            lastPrefixMode = prefixMode
            generateModes += prefixMode
            generateLatch?.await(5, TimeUnit.SECONDS)
            Thread.sleep(2)
            if (prefixMode != 0 && prefix == null) {
                return NativeGenerateResult(4, "", "prefix mode requested but no prefix set", 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0)
            }
            val status = if (cancelled) 2 else 0
            return when (prefixMode) {
                0 -> NativeGenerateResult(status, "{\"intent\":\"chitchat\"}", null, 300, 11, 1.0, 400.0, 420.0, 200.0, 620.0)
                1 -> NativeGenerateResult(status, answer(prompt), null, PREFIX_TOKENS + SUFFIX_TOKENS, 30, 1.0, 4000.0, 4040.0, 1000.0, 5040.0, PREFIX_TOKENS, SUFFIX_TOKENS, ffCalls(fastForward), ffForced(fastForward), ffForced(fastForward) + 2, 0.5)
                else -> NativeGenerateResult(status, answer(prompt), null, SUFFIX_TOKENS, 30, 0.1, 230.0, 270.0, if (fastForward) 250.0 else 400.0, if (fastForward) 520.0 else 670.0, PREFIX_TOKENS, SUFFIX_TOKENS, ffCalls(fastForward), ffForced(fastForward), ffForced(fastForward) + 2, 0.5)
            }
        } finally {
            concurrent.decrementAndGet()
        }
    }

    private fun ffForced(ff: Boolean) = if (ff) FF_FORCED else 0
    private fun ffCalls(ff: Boolean) = if (ff) 30 - FF_FORCED else 30

    override fun setThreads(handle: Long, threads: Int) { this.threads = threads }

    override fun setAffinity(cpus: IntArray): Int {
        affinity = cpus.toList()
        affinityThread = Thread.currentThread().name
        return affinityErrno
    }

    override fun cancel(handle: Long) { cancelled = true; generateLatch?.countDown() }
    override fun release(handle: Long) { calls += "release" }
}
