package com.voicekiosk.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEngineSelectorTest {

    private val engineGoogle = TtsEngineInfo("com.google.android.tts", "Google 语音服务")
    private val engineIflytek = TtsEngineInfo("com.iflytek.speechcloud", "讯飞语音引擎")
    private val enginePico = TtsEngineInfo("com.svox.pico", "Pico TTS")

    @Test
    fun testCandidatePlan_orderingAndDeduplication() {
        val selector = TtsEngineSelector()
        val engines = listOf(engineGoogle, engineIflytek, enginePico)

        // Case 1: User preferred specified and different from default
        val plan1 = selector.buildCandidatePlan(
            preferredEngine = "com.iflytek.speechcloud",
            defaultEngine = "com.google.android.tts",
            availableEngines = engines
        )
        assertEquals(
            listOf("com.iflytek.speechcloud", "com.google.android.tts", "com.svox.pico"),
            plan1
        )

        // Case 2: User preferred same as default -> deduplicated
        val plan2 = selector.buildCandidatePlan(
            preferredEngine = "com.google.android.tts",
            defaultEngine = "com.google.android.tts",
            availableEngines = engines
        )
        assertEquals(
            listOf("com.google.android.tts", "com.iflytek.speechcloud", "com.svox.pico"),
            plan2
        )

        // Case 3: User preferred empty -> default first
        val plan3 = selector.buildCandidatePlan(
            preferredEngine = "",
            defaultEngine = "com.iflytek.speechcloud",
            availableEngines = engines
        )
        assertEquals(
            listOf("com.iflytek.speechcloud", "com.google.android.tts", "com.svox.pico"),
            plan3
        )
    }

    @Test
    fun testSelectionOrder_userPreferredFirst() {
        val selector = TtsEngineSelector()
        val engines = listOf(engineGoogle, engineIflytek, enginePico)
        val evaluationOrder = mutableListOf<String>()

        val outcome = selector.evaluate(
            preferredEngine = "com.iflytek.speechcloud",
            defaultEngine = "com.google.android.tts",
            availableEngines = engines,
            tryInit = { pkg ->
                evaluationOrder.add(pkg)
                EngineInitResult(
                    initCode = 0,
                    langAvailability = LangAvailability.AVAILABLE_ZH_CN
                )
            }
        )

        assertEquals(TtsStatus.READY, outcome.status)
        assertEquals("com.iflytek.speechcloud", outcome.selectedEngine)
        assertEquals("zh-CN", outcome.selectedLocale)
        // User preferred was tried first and succeeded, so subsequent engines were not evaluated
        assertEquals(listOf("com.iflytek.speechcloud"), evaluationOrder)
    }

    @Test
    fun testSelectionOrder_userPreferredFails_fallbackToDefault() {
        val selector = TtsEngineSelector()
        val engines = listOf(engineGoogle, engineIflytek, enginePico)
        val evaluationOrder = mutableListOf<String>()

        val outcome = selector.evaluate(
            preferredEngine = "com.iflytek.speechcloud",
            defaultEngine = "com.google.android.tts",
            availableEngines = engines,
            tryInit = { pkg ->
                evaluationOrder.add(pkg)
                if (pkg == "com.iflytek.speechcloud") {
                    // Preferred engine init fails
                    EngineInitResult(initCode = -1, langAvailability = LangAvailability.NOT_SUPPORTED)
                } else {
                    // Default engine succeeds
                    EngineInitResult(initCode = 0, langAvailability = LangAvailability.AVAILABLE_ZH_CN)
                }
            }
        )

        assertEquals(TtsStatus.READY, outcome.status)
        assertEquals("com.google.android.tts", outcome.selectedEngine)
        assertEquals(listOf("com.iflytek.speechcloud", "com.google.android.tts"), evaluationOrder)
    }

    @Test
    fun testSelectionOrder_userAndDefaultFail_fallbackToFirstChineseSupportingEngine() {
        val selector = TtsEngineSelector()
        val engines = listOf(engineGoogle, engineIflytek, enginePico)
        val evaluationOrder = mutableListOf<String>()

        val outcome = selector.evaluate(
            preferredEngine = "com.iflytek.speechcloud",
            defaultEngine = "com.google.android.tts",
            availableEngines = engines,
            tryInit = { pkg ->
                evaluationOrder.add(pkg)
                when (pkg) {
                    "com.iflytek.speechcloud" -> EngineInitResult(initCode = -1, langAvailability = LangAvailability.NOT_SUPPORTED)
                    "com.google.android.tts" -> EngineInitResult(initCode = 0, langAvailability = LangAvailability.MISSING_DATA)
                    "com.svox.pico" -> EngineInitResult(initCode = 0, langAvailability = LangAvailability.AVAILABLE_ZH_CN)
                    else -> EngineInitResult(initCode = -1, langAvailability = LangAvailability.NOT_SUPPORTED)
                }
            }
        )

        assertEquals(TtsStatus.READY, outcome.status)
        assertEquals("com.svox.pico", outcome.selectedEngine)
        assertEquals(listOf("com.iflytek.speechcloud", "com.google.android.tts", "com.svox.pico"), evaluationOrder)
    }

    @Test
    fun testSelection_fallbackToZhWhenZhCnUnavailable() {
        val selector = TtsEngineSelector()
        val engines = listOf(engineGoogle)

        val outcome = selector.evaluate(
            preferredEngine = null,
            defaultEngine = "com.google.android.tts",
            availableEngines = engines,
            tryInit = {
                EngineInitResult(initCode = 0, langAvailability = LangAvailability.AVAILABLE_ZH)
            }
        )

        assertEquals(TtsStatus.READY, outcome.status)
        assertEquals("com.google.android.tts", outcome.selectedEngine)
        assertEquals("zh", outcome.selectedLocale)
    }

    @Test
    fun testNoEngine_returnsNoEngineStatus() {
        val selector = TtsEngineSelector()

        val outcome = selector.evaluate(
            preferredEngine = null,
            defaultEngine = null,
            availableEngines = emptyList(),
            tryInit = {
                EngineInitResult(initCode = 0, langAvailability = LangAvailability.AVAILABLE_ZH_CN)
            }
        )

        assertEquals(TtsStatus.NO_ENGINE, outcome.status)
        assertNull(outcome.selectedEngine)
        assertEquals(TtsStatus.NO_ENGINE, selector.status)
        assertNull(selector.selectedEngine)
    }

    @Test
    fun testLanguageMissing_returnsLangMissingStatus() {
        val selector = TtsEngineSelector()
        val engines = listOf(engineGoogle, enginePico)

        val outcome = selector.evaluate(
            preferredEngine = null,
            defaultEngine = "com.google.android.tts",
            availableEngines = engines,
            tryInit = {
                // Both engines initialize successfully but neither has Chinese voice data
                EngineInitResult(initCode = 0, langAvailability = LangAvailability.MISSING_DATA)
            }
        )

        assertEquals(TtsStatus.LANG_MISSING, outcome.status)
        assertNull(outcome.selectedEngine)
        assertEquals(TtsStatus.LANG_MISSING, selector.status)
    }

    @Test
    fun testAllEnginesInitFailed_returnsInitFailedStatus() {
        val selector = TtsEngineSelector()
        val engines = listOf(engineGoogle, enginePico)

        val outcome = selector.evaluate(
            preferredEngine = null,
            defaultEngine = "com.google.android.tts",
            availableEngines = engines,
            tryInit = {
                // Both engines fail during onInit
                EngineInitResult(initCode = -1, langAvailability = LangAvailability.NOT_SUPPORTED)
            }
        )

        assertEquals(TtsStatus.INIT_FAILED, outcome.status)
        assertNull(outcome.selectedEngine)
        assertEquals(TtsStatus.INIT_FAILED, selector.status)
    }

    @Test
    fun testRetryCooldown_blocksImmediateRetryAndAllowsAfterCooldown() {
        val cooldown = 5000L
        var currentTime = 100_000L
        val selector = TtsEngineSelector(cooldownMs = cooldown, timeProvider = { currentTime })

        // 1. Initial attempt is allowed
        assertTrue("Initial attempt should be allowed", selector.canAttemptInit(currentTime))

        // 2. Record attempt
        selector.recordAttempt(currentTime)
        selector.updateStatus(TtsStatus.INIT_FAILED, null)

        // 3. 1 second later: blocked
        currentTime += 1000L
        assertFalse("Retry 1s later should be blocked by cooldown", selector.canAttemptInit(currentTime))

        // 4. 4999ms later: still blocked
        currentTime = 100_000L + 4999L
        assertFalse("Retry before cooldownMs should be blocked", selector.canAttemptInit(currentTime))

        // 5. Exactly at cooldown boundary: allowed
        currentTime = 100_000L + 5000L
        assertTrue("Retry at cooldownMs should be allowed", selector.canAttemptInit(currentTime))

        // 6. When status is already READY: no init attempt needed
        selector.updateStatus(TtsStatus.READY, "com.google.android.tts")
        assertFalse("Attempt should be disallowed when status is already READY", selector.canAttemptInit(currentTime))

        // 7. Reset cooldown permits immediate attempt if not ready
        selector.updateStatus(TtsStatus.LANG_MISSING, null)
        selector.recordAttempt(currentTime)
        assertFalse(selector.canAttemptInit(currentTime))
        selector.resetCooldown()
        assertTrue("Reset cooldown should allow immediate attempt", selector.canAttemptInit(currentTime))
    }

    @Test
    fun testResolveLangAvailability_helper() {
        // zh-CN available (>= 0)
        assertEquals(
            LangAvailability.AVAILABLE_ZH_CN,
            TtsEngineSelector.resolveLangAvailability(zhCnCode = 0, zhCode = 0)
        )
        assertEquals(
            LangAvailability.AVAILABLE_ZH_CN,
            TtsEngineSelector.resolveLangAvailability(zhCnCode = 1, zhCode = -1)
        )

        // zh available when zh-CN is not
        assertEquals(
            LangAvailability.AVAILABLE_ZH,
            TtsEngineSelector.resolveLangAvailability(zhCnCode = -1, zhCode = 0)
        )
        assertEquals(
            LangAvailability.AVAILABLE_ZH,
            TtsEngineSelector.resolveLangAvailability(zhCnCode = -2, zhCode = 2)
        )

        // Missing data (-1)
        assertEquals(
            LangAvailability.MISSING_DATA,
            TtsEngineSelector.resolveLangAvailability(zhCnCode = -1, zhCode = -2)
        )

        // Not supported
        assertEquals(
            LangAvailability.NOT_SUPPORTED,
            TtsEngineSelector.resolveLangAvailability(zhCnCode = -2, zhCode = -2)
        )
    }
}
