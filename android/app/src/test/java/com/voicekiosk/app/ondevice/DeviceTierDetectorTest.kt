package com.voicekiosk.app.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceTierDetectorTest {
    private val gib = 1024L * 1024L * 1024L
    private val dotprod = setOf("fp", "asimd", "asimdhp", "asimddp", "crc32")

    private fun profile(
        ramGiB: Double,
        features: Set<String> = dotprod,
        storageGiB: Double = 32.0,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        lowRam: Boolean = false
    ) = DeviceProfile(
        totalRamBytes = (ramGiB * gib).toLong(),
        availRamBytes = (ramGiB * gib / 2).toLong(),
        freeStorageBytes = (storageGiB * gib).toLong(),
        supportedAbis = abis,
        cpuFeatures = features,
        cpuCores = 8,
        sdkInt = 33,
        lowRamDevice = lowRam
    )

    @Test fun marketed3GbIsLow() = assertEquals(DeviceTier.LOW, DeviceTierDetector.classify(profile(2.8)).tier)
    @Test fun marketed4GbIsMid() = assertEquals(DeviceTier.MID, DeviceTierDetector.classify(profile(3.6)).tier)
    @Test fun marketed6GbIsMid() = assertEquals(DeviceTier.MID, DeviceTierDetector.classify(profile(5.6)).tier)
    @Test fun marketed8GbWithDotprodIsHigh() = assertEquals(DeviceTier.HIGH, DeviceTierDetector.classify(profile(7.3)).tier)
    @Test fun marketed12GbIsHigh() = assertEquals(DeviceTier.HIGH, DeviceTierDetector.classify(profile(11.2)).tier)

    @Test
    fun noDotprodIsLowEvenWithLotsOfRam() {
        val d = DeviceTierDetector.classify(profile(11.0, features = setOf("fp", "asimd")))
        assertEquals(DeviceTier.LOW, d.tier)
        assertTrue(d.reasons.any { it.contains("dotprod") })
    }

    @Test fun nonArm64IsLow() = assertEquals(DeviceTier.LOW, DeviceTierDetector.classify(profile(8.0, abis = listOf("x86_64"))).tier)
    @Test fun lowRamFlagIsLow() = assertEquals(DeviceTier.LOW, DeviceTierDetector.classify(profile(8.0, lowRam = true)).tier)

    @Test
    fun insufficientStorageStepsDown() {
        // HIGH needs ~1.1GB + 0.5GB headroom; 1.2GB free steps down to MID (no model since S0.2).
        val d = DeviceTierDetector.classify(profile(7.5, storageGiB = 1.2))
        assertEquals(DeviceTier.HIGH, d.capabilityTier)
        assertEquals(DeviceTier.MID, d.tier)
        val none = DeviceTierDetector.classify(profile(7.5, storageGiB = 0.3))
        assertEquals(DeviceTier.LOW, none.tier)
    }

    @Test
    fun userCapLowersTier() {
        assertEquals(DeviceTier.MID, DeviceTierDetector.classify(profile(12.0), userMaxTier = DeviceTier.MID).tier)
        assertEquals(DeviceTier.LOW, DeviceTierDetector.classify(profile(12.0), userMaxTier = DeviceTier.LOW).tier)
        // A cap never raises the tier.
        assertEquals(DeviceTier.MID, DeviceTierDetector.classify(profile(5.0), userMaxTier = DeviceTier.HIGH).tier)
    }

    @Test
    fun cpuinfoFeaturesAreIntersectedAcrossCores() {
        val cpuinfo = """
            processor	: 0
            BogoMIPS	: 38.40
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
            CPU implementer	: 0x41

            processor	: 4
            Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp i8mm sve sve2
            CPU part	: 0xd41
        """.trimIndent()
        val f = DeviceTierDetector.parseCpuFeatures(cpuinfo)
        assertTrue("asimddp" in f)
        assertTrue("i8mm" !in f) // only on big cores → not usable everywhere
        assertTrue("sve2" !in f)
    }

    @Test
    fun cpuinfoWithoutFeaturesYieldsEmpty() {
        assertTrue(DeviceTierDetector.parseCpuFeatures("processor : 0\nHardware : Qualcomm").isEmpty())
        assertTrue(DeviceTierDetector.parseCpuFeatures("").isEmpty())
    }

    @Test
    fun thresholdsSitBetweenMarketedClasses() {
        assertTrue(DeviceTierDetector.MID_MIN_RAM_BYTES > (3.0 * gib).toLong())
        assertTrue(DeviceTierDetector.MID_MIN_RAM_BYTES < (3.5 * gib).toLong())
        assertTrue(DeviceTierDetector.HIGH_MIN_RAM_BYTES > (6.0 * gib).toLong())
        assertTrue(DeviceTierDetector.HIGH_MIN_RAM_BYTES < (7.2 * gib).toLong())
    }
}
