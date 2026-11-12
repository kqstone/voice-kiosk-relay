package com.voicekiosk.app.ondevice

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/** Device capability tier for on-device LLM selection (plan §0). */
enum class DeviceTier(val label: String) {
    /** No model: rules + cloud only. */
    LOW("低档（不下载模型，仅规则+云端）"),

    /**
     * S0.2: no default model either (0.5–0.6B failed the routing cases); rules + cloud.
     * 0.5B / 0.6B stay selectable in the benchmark as experimental.
     */
    MID("中档（默认不下载模型，仅规则+云端；0.5B/0.6B 仅作实验）"),

    /** Qwen2.5-1.5B Q4_K_M by default: routing / slots, later short replies and memory extraction. */
    HIGH("高档（默认 Qwen2.5-1.5B Q4_K_M）")
}

/** Raw inputs, collected on device or injected in tests. */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availRamBytes: Long,
    val freeStorageBytes: Long,
    val supportedAbis: List<String>,
    val cpuFeatures: Set<String>,
    val cpuCores: Int,
    val sdkInt: Int,
    val manufacturer: String = "",
    val model: String = "",
    val socModel: String = "",
    val lowRamDevice: Boolean = false
) {
    val hasDotProd: Boolean get() = "asimddp" in cpuFeatures
    val hasI8mm: Boolean get() = "i8mm" in cpuFeatures
    val hasFp16Arith: Boolean get() = "asimdhp" in cpuFeatures
    val hasSve: Boolean get() = "sve" in cpuFeatures
    val hasSve2: Boolean get() = "sve2" in cpuFeatures
    val isArm64: Boolean get() = supportedAbis.firstOrNull() == "arm64-v8a" || "arm64-v8a" in supportedAbis
}

data class TierDecision(
    val tier: DeviceTier,
    /** Tier from RAM/CPU alone, before storage or user caps. */
    val capabilityTier: DeviceTier,
    val reasons: List<String>
)

object DeviceTierDetector {
    private const val GIB = 1024L * 1024L * 1024L

    /**
     * MemTotal is always below the marketed RAM size (kernel/carve-outs): a "4GB" phone
     * reports ~3.5–3.8 GiB, "6GB" ~5.4–5.7 GiB, "8GB" ~7.2–7.6 GiB. Thresholds are
     * placed between marketed classes so the plan's 4GB/8GB cut-offs hold in practice.
     */
    const val MID_MIN_RAM_BYTES = 3L * GIB + GIB / 4            // 3.25 GiB ≈ marketed 4GB
    const val HIGH_MIN_RAM_BYTES = 6L * GIB + (GIB * 3) / 4     // 6.75 GiB ≈ marketed 8GB

    /** Extra free space required beyond the model file (temp file, KV, other app data). */
    const val STORAGE_HEADROOM_BYTES = 512L * 1024L * 1024L

    /** Download size of the tier's default model (MID / LOW have none since S0.2). */
    fun requiredModelBytes(tier: DeviceTier): Long = OnDeviceModelCatalog.defaultFor(tier)?.sizeBytes ?: 0L

    fun classify(p: DeviceProfile, userMaxTier: DeviceTier? = null): TierDecision {
        val reasons = mutableListOf<String>()
        val capability: DeviceTier = when {
            !p.isArm64 -> {
                reasons += "非 arm64-v8a 设备（${p.supportedAbis.joinToString()}），不支持端侧模型"
                DeviceTier.LOW
            }
            p.lowRamDevice -> {
                reasons += "系统标记为低内存设备（isLowRamDevice）"
                DeviceTier.LOW
            }
            p.totalRamBytes < MID_MIN_RAM_BYTES -> {
                reasons += "内存 ${fmtGiB(p.totalRamBytes)} < 4GB 档"
                DeviceTier.LOW
            }
            !p.hasDotProd -> {
                reasons += "CPU 不支持 dotprod（asimddp），int8 推理过慢"
                DeviceTier.LOW
            }
            p.totalRamBytes >= HIGH_MIN_RAM_BYTES -> {
                reasons += "内存 ${fmtGiB(p.totalRamBytes)} ≥ 8GB 档且支持 dotprod"
                DeviceTier.HIGH
            }
            else -> {
                reasons += "内存 ${fmtGiB(p.totalRamBytes)} 属 4–6GB 档，支持 dotprod"
                DeviceTier.MID
            }
        }

        var tier = capability
        // Storage gate: step down until the tier's model fits.
        while (tier != DeviceTier.LOW &&
            p.freeStorageBytes < requiredModelBytes(tier) + STORAGE_HEADROOM_BYTES
        ) {
            val lower = DeviceTier.values()[tier.ordinal - 1]
            reasons += "可用存储 ${fmtGiB(p.freeStorageBytes)} 不足以下载${tier.name}档模型，降为${lower.name}"
            tier = lower
        }
        if (userMaxTier != null && userMaxTier.ordinal < tier.ordinal) {
            reasons += "用户手动限制为${userMaxTier.name}档"
            tier = userMaxTier
        }
        return TierDecision(tier, capability, reasons)
    }

    /**
     * Parses /proc/cpuinfo "Features" lines. Returns the features common to ALL cores
     * (inference threads may be scheduled on any core of a big.LITTLE cluster).
     */
    fun parseCpuFeatures(cpuinfo: String): Set<String> {
        val perCore = cpuinfo.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("Features", ignoreCase = true) && it.contains(':') }
            .map { line -> line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet() }
            .toList()
        if (perCore.isEmpty()) return emptySet()
        return perCore.reduce { acc, s -> acc intersect s }
    }

    fun fmtGiB(bytes: Long): String = String.format(java.util.Locale.US, "%.1fGB", bytes.toDouble() / GIB)

    /** Collects a [DeviceProfile] on a real device. Never throws. */
    fun collect(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        try {
            am?.getMemoryInfo(mi)
        } catch (_: Exception) {
        }
        val cpuinfo = try {
            File("/proc/cpuinfo").readText()
        } catch (_: Exception) {
            ""
        }
        val free = try {
            StatFs(context.filesDir.absolutePath).availableBytes
        } catch (_: Exception) {
            0L
        }
        val soc = if (Build.VERSION.SDK_INT >= 31) {
            try {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
            } catch (_: Throwable) {
                ""
            }
        } else {
            Build.HARDWARE ?: ""
        }
        return DeviceProfile(
            totalRamBytes = mi.totalMem,
            availRamBytes = mi.availMem,
            freeStorageBytes = free,
            supportedAbis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            cpuFeatures = parseCpuFeatures(cpuinfo),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER ?: "",
            model = Build.MODEL ?: "",
            socModel = soc,
            lowRamDevice = am?.isLowRamDevice ?: false
        )
    }
}
