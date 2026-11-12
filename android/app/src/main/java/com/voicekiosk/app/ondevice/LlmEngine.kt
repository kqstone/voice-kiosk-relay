package com.voicekiosk.app.ondevice

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/**
 * Kotlin wrapper over llama.cpp (via [NativeLlm]).
 *
 * - All native work (init, load, generate, release) runs on ONE dedicated inference
 *   thread, so calls are serialised and the native context is never used concurrently.
 * - [cancel] is safe from any thread and interrupts an in-flight generation.
 * - Never throws for expected failures (missing/corrupt model, missing native library,
 *   bad grammar, timeout): every operation returns a result describing the outcome.
 *
 * Used by the benchmark screen (`:llmbench` process) and, since S1, by the voice
 * service's L1 router (RouterModelHost) only when on-device routing is enabled, the
 * device is HIGH tier and the default model is already downloaded.
 */
class LlmEngine(
    private val native: NativeLlm = JniNativeLlm,
    private val nativeLibDir: String = "",
    executor: ExecutorService? = null
) : Closeable {

    enum class KvType(val ggmlType: Int, val label: String) { F16(1, "f16"), Q8_0(8, "q8_0") }

    enum class FlashAttn(val code: Int, val label: String) { AUTO(-1, "auto"), OFF(0, "off"), ON(1, "on") }

    /** How [GenerateRequest.prompt] is interpreted. */
    enum class PrefixMode(val code: Int) {
        /** prompt = full formatted prompt, KV cleared (S0 behaviour). */
        NONE(0),
        /** prompt = suffix; prefix + suffix evaluated from an empty KV (no-cache baseline). */
        COLD(1),
        /** prompt = suffix; prefix KV from [setPrefix] reused, only the suffix is evaluated. */
        CACHED(2)
    }

    /**
     * Defaults are the S0.1 routing defaults (see [RoutingDefaults]): n_ctx 1024 is ~3×
     * what prefix + suffix + 48-token answer needs; q8_0 KV halves the cache vs f16 and
     * requires flash attention, which is therefore ON.
     */
    data class LoadConfig(
        val contextSize: Int = RoutingDefaults.CONTEXT_SIZE,
        val threads: Int = defaultThreadCount(),
        val batchSize: Int = RoutingDefaults.BATCH_SIZE,
        val kvType: KvType = RoutingDefaults.KV_TYPE,
        val flashAttn: FlashAttn = RoutingDefaults.FLASH_ATTN,
        /** false = no CPU_REPACK: weights are used straight from the read-only mmap. */
        val repack: Boolean = RoutingDefaults.REPACK,
        /** false = read weights into anonymous buffers (no double-resident mmap pages under REPACK). */
        val mmap: Boolean = RoutingDefaults.MMAP
    ) {
        /** Null if valid, else the reason (checked before any native call). */
        fun validate(): String? = when {
            contextSize !in 128..32768 -> "contextSize out of range"
            threads !in 1..16 -> "threads out of range"
            batchSize !in 8..2048 -> "batchSize out of range"
            kvType == KvType.Q8_0 && flashAttn == FlashAttn.OFF -> "q8_0 KV cache requires flash attention (auto/on)"
            else -> null
        }
    }

    sealed class PrefixResult {
        data class Success(
            val tokens: Int,
            val restoredFromFile: Boolean,
            val savedToFile: Boolean,
            val tokenizeMs: Double,
            val buildMs: Double,
            val restoreMs: Double,
            val saveMs: Double,
            val stateBytes: Long
        ) : PrefixResult()

        data class Failure(val reason: String) : PrefixResult()
    }

    data class GenerateRequest(
        val prompt: String,
        val prefixMode: PrefixMode = PrefixMode.NONE,
        val maxTokens: Int = 64,
        val grammar: String? = null,
        val temperature: Float = 0f,
        val timeoutMs: Long = 0L,
        val seed: Int = 42,
        /** Grammar fast-forward (greedy + grammar only); output is identical either way. */
        val fastForward: Boolean = true
    )

    enum class Status(val code: Int) {
        END_OF_GENERATION(0),
        MAX_TOKENS(1),
        CANCELLED(2),
        TIMEOUT(3),
        ERROR(4),
        CONTEXT_FULL(5);

        companion object {
            fun fromCode(code: Int): Status = values().firstOrNull { it.code == code } ?: ERROR
        }
    }

    sealed class LoadResult {
        data class Success(
            val description: String,
            val loadMs: Double,
            val modelSizeBytes: Long,
            val backendSummary: String,
            val systemInfo: String,
            val memoryLog: String = ""
        ) : LoadResult()

        data class Failure(val reason: String) : LoadResult()
    }

    data class GenerateResult(
        val status: Status,
        val text: String,
        val error: String?,
        val promptTokens: Int = 0,
        val generatedTokens: Int = 0,
        val tokenizeMs: Double = 0.0,
        val promptEvalMs: Double = 0.0,
        val ttftMs: Double = 0.0,
        val decodeMs: Double = 0.0,
        val totalMs: Double = 0.0,
        val prefixTokens: Int = 0,
        val suffixTokens: Int = 0,
        val decodeCalls: Int = 0,
        val forcedTokens: Int = 0,
        val draftTokens: Int = 0,
        val draftMs: Double = 0.0
    ) {
        val isSuccess: Boolean get() = status == Status.END_OF_GENERATION || status == Status.MAX_TOKENS
        val promptTokensPerSecond: Double
            get() = if (promptEvalMs > 0) promptTokens * 1000.0 / promptEvalMs else 0.0
        /** Decode speed excluding the first token (which is part of TTFT). */
        val decodeTokensPerSecond: Double
            get() = if (decodeMs > 0 && generatedTokens > 1) (generatedTokens - 1) * 1000.0 / decodeMs else 0.0

        companion object {
            fun failure(reason: String) = GenerateResult(Status.ERROR, "", reason)
        }
    }

    private val ownsExecutor = executor == null
    private val inference: ExecutorService = executor ?: Executors.newSingleThreadExecutor { r ->
        Thread(r, "vk-llm-inference").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    // Accessed only on the inference thread, except [activeHandle] which cancel() reads.
    private val activeHandle = AtomicLong(0L)
    private var backendSummary: String? = null
    private var systemInfo: String = ""
    private var closed = false

    val isLoaded: Boolean get() = activeHandle.get() != 0L

    fun load(modelFile: File, config: LoadConfig = LoadConfig()): Future<LoadResult> =
        inference.submit<LoadResult> { loadBlocking(modelFile, config) }

    fun generate(request: GenerateRequest): Future<GenerateResult> =
        inference.submit<GenerateResult> { generateBlocking(request) }

    /**
     * Evaluates the fixed [prefix] once so later [PrefixMode.CACHED] requests only pay for
     * their suffix. With [stateFile], a valid saved KV state for exactly these tokens is
     * restored instead, and a freshly built one is saved there.
     */
    fun setPrefix(prefix: String, stateFile: File? = null, forceRebuild: Boolean = false): Future<PrefixResult> =
        inference.submit<PrefixResult> { setPrefixBlocking(prefix, stateFile, forceRebuild) }

    /** Changes thread count without reloading. */
    fun setThreads(threads: Int): Future<Boolean> = inference.submit<Boolean> {
        val h = activeHandle.get()
        if (h == 0L || threads !in 1..16) return@submit false
        try { native.setThreads(h, threads); true } catch (_: Throwable) { false }
    }

    /**
     * Pins the inference thread (and the ggml worker threads it spawns per graph) to
     * [cpus]; empty = undo an earlier pin. Returns null on success, else a reason.
     */
    fun setCpuAffinity(cpus: List<Int>): Future<String?> = inference.submit<String?> {
        val rc = try { native.setAffinity(cpus.toIntArray()) } catch (t: Throwable) { return@submit "native: ${t.message}" }
        if (rc == 0) null else "sched_setaffinity errno=$rc"
    }

    /** Interrupts the in-flight generation, if any. Safe from any thread. */
    fun cancel() {
        val h = activeHandle.get()
        if (h != 0L) {
            try {
                native.cancel(h)
            } catch (_: Throwable) {
            }
        }
    }

    /** Frees the model (queued after any in-flight generation; call [cancel] first to hurry it). */
    fun release(): Future<Unit> = inference.submit<Unit> { releaseBlocking() }

    override fun close() {
        cancel()
        try {
            release().get()
        } catch (_: Throwable) {
        }
        closed = true
        if (ownsExecutor) inference.shutdown()
    }

    private fun loadBlocking(modelFile: File, config: LoadConfig): LoadResult {
        if (closed) return LoadResult.Failure("engine closed")
        GgufFileValidator.validate(modelFile)?.let { return LoadResult.Failure(it) }
        config.validate()?.let { return LoadResult.Failure(it) }

        val summary = backendSummary ?: try {
            native.backendInit(nativeLibDir).also {
                backendSummary = it
                systemInfo = try { native.systemInfo() } catch (_: Throwable) { "" }
            }
        } catch (t: Throwable) {
            // UnsatisfiedLinkError etc. — report, never crash.
            return LoadResult.Failure("native library unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }

        releaseBlocking()
        val result = try {
            native.load(
                modelFile.absolutePath,
                NativeLoadParams(
                    contextSize = config.contextSize,
                    threads = config.threads,
                    batchSize = config.batchSize,
                    kvType = config.kvType.ggmlType,
                    flashAttn = config.flashAttn.code,
                    useExtraBufts = config.repack,
                    useMmap = config.mmap
                )
            )
        } catch (t: Throwable) {
            return LoadResult.Failure("native load threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (result.handle == 0L) {
            return LoadResult.Failure(result.error?.takeIf { it.isNotBlank() } ?: "model load failed")
        }
        activeHandle.set(result.handle)
        val desc = try { native.modelDescription(result.handle) } catch (_: Throwable) { "" }
        return LoadResult.Success(desc, result.loadMs, result.modelSizeBytes, summary, systemInfo, result.memoryLog)
    }

    private fun setPrefixBlocking(prefix: String, stateFile: File?, forceRebuild: Boolean): PrefixResult {
        val h = activeHandle.get()
        if (h == 0L) return PrefixResult.Failure("model not loaded")
        stateFile?.parentFile?.mkdirs()
        val r = try {
            native.setPrefix(h, prefix, stateFile?.absolutePath, forceRebuild)
        } catch (t: Throwable) {
            return PrefixResult.Failure("native setPrefix threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (!r.ok) return PrefixResult.Failure(r.error?.takeIf { it.isNotBlank() } ?: "prefix failed")
        return PrefixResult.Success(
            r.tokens, r.restoredFromFile, r.savedToFile, r.tokenizeMs, r.buildMs, r.restoreMs,
            r.saveMs, r.stateBytes
        )
    }

    private fun generateBlocking(request: GenerateRequest): GenerateResult {
        val h = activeHandle.get()
        if (h == 0L) return GenerateResult.failure("model not loaded")
        if (request.prompt.isEmpty()) return GenerateResult.failure("empty prompt")
        if (request.maxTokens !in 1..4096) return GenerateResult.failure("maxTokens out of range")
        if (request.timeoutMs < 0) return GenerateResult.failure("timeoutMs must be >= 0")
        val r = try {
            native.generate(
                h,
                request.prompt,
                request.prefixMode.code,
                request.maxTokens,
                request.grammar?.takeIf { it.isNotBlank() },
                request.temperature,
                request.timeoutMs,
                request.seed,
                request.fastForward
            )
        } catch (t: Throwable) {
            return GenerateResult.failure("native generate threw: ${t.javaClass.simpleName}: ${t.message}")
        }
        val status = Status.fromCode(r.statusCode)
        return GenerateResult(
            status = status,
            text = r.text,
            error = r.error?.takeIf { it.isNotBlank() } ?: if (status == Status.ERROR) "unknown error" else null,
            promptTokens = r.promptTokens,
            generatedTokens = r.generatedTokens,
            tokenizeMs = r.tokenizeMs,
            promptEvalMs = r.promptEvalMs,
            ttftMs = r.ttftMs,
            decodeMs = r.decodeMs,
            totalMs = r.totalMs,
            prefixTokens = r.prefixTokens,
            suffixTokens = r.suffixTokens,
            decodeCalls = r.decodeCalls,
            forcedTokens = r.forcedTokens,
            draftTokens = r.draftTokens,
            draftMs = r.draftMs
        )
    }

    private fun releaseBlocking() {
        val h = activeHandle.getAndSet(0L)
        if (h != 0L) {
            try {
                native.release(h)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        /** Phones typically have 2–4 big cores; more threads than big cores hurts latency. */
        fun defaultThreadCount(cores: Int = Runtime.getRuntime().availableProcessors()): Int =
            when {
                cores >= 8 -> 4
                cores >= 4 -> cores - 2
                else -> cores.coerceAtLeast(1)
            }.coerceIn(1, 8)
    }
}

/** Cheap pre-flight checks so obviously bad files never reach native code. */
object GgufFileValidator {
    const val MIN_MODEL_BYTES = 1L * 1024 * 1024
    private val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** Returns null if the file looks like a GGUF model, otherwise a reason. */
    fun validate(file: File): String? {
        if (!file.exists()) return "model file not found: ${file.name}"
        if (!file.isFile) return "model path is not a file: ${file.name}"
        if (!file.canRead()) return "model file not readable: ${file.name}"
        if (file.length() < MIN_MODEL_BYTES) return "model file too small (${file.length()} bytes)"
        val header = ByteArray(4)
        val n = try {
            FileInputStream(file).use { it.read(header) }
        } catch (e: Exception) {
            return "model file read error: ${e.message}"
        }
        if (n != 4 || !header.contentEquals(MAGIC)) return "not a GGUF file (bad magic)"
        return null
    }
}
