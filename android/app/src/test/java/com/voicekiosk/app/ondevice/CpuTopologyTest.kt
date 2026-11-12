package com.voicekiosk.app.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CpuTopologyTest {
    // Snapdragon 8 Gen 3 (SM8650): 2×A520 2.27 + 3×A720 2.96 + 2×A720 3.15 + 1×X4 3.30
    private val sm8650 = CpuTopology(
        mapOf(0 to 2_265_600L, 1 to 2_265_600L, 2 to 2_956_800L, 3 to 2_956_800L, 4 to 2_956_800L,
            5 to 3_148_800L, 6 to 3_148_800L, 7 to 3_302_400L)
    )

    @Test
    fun fastestCoresAndAutoThreads() {
        assertEquals(listOf(7, 5, 6, 2, 3, 4, 0, 1), sm8650.fastestFirst)
        assertEquals(listOf(2, 5, 6, 7), sm8650.fastest(4))
        assertEquals(6, sm8650.bigCoreCount())
        assertEquals(4, sm8650.autoThreads())
        assertEquals(8, sm8650.cpuCount)
    }

    @Test
    fun describeGroupsClusters() {
        assertEquals("1×3.30GHz[7] + 2×3.15GHz[5-6] + 3×2.96GHz[2-4] + 2×2.27GHz[0-1]", sm8650.describe())
        assertEquals("unknown (cpufreq not readable)", CpuTopology(emptyMap()).describe())
        assertEquals("0-2,5,7-8", CpuTopology.ranges(listOf(0, 1, 2, 5, 7, 8)))
    }

    @Test
    fun littleHeavyPhoneGetsAtLeastTwoThreads() {
        // 2 big + 6 little (e.g. Helio G-series)
        val t = CpuTopology((0..7).associateWith { if (it >= 6) 2_000_000L else 900_000L })
        assertEquals(2, t.bigCoreCount())
        assertEquals(2, t.autoThreads())
        // unknown topology falls back to the core-count heuristic
        assertTrue(CpuTopology(emptyMap()).autoThreads() >= 1)
    }

    @Test
    fun readsSysfsLayout() {
        val root = Files.createTempDirectory("cpu").toFile()
        try {
            for ((id, khz) in listOf(0 to "1800000", 1 to "1800000", 2 to "2800000")) {
                val d = java.io.File(root, "cpu$id/cpufreq").apply { mkdirs() }
                java.io.File(d, "cpuinfo_max_freq").writeText("$khz\n")
            }
            java.io.File(root, "cpu3").mkdirs() // offline core without cpufreq: skipped
            java.io.File(root, "cpufreq").mkdirs() // not a cpuN dir
            val t = CpuTopology.read(root)
            assertEquals(3, t.cpuCount)
            assertEquals(listOf(2), t.fastest(1))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun prefixStateKeyChangesWithEveryInput() {
        val base = RoutingDefaults.prefixStateKey("sha", "v2", "prefix", LlmEngine.KvType.Q8_0, "llama")
        assertEquals(24, base.length)
        assertEquals(base, RoutingDefaults.prefixStateKey("sha", "v2", "prefix", LlmEngine.KvType.Q8_0, "llama"))
        val variants = listOf(
            RoutingDefaults.prefixStateKey("sha2", "v2", "prefix", LlmEngine.KvType.Q8_0, "llama"),
            RoutingDefaults.prefixStateKey("sha", "v3", "prefix", LlmEngine.KvType.Q8_0, "llama"),
            RoutingDefaults.prefixStateKey("sha", "v2", "prefix!", LlmEngine.KvType.Q8_0, "llama"),
            RoutingDefaults.prefixStateKey("sha", "v2", "prefix", LlmEngine.KvType.F16, "llama"),
            RoutingDefaults.prefixStateKey("sha", "v2", "prefix", LlmEngine.KvType.Q8_0, "llama2")
        )
        variants.forEach { assertTrue(it != base) }
    }
}
