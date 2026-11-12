package com.voicekiosk.app.ondevice

import android.os.Debug
import java.io.File
import java.util.Locale

/** Process memory readings for the benchmark (KiB). */
object ProcessMemoryProbe {
    /**
     * [rssAnonKb]: private anonymous pages (repacked weights, KV cache, compute buffers,
     * heap). [rssFileKb]: file-backed pages — mostly the GGUF mmap, which the kernel can
     * drop under pressure. RSS = anon + file + shmem.
     */
    data class Snapshot(
        val rssKb: Long,
        val peakRssKb: Long,
        val pssKb: Long,
        val rssAnonKb: Long = -1,
        val rssFileKb: Long = -1
    )

    /** Parses a `/proc/self/status` field such as `VmRSS:   123456 kB`. Returns -1 if absent. */
    fun parseStatusField(status: String, field: String): Long {
        val line = status.lineSequence().firstOrNull { it.startsWith("$field:") } ?: return -1L
        return line.substringAfter(':').trim().split(Regex("\\s+")).firstOrNull()?.toLongOrNull() ?: -1L
    }

    fun parse(status: String, pssKb: Long): Snapshot = Snapshot(
        rssKb = parseStatusField(status, "VmRSS"),
        peakRssKb = parseStatusField(status, "VmHWM"),
        pssKb = pssKb,
        rssAnonKb = parseStatusField(status, "RssAnon"),
        rssFileKb = parseStatusField(status, "RssFile")
    )

    fun snapshot(includePss: Boolean = true): Snapshot {
        val status = try { File("/proc/self/status").readText() } catch (_: Exception) { "" }
        val pss = if (includePss) {
            try {
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                mi.totalPss.toLong()
            } catch (_: Throwable) {
                -1L
            }
        } else -1L
        return parse(status, pss)
    }

    /**
     * Resets VmHWM (peak RSS) to the current RSS by writing "5" to /proc/self/clear_refs
     * (Linux ≥ 4.0), so each sweep config gets its own peak. Returns false if not allowed.
     */
    fun resetPeak(): Boolean = try {
        File("/proc/self/clear_refs").writeText("5")
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * Memory breakdown from llama.cpp's load log, e.g.
 * `load_tensors:   CPU_REPACK model buffer size =   738.00 MiB`,
 * `llama_kv_cache:        CPU KV buffer size =    14.88 MiB`,
 * `sched_reserve:        CPU compute buffer size =   148.01 MiB`.
 */
data class MemoryBreakdown(
    val mappedMiB: Double,
    val repackMiB: Double,
    val otherModelMiB: Double,
    val kvMiB: Double,
    val computeMiB: Double,
    val outputMiB: Double
) {
    /** Anonymous memory llama.cpp allocates (everything except the read-only mmap). */
    val anonMiB: Double get() = repackMiB + otherModelMiB + kvMiB + computeMiB + outputMiB

    fun describe(): String = String.format(
        Locale.US,
        "weights_mmap=%.0fMiB repacked=%.0fMiB other_weights=%.0fMiB kv=%.1fMiB compute=%.1fMiB output=%.1fMiB (llama_anon≈%.0fMiB)",
        mappedMiB, repackMiB, otherModelMiB, kvMiB, computeMiB, outputMiB, anonMiB
    )

    companion object {
        private val SIZE = Regex("""^(?:\S+:\s+)?(\S+)\s+(model|KV|compute|output)\s+buffer size\s*=\s*([0-9.]+)\s*MiB""")

        fun parse(log: String): MemoryBreakdown? {
            var mapped = 0.0; var repack = 0.0; var other = 0.0; var kv = 0.0; var compute = 0.0; var output = 0.0
            var any = false
            for (raw in log.lineSequence()) {
                val m = SIZE.find(raw.trim()) ?: continue
                val (buf, kind, v) = m.destructured
                val mib = v.toDoubleOrNull() ?: continue
                any = true
                when (kind) {
                    "model" -> when {
                        buf.contains("REPACK", ignoreCase = true) -> repack += mib
                        buf.contains("Mapped", ignoreCase = true) -> mapped += mib
                        else -> other += mib
                    }
                    "KV" -> kv += mib
                    "compute" -> compute += mib
                    "output" -> output += mib
                }
            }
            return if (any) MemoryBreakdown(mapped, repack, other, kv, compute, output) else null
        }
    }
}
