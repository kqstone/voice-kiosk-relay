package com.voicekiosk.app.ondevice

import java.io.File
import java.util.Locale

/**
 * CPU cluster layout from /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq.
 * Phones mix 1–3 core types (e.g. SM8650: 1×3.3 GHz + 5×~3.0 GHz + 2×2.3 GHz); llama.cpp
 * threads that land on little cores stall every matmul barrier, so the benchmark can pin
 * inference to the fastest cores.
 */
data class CpuTopology(val maxFreqKhz: Map<Int, Long>) {

    val cpuCount: Int get() = maxFreqKhz.size

    /** CPU ids sorted fastest first (ties: lower id first). */
    val fastestFirst: List<Int>
        get() = maxFreqKhz.entries.sortedWith(compareByDescending<Map.Entry<Int, Long>> { it.value }.thenBy { it.key }).map { it.key }

    /** The [n] fastest cpus, ascending by id. */
    fun fastest(n: Int): List<Int> = fastestFirst.take(n.coerceAtLeast(0)).sorted()

    /** Cores within [ratio] of the top max frequency ("prime + performance" cores). */
    fun bigCoreCount(ratio: Double = 0.8): Int {
        val top = maxFreqKhz.values.maxOrNull() ?: return 0
        return maxFreqKhz.values.count { it >= top * ratio }
    }

    /**
     * Thread count used when the user picks "auto": the big cores, capped at 4 (more
     * threads than ~4 rarely helps a 0.5–1.7B model and makes thermal throttling worse).
     */
    fun autoThreads(): Int = if (maxFreqKhz.isEmpty()) LlmEngine.defaultThreadCount() else bigCoreCount().coerceIn(2, 4)

    /** e.g. "1×3.30GHz[7] + 5×3.15GHz[2-6] + 2×2.27GHz[0-1]". */
    fun describe(): String {
        if (maxFreqKhz.isEmpty()) return "unknown (cpufreq not readable)"
        return maxFreqKhz.entries.groupBy { it.value }.entries.sortedByDescending { it.key }.joinToString(" + ") { (khz, cpus) ->
            val ids = cpus.map { it.key }.sorted()
            "${ids.size}×${String.format(Locale.US, "%.2f", khz / 1_000_000.0)}GHz[${ranges(ids)}]"
        }
    }

    companion object {
        fun ranges(ids: List<Int>): String {
            if (ids.isEmpty()) return ""
            val out = mutableListOf<String>()
            var start = ids[0]
            var prev = ids[0]
            for (i in ids.drop(1) + Int.MIN_VALUE) {
                if (i == prev + 1) { prev = i; continue }
                out += if (start == prev) "$start" else "$start-$prev"
                start = i
                prev = i
            }
            return out.joinToString(",")
        }

        fun read(root: File = File("/sys/devices/system/cpu")): CpuTopology {
            val map = mutableMapOf<Int, Long>()
            val dirs = root.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) } ?: emptyArray()
            for (d in dirs) {
                val id = d.name.removePrefix("cpu").toIntOrNull() ?: continue
                val khz = try {
                    File(d, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                } catch (_: Exception) { continue }
                map[id] = khz
            }
            return CpuTopology(map)
        }
    }
}
