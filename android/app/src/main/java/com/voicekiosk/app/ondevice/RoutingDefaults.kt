package com.voicekiosk.app.ondevice

import java.security.MessageDigest

/**
 * S0.1 defaults for the on-device routing workload (short prefix-cached prompt, ≤48-token
 * JSON answer). Chosen from the S0 phone report + host measurements; the sweep in the
 * benchmark screen re-checks each one on the phone:
 *
 * - n_ctx 1024: prefix (~420 tok) + suffix (~30) + answer (48) ≈ 500, leaves room for a few
 *   dialog-state lines; 2048 only grows the KV cache.
 * - KV q8_0 (+ flash attention, required for a quantized V cache): half of f16.
 * - batch 256: bounds the compute buffer; prefix is built once, suffixes are < 64 tokens.
 * - REPACK on: Q4_K → interleaved layout for the i8mm/dotprod GEMM kernels. It costs an
 *   anonymous copy of the repacked weights (why S0 showed RSS ≈ 2× file size while the
 *   mmap pages were still resident); the sweep measures "repack off" to quantify it.
 * - mmap OFF: this is the S0 "RSS ≈ 2 GB for a 1.1 GB GGUF" fix. With mmap + REPACK the
 *   repacked tensors' file pages stay mapped next to their anonymous repacked copy
 *   (llama.cpp only unmaps the two ends of the file), so RSS ≈ whole file + repacked
 *   size until the kernel drops the clean pages. Reading without mmap puts every weight
 *   in memory exactly once (host, Qwen2.5-1.5B: +1.1 GB vs +1.77 GB, same speed). Cost:
 *   anonymous memory is not reclaimable like file pages, and load reads the whole file.
 * - threads: number of big/prime cores, capped at 4 (4 on SM8650-like 1+5+2 layouts).
 * - NO core pinning: on the S0.1 phone run pinning hurt badly (Qwen3-0.6B decode 8.6
 *   vs 46 tok/s unpinned, one EINVAL). The scheduler already places busy threads on big
 *   cores; pinning stays an opt-in sweep comparison ("pinned").
 * - grammar fast-forward on: forced JSON keys/punctuation/enum tails are decoded as a
 *   verified draft batch (identical output, fewer decode calls).
 */
object RoutingDefaults {
    const val CONTEXT_SIZE = 1024
    const val BATCH_SIZE = 256
    val KV_TYPE = LlmEngine.KvType.Q8_0
    val FLASH_ATTN = LlmEngine.FlashAttn.ON
    const val REPACK = true
    const val MMAP = false
    const val PIN_FASTEST_CORES = false
    const val FAST_FORWARD = true
    const val MAX_TOKENS = BenchmarkPrompt.MAX_TOKENS

    /**
     * Cache key for the saved prefix KV state: any change to the model bytes, the prompt
     * text, the KV layout, or the llama.cpp build invalidates it.
     */
    fun prefixStateKey(modelSha256: String, promptVersion: String, prefixText: String, kvType: LlmEngine.KvType, llamaTag: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        listOf(modelSha256, promptVersion, kvType.label, llamaTag).forEach { md.update(it.toByteArray()); md.update(0) }
        md.update(prefixText.toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }.take(24)
    }

    /** Pinned llama.cpp source (scripts/fetch-llama-cpp.sh); part of the state key. */
    const val LLAMA_TAG = "llama.cpp-v0.9.0-cd959bae"
}
