package com.voicekiosk.app.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.WakeWordEventBus
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Text-to-Speech manager for Voice Kiosk:
 * - Engine sequence: User selected (AppConfig) -> System default -> Available engines with zh-CN/zh support
 * - Diagnostics tag: VKTts
 * - Diagnostic logging: engine list, default engine, selected engine, onInit status code,
 *   zh-CN availability, speak return values, onError errorCodes.
 * - 3s onStart diagnosis warning in speakBlocking.
 * - Lazy retry with cooldown on speak when not ready.
 * - Audio attributes: USAGE_ASSISTANT + CONTENT_TYPE_SPEECH.
 */
class TtsManager(private val context: Context) {

    companion object {
        const val TAG = "VKTts"

        @Volatile
        private var instance: TtsManager? = null

        fun getInstance(context: Context): TtsManager {
            return instance ?: synchronized(this) {
                instance ?: TtsManager(context.applicationContext).also { instance = it }
            }
        }
    }

    val selector = TtsEngineSelector()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var activeEnginePackage: String? = null

    @Volatile
    private var isInitializing = false

    @Volatile
    private var initLatch: CountDownLatch? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isReady(): Boolean = selector.status == TtsStatus.READY && tts != null

    fun getStatus(): TtsStatus = selector.status

    fun getActiveEngine(): String? = activeEnginePackage

    /**
     * Query all installed TTS engines via TTS_SERVICE declaration contract.
     */
    fun queryAvailableEngines(ctx: Context = context): List<TtsEngineInfo> {
        val pm = ctx.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val resolveInfos = pm.queryIntentServices(intent, 0)
        return resolveInfos.map {
            val pkg = it.serviceInfo.packageName
            val label = it.loadLabel(pm)?.toString() ?: pkg
            TtsEngineInfo(pkg, label)
        }
    }

