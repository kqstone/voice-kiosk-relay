package com.voicekiosk.app.tts

/**
 * Public status of Text-to-Speech subsystem.
 */
enum class TtsStatus {
    READY,
    NO_ENGINE,
    INIT_FAILED,
    LANG_MISSING
}

/**
 * Representation of an installed TTS engine without android.* dependencies.
 */
data class TtsEngineInfo(
    val packageName: String,
    val label: String = packageName
)

/**
 * Language availability outcome for Chinese language support.
 */
enum class LangAvailability {
    AVAILABLE_ZH_CN,
    AVAILABLE_ZH,
    MISSING_DATA,
    NOT_SUPPORTED
}

/**
 * Result of an engine initialization attempt.
 * [initCode] 0 represents TextToSpeech.SUCCESS.
 */
data class EngineInitResult(
    val initCode: Int,
    val langAvailability: LangAvailability
)

/**
 * Outcome of candidate engine selection evaluation.
 */
data class SelectionOutcome(
    val status: TtsStatus,
    val selectedEngine: String?,
    val selectedLocale: String? = null
)

/**
 * Pure Kotlin decision maker for Text-To-Speech engine selection and retry cooldown.
 * Completely free of android.* imports for local JVM unit testing.
 *
 * Selection sequence:
 * 1. User selected engine in AppConfig (if valid/available)
 * 2. System default engine
 * 3. First engine in available engines supporting zh-CN (or zh fallback)
 */
class TtsEngineSelector(
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val DEFAULT_COOLDOWN_MS = 10_000L
        const val SUCCESS_CODE = 0

        /**
         * Pure helper to resolve [LangAvailability] from standard TTS language codes
         * (0..2 available, -1 missing data, -2 not supported).
         */
        fun resolveLangAvailability(zhCnCode: Int, zhCode: Int): LangAvailability {
            return when {
                zhCnCode >= 0 -> LangAvailability.AVAILABLE_ZH_CN
                zhCode >= 0 -> LangAvailability.AVAILABLE_ZH
                zhCnCode == -1 || zhCode == -1 -> LangAvailability.MISSING_DATA
                else -> LangAvailability.NOT_SUPPORTED
            }
        }
    }

    var status: TtsStatus = TtsStatus.NO_ENGINE
        private set

    var selectedEngine: String? = null
        private set

    var selectedLocale: String? = null
        private set

    var lastAttemptTimeMs: Long = 0L
        private set

    /**
     * Checks whether lazy retry reinitialization is permitted under the cooldown window.
     */
    fun canAttemptInit(nowMs: Long = timeProvider()): Boolean {
        if (status == TtsStatus.READY) return false
        if (lastAttemptTimeMs == 0L) return true
        return (nowMs - lastAttemptTimeMs) >= cooldownMs
    }

    fun recordAttempt(nowMs: Long = timeProvider()) {
        lastAttemptTimeMs = nowMs
    }

    fun resetCooldown() {
        lastAttemptTimeMs = 0L
    }

    fun updateStatus(newStatus: TtsStatus, engine: String?, locale: String? = null) {
        status = newStatus
        selectedEngine = engine
        selectedLocale = locale
    }

    /**
     * Determines candidate engines to attempt in order:
     * 1. User preferred engine (if specified and present in available engines)
     * 2. System default engine (if specified and present in available engines, deduplicated)
     * 3. Remaining available engines in order
     */
    fun buildCandidatePlan(
        preferredEngine: String?,
        defaultEngine: String?,
        availableEngines: List<TtsEngineInfo>
    ): List<String> {
        if (availableEngines.isEmpty()) return emptyList()

        val availablePkgs = availableEngines.map { it.packageName }.toSet()
        val result = mutableListOf<String>()

        val preferred = preferredEngine?.trim().orEmpty()
        if (preferred.isNotEmpty() && availablePkgs.contains(preferred)) {
            result.add(preferred)
        }

        val defaultPkg = defaultEngine?.trim().orEmpty()
        if (defaultPkg.isNotEmpty() && availablePkgs.contains(defaultPkg) && !result.contains(defaultPkg)) {
            result.add(defaultPkg)
        }

        for (engine in availableEngines) {
            if (!result.contains(engine.packageName)) {
                result.add(engine.packageName)
            }
        }

        return result
    }

    /**
     * Evaluates candidates sequentially according to the initialization plan:
     * User selected -> System default -> Remaining available engines.
     * Stops on the first candidate that initializes successfully and supports zh-CN or zh.
     */
    fun evaluate(
        preferredEngine: String?,
        defaultEngine: String?,
        availableEngines: List<TtsEngineInfo>,
        tryInit: (packageName: String) -> EngineInitResult,
        onLog: ((message: String) -> Unit)? = null
    ): SelectionOutcome {
        if (availableEngines.isEmpty()) {
            status = TtsStatus.NO_ENGINE
            selectedEngine = null
            selectedLocale = null
            onLog?.invoke("No TTS engines installed")
            return SelectionOutcome(TtsStatus.NO_ENGINE, null)
        }

        val candidates = buildCandidatePlan(preferredEngine, defaultEngine, availableEngines)
        var anyEngineInited = false

        for (pkg in candidates) {
            onLog?.invoke("Attempting TTS engine: $pkg")
            val initRes = tryInit(pkg)
            if (initRes.initCode != SUCCESS_CODE) {
                onLog?.invoke("TTS engine $pkg onInit failed with code: ${initRes.initCode}")
                continue
            }

            anyEngineInited = true
            when (initRes.langAvailability) {
                LangAvailability.AVAILABLE_ZH_CN -> {
                    status = TtsStatus.READY
                    selectedEngine = pkg
                    selectedLocale = "zh-CN"
                    onLog?.invoke("TTS engine $pkg ready with zh-CN")
                    return SelectionOutcome(TtsStatus.READY, pkg, "zh-CN")
                }
                LangAvailability.AVAILABLE_ZH -> {
                    status = TtsStatus.READY
                    selectedEngine = pkg
                    selectedLocale = "zh"
                    onLog?.invoke("TTS engine $pkg ready with zh fallback")
                    return SelectionOutcome(TtsStatus.READY, pkg, "zh")
                }
                LangAvailability.MISSING_DATA, LangAvailability.NOT_SUPPORTED -> {
                    onLog?.invoke("TTS engine $pkg missing Chinese language support (${initRes.langAvailability})")
                }
            }
        }

        selectedEngine = null
        selectedLocale = null
        status = if (anyEngineInited) TtsStatus.LANG_MISSING else TtsStatus.INIT_FAILED
        onLog?.invoke("TTS candidate evaluation exhausted, final status: $status")
        return SelectionOutcome(status, null)
    }
}
