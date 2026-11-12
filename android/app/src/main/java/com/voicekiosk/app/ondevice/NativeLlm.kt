package com.voicekiosk.app.ondevice

import java.nio.charset.StandardCharsets

/**
 * Raw native surface of the on-device LLM. Abstracted as an interface so [LlmEngine]
 * can be unit-tested on the JVM with a fake; production uses [JniNativeLlm].
 *
 * All text crosses the JNI boundary as UTF-8 bytes (no JNI modified-UTF-8 pitfalls).
 */
interface NativeLlm {
    /** Loads the native library and CPU backends. Returns a backend summary, or throws. */
    fun backendInit(nativeLibDir: String): String
    fun systemInfo(): String
    fun load(path: String, params: NativeLoadParams): NativeLoadResult
    fun modelDescription(handle: Long): String

    /** Evaluates (or restores from [statePath]) the fixed prompt prefix into the KV cache. */
    fun setPrefix(handle: Long, prefix: String, statePath: String?, forceRebuild: Boolean): NativePrefixResult

    /** [prefixMode]: 0 = full prompt, 1 = cold (prefix + suffix from scratch), 2 = cached prefix. */
    fun generate(
        handle: Long,
        prompt: String,
        prefixMode: Int,
        maxTokens: Int,
        grammar: String?,
        temperature: Float,
        timeoutMs: Long,
        seed: Int,
        fastForward: Boolean = true
    ): NativeGenerateResult
    fun setThreads(handle: Long, threads: Int)

    /**
     * Pins the calling thread (and ggml workers it spawns). Empty = restore the mask from
     * before the first pin (no-op if never pinned). Returns errno (0 = ok).
     */
    fun setAffinity(cpus: IntArray): Int
    fun cancel(handle: Long)
    fun release(handle: Long)
}

data class NativeLoadParams(
    val contextSize: Int,
    val threads: Int,
    val batchSize: Int,
    /** ggml type id: 1 = f16, 8 = q8_0. */
    val kvType: Int,
    /** -1 auto, 0 off, 1 on. */
    val flashAttn: Int,
    /** false disables llama.cpp's extra buffer types (CPU_REPACK weight repacking). */
    val useExtraBufts: Boolean,
    /** false reads weights into anonymous memory instead of mmapping the GGUF. */
    val useMmap: Boolean = true
)

data class NativeLoadResult(
    val handle: Long,
    val error: String?,
    val loadMs: Double,
    val modelSizeBytes: Long,
    /** llama.cpp "buffer size = N MiB" log lines captured during load. */
    val memoryLog: String = ""
)

data class NativePrefixResult(
    val ok: Boolean,
    val error: String?,
    val tokens: Int,
    val restoredFromFile: Boolean,
    val savedToFile: Boolean,
    val tokenizeMs: Double,
    val buildMs: Double,
    val restoreMs: Double,
    val saveMs: Double,
    val stateBytes: Long
)

data class NativeGenerateResult(
    val statusCode: Int,
    val text: String,
    val error: String?,
    /** Tokens evaluated by this call (cached mode: suffix only). */
    val promptTokens: Int,
    val generatedTokens: Int,
    val tokenizeMs: Double,
    val promptEvalMs: Double,
    val ttftMs: Double,
    val decodeMs: Double,
    val totalMs: Double,
    val prefixTokens: Int = 0,
    val suffixTokens: Int = 0,
    /** llama_decode calls after the prompt (fast-forward batches several tokens per call). */
    val decodeCalls: Int = 0,
    /** Generated tokens taken from verified grammar drafts. */
    val forcedTokens: Int = 0,
    val draftTokens: Int = 0,
    val draftMs: Double = 0.0
)

/** JNI implementation backed by libvk_llm.so (+ libllama / libggml*). */
object JniNativeLlm : NativeLlm {
    @Volatile
    private var libraryLoaded = false

    @Synchronized
    private fun ensureLibrary() {
        if (!libraryLoaded) {
            System.loadLibrary("vk_llm")
            libraryLoaded = true
        }
    }

    override fun backendInit(nativeLibDir: String): String {
        ensureLibrary()
        return utf8(nativeBackendInit(nativeLibDir))
    }

    override fun systemInfo(): String {
        ensureLibrary()
        return utf8(nativeSystemInfo())
    }

    override fun load(path: String, params: NativeLoadParams): NativeLoadResult {
        ensureLibrary()
        val metrics = DoubleArray(2)
        val text = arrayOfNulls<ByteArray>(2)
        val handle = nativeLoad(
            path, params.contextSize, params.threads, params.batchSize, params.kvType,
            params.flashAttn, params.useExtraBufts, params.useMmap, metrics, text
        )
        return NativeLoadResult(
            handle = handle,
            error = text[0]?.let(::utf8),
            loadMs = metrics[0],
            modelSizeBytes = metrics[1].toLong(),
            memoryLog = text[1]?.let(::utf8) ?: ""
        )
    }