    /**
     * Query system default TTS engine package.
     */
    fun queryDefaultEngine(ctx: Context = context): String? {
        val fromTts = try {
            tts?.defaultEngine
        } catch (_: Exception) {
            null
        }
        if (!fromTts.isNullOrBlank()) return fromTts

        return try {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Initialize TextToSpeech engine candidate plan.
     */
    @Synchronized
    fun init() {
        if (selector.status == TtsStatus.READY && tts != null) {
            return
        }
        if (isInitializing) {
            return
        }
        isInitializing = true
        selector.recordAttempt()
        initLatch = CountDownLatch(1)

        val engines = queryAvailableEngines(context)
        Log.i(TAG, "Available engines (${engines.size}): ${engines.joinToString { "${it.label} (${it.packageName})" }}")

        if (engines.isEmpty()) {
            isInitializing = false
            selector.updateStatus(TtsStatus.NO_ENGINE, null)
            Log.w(TAG, "No TTS engines discovered on device")
            WakeWordEventBus.emitTtsStatus(TtsStatus.NO_ENGINE.name, null)
            initLatch?.countDown()
            return
        }

        val defaultEngine = queryDefaultEngine(context)
        Log.i(TAG, "Default engine: $defaultEngine")

        val preferredEngine = AppConfig.getPreferredTtsEngine(context)
        Log.i(TAG, "User preferred engine: $preferredEngine")

        val candidatePlan = selector.buildCandidatePlan(preferredEngine, defaultEngine, engines)
        Log.i(TAG, "TTS evaluation plan: $candidatePlan")

        tryCandidate(candidatePlan, 0, anyInitedSoFar = false)
    }

    private fun tryCandidate(candidates: List<String>, index: Int, anyInitedSoFar: Boolean) {
        if (index >= candidates.size) {
            // All candidates exhausted
            synchronized(this) {
                isInitializing = false
                tts = null
                activeEnginePackage = null
                val finalStatus = if (anyInitedSoFar) TtsStatus.LANG_MISSING else TtsStatus.INIT_FAILED
                selector.updateStatus(finalStatus, null)
                Log.w(TAG, "All TTS candidates failed, final status: $finalStatus")
                WakeWordEventBus.emitTtsStatus(finalStatus.name, null)
                initLatch?.countDown()
            }
            return
        }

        val candidatePkg = candidates[index]
        Log.i(TAG, "Attempting TTS candidate [$index/${candidates.size}]: $candidatePkg")

        // Must initialize TextToSpeech on main thread
        mainHandler.post {
            var candidateTts: TextToSpeech? = null
            try {
                candidateTts = TextToSpeech(context, { status ->
                    handleOnInit(candidateTts, candidatePkg, status, candidates, index, anyInitedSoFar)
                }, candidatePkg)
            } catch (e: Exception) {
                Log.e(TAG, "Exception instantiating TextToSpeech for engine $candidatePkg", e)
                tryCandidate(candidates, index + 1, anyInitedSoFar)
            }
        }
    }

    private fun handleOnInit(
        engine: TextToSpeech?,
        candidatePkg: String,
        status: Int,
        candidates: List<String>,
        index: Int,
        anyInitedSoFar: Boolean
    ) {
        Log.i(TAG, "onInit status: $status for engine: $candidatePkg")

        if (status != TextToSpeech.SUCCESS || engine == null) {
            Log.w(TAG, "onInit failed for engine $candidatePkg (status=$status), trying next candidate")
            try {
                engine?.shutdown()
            } catch (_: Exception) {}
            tryCandidate(candidates, index + 1, anyInitedSoFar)
            return
        }

        val nowAnyInited = true

        // 1. First check zh-CN
        val localeCn = Locale.CHINA
        val cnRes = try {
            engine.isLanguageAvailable(localeCn)
        } catch (e: Exception) {
            Log.w(TAG, "Error checking isLanguageAvailable(zh-CN)", e)
            -2
        }

        var selectedLocale: Locale? = null
        if (cnRes >= TextToSpeech.LANG_AVAILABLE) {
            val setRes = engine.setLanguage(localeCn)
            if (setRes >= TextToSpeech.LANG_AVAILABLE) {
                selectedLocale = localeCn
            }
        }

        // 2. If zh-CN not available, try generic zh
        var zhRes = -999
        if (selectedLocale == null) {
            val localeZh = Locale("zh")
            zhRes = try {
                engine.isLanguageAvailable(localeZh)
            } catch (e: Exception) {
                Log.w(TAG, "Error checking isLanguageAvailable(zh)", e)
                -2
            }
            if (zhRes >= TextToSpeech.LANG_AVAILABLE) {
                val setRes = engine.setLanguage(localeZh)
                if (setRes >= TextToSpeech.LANG_AVAILABLE) {
                    selectedLocale = localeZh
                }
            }
        }

        Log.i(TAG, "zh-CN availability: $cnRes (zh availability: $zhRes) for engine: $candidatePkg")

        if (selectedLocale != null) {
            // Apply AudioAttributes (USAGE_ASSISTANT + CONTENT_TYPE_SPEECH)
            try {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                engine.setAudioAttributes(audioAttributes)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply setAudioAttributes to engine $candidatePkg", e)
            }

            synchronized(this) {
                try {
                    tts?.shutdown()
                } catch (_: Exception) {}

                tts = engine
                activeEnginePackage = candidatePkg
                isInitializing = false
                selector.updateStatus(TtsStatus.READY, candidatePkg, selectedLocale.toLanguageTag())
            }

            Log.i(TAG, "Selected engine: $candidatePkg (locale=${selectedLocale.toLanguageTag()})")
            WakeWordEventBus.emitTtsStatus(TtsStatus.READY.name, candidatePkg)
            initLatch?.countDown()
        } else {
            Log.w(TAG, "Engine $candidatePkg missing Chinese voice data, trying next candidate")
            try {
                engine.shutdown()
            } catch (_: Exception) {}
            tryCandidate(candidates, index + 1, nowAnyInited)
        }
    }

    /**
     * Lazy re-initialization triggered on speak if not ready, subject to cooldown.
     */
    private fun ensureReadyLazy() {
        if (isReady()) return
        val now = System.currentTimeMillis()
        if (selector.canAttemptInit(now) && !isInitializing) {
            Log.i(TAG, "TTS not ready on speak, attempting lazy reinitialization")
            init()
            try {
                initLatch?.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Blocking speak with utterance listener and 3s onStart diagnostic warning.
     * Waits up to 20s total for playback to complete.
     */
    fun speakBlocking(text: String): Boolean {
        if (text.isBlank()) return false
        ensureReadyLazy()

        val engine = tts
        if (!isReady() || engine == null) {
            Log.w(TAG, "TTS not ready (status=${selector.status}), skipping speech playback")
            return false
        }

        val utteranceId = "vk_${UUID.randomUUID()}"
        val started = AtomicBoolean(false)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(1)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                started.set(true)
                startLatch.countDown()
                Log.i(TAG, "TTS started speaking ($id)")
            }

            override fun onDone(id: String?) {
                doneLatch.countDown()
                Log.i(TAG, "TTS finished speaking ($id)")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                doneLatch.countDown()
                Log.w(TAG, "TTS error ($id)")
            }

            override fun onError(id: String?, errorCode: Int) {
                doneLatch.countDown()
                Log.e(TAG, "TTS onError ($id, errorCode=$errorCode)")
            }
        })

        val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.i(TAG, "speak() returned: $speakResult for utteranceId: $utteranceId")
        if (speakResult != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak() returned error code: $speakResult")
            return false
        }

        // 3-second diagnosis: warning logged if no onStart within 3 seconds
        val startedInTime = startLatch.await(3, TimeUnit.SECONDS)
        if (!startedInTime && !started.get()) {
            Log.w(TAG, "TTS utterance ($utteranceId) did not receive onStart within 3 seconds")
        }

        // Wait remaining time up to 17 seconds
        try {
            doneLatch.await(17, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return true
    }

    /**
     * Non-blocking TTS playback for host callbacks, UI test speak, and timer alerts.
     */
    fun speakNonBlocking(text: String, utteranceId: String = "vk_host_${UUID.randomUUID()}"): Boolean {
        if (text.isBlank()) return false
        ensureReadyLazy()

        val engine = tts
        if (!isReady() || engine == null) {
            Log.w(TAG, "TTS not ready (status=${selector.status}), skipping speakNonBlocking")
            return false
        }

        val res = engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        Log.i(TAG, "speakNonBlocking() returned: $res for utteranceId: $utteranceId")
        return res == TextToSpeech.SUCCESS
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping TTS", e)
        }
    }

    @Synchronized
    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TextToSpeech", e)
        } finally {
            tts = null
            activeEnginePackage = null
            isInitializing = false
            selector.updateStatus(TtsStatus.NO_ENGINE, null)
        }
    }
}