    override fun modelDescription(handle: Long): String = utf8(nativeModelDescription(handle))

    override fun setPrefix(handle: Long, prefix: String, statePath: String?, forceRebuild: Boolean): NativePrefixResult {
        val m = DoubleArray(8)
        val err = arrayOfNulls<ByteArray>(1)
        val ok = nativeSetPrefix(handle, prefix.toByteArray(StandardCharsets.UTF_8), statePath ?: "", forceRebuild, m, err)
        return NativePrefixResult(
            ok = ok, error = err[0]?.let(::utf8), tokens = m[0].toInt(),
            restoredFromFile = m[1] != 0.0, savedToFile = m[2] != 0.0, tokenizeMs = m[3],
            buildMs = m[4], restoreMs = m[5], saveMs = m[6], stateBytes = m[7].toLong()
        )
    }

    override fun generate(
        handle: Long,
        prompt: String,
        prefixMode: Int,
        maxTokens: Int,
        grammar: String?,
        temperature: Float,
        timeoutMs: Long,
        seed: Int,
        fastForward: Boolean
    ): NativeGenerateResult {
        val metrics = DoubleArray(14)
        val err = arrayOfNulls<ByteArray>(1)
        val text = nativeGenerate(
            handle,
            prompt.toByteArray(StandardCharsets.UTF_8),
            prefixMode,
            maxTokens,
            grammar?.toByteArray(StandardCharsets.UTF_8),
            temperature,
            timeoutMs,
            seed,
            fastForward,
            metrics,
            err
        )
        return NativeGenerateResult(
            statusCode = metrics[0].toInt(),
            text = utf8(text),
            error = err[0]?.let(::utf8),
            promptTokens = metrics[1].toInt(),
            generatedTokens = metrics[2].toInt(),
            tokenizeMs = metrics[3],
            promptEvalMs = metrics[4],
            ttftMs = metrics[5],
            decodeMs = metrics[6],
            totalMs = metrics[7],
            prefixTokens = metrics[8].toInt(),
            suffixTokens = metrics[9].toInt(),
            decodeCalls = metrics[10].toInt(),
            forcedTokens = metrics[11].toInt(),
            draftTokens = metrics[12].toInt(),
            draftMs = metrics[13]
        )
    }

    override fun setThreads(handle: Long, threads: Int) = nativeSetThreads(handle, threads)

    override fun setAffinity(cpus: IntArray): Int {
        ensureLibrary()
        return nativeSetAffinity(cpus)
    }

    override fun cancel(handle: Long) = nativeCancel(handle)

    override fun release(handle: Long) = nativeRelease(handle)

    private fun utf8(bytes: ByteArray?): String =
        if (bytes == null) "" else String(bytes, StandardCharsets.UTF_8)

    @JvmStatic private external fun nativeBackendInit(dir: String): ByteArray?
    @JvmStatic private external fun nativeSystemInfo(): ByteArray?
    @JvmStatic private external fun nativeLoad(
        path: String,
        nCtx: Int,
        nThreads: Int,
        nBatch: Int,
        kvType: Int,
        flashAttn: Int,
        useExtraBufts: Boolean,
        useMmap: Boolean,
        metricsOut: DoubleArray,
        errOut: Array<ByteArray?>
    ): Long
    @JvmStatic private external fun nativeModelDescription(handle: Long): ByteArray?
    @JvmStatic private external fun nativeSetPrefix(
        handle: Long,
        prefix: ByteArray,
        statePath: String,
        forceRebuild: Boolean,
        metricsOut: DoubleArray,
        errOut: Array<ByteArray?>
    ): Boolean
    @JvmStatic private external fun nativeGenerate(
        handle: Long,
        prompt: ByteArray,
        prefixMode: Int,
        maxTokens: Int,
        grammar: ByteArray?,
        temperature: Float,
        timeoutMs: Long,
        seed: Int,
        fastForward: Boolean,
        metricsOut: DoubleArray,
        errOut: Array<ByteArray?>
    ): ByteArray?
    @JvmStatic private external fun nativeSetThreads(handle: Long, n: Int)
    @JvmStatic private external fun nativeSetAffinity(cpus: IntArray): Int
    @JvmStatic private external fun nativeCancel(handle: Long)
    @JvmStatic private external fun nativeRelease(handle: Long)
}
