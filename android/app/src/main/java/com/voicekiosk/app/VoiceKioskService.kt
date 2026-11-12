package com.voicekiosk.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.voicekiosk.app.bridge.BridgeHealth
import com.voicekiosk.app.bridge.BridgeHealthClient
import com.voicekiosk.app.bridge.ErrorEnvelope
import com.voicekiosk.app.conversation.AnswerQueue
import com.voicekiosk.app.conversation.CloudReply
import com.voicekiosk.app.conversation.ConversationContext
import com.voicekiosk.app.conversation.ConversationPoller
import com.voicekiosk.app.conversation.ConversationRecord
import com.voicekiosk.app.conversation.ConversationWire
import com.voicekiosk.app.conversation.OpenApproval
import com.voicekiosk.app.conversation.OpenQuestion
import com.voicekiosk.app.conversation.QuestionAnswerMatcher
import com.voicekiosk.app.conversation.QuestionMatch
import com.voicekiosk.app.ondevice.route.RouterModelHost
import com.voicekiosk.app.ondevice.route.RuleRouter
import com.voicekiosk.app.ondevice.route.TurnOrchestrator
import com.voicekiosk.app.ondevice.tools.AndroidLocalPlatform
import com.voicekiosk.app.ondevice.tools.LocalToolExecutor
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Foreground Service running the full P2 voice chain on a single 16kHz mono PCM16 AudioRecord stream:
 * State machine:
 *   KWS_LISTENING -> (on wake: beep + banner) -> Conversation session loop:
 *     [ASR_LISTENING -> SPEAKING] x N (no turn limit, no re-wake, no beep between follow-up turns)
 *     -> back to KWS_LISTENING on silence timeout (4s on initial turn, SESSION_SILENCE_TIMEOUT_MS = 8s on follow-up
 *        with a short spoken closing prompt) or offline/chat error.
 *
 * - KWS: Sherpa-ONNX KeywordSpotter (bundled in APK assets).
 * - ASR: Sherpa-ONNX OnlineRecognizer with streaming Zipformer bilingual model, downloaded on first launch
 *        to app private filesDir with SHA256 verification and dashboard progress reporting.
 * - Endpointing: Energy-based VAD (~1.2s silence below threshold after speech) or 8s max duration.
 * - Bridge Chat: POST JSON to /api/v1/chat (HttpURLConnection, 10s timeout, no redirects).
 * - TTS: Android system TextToSpeech (Locale.CHINA / zh-CN, device default TTS engine).
 * - Offline / Error resilience: Shows clear offline notice on dashboard and never speaks a fake answer.
 */
class VoiceKioskService : Service(), TextToSpeech.OnInitListener {

    enum class PipelineState {
        KWS_LISTENING,
        ASR_LISTENING,
        SPEAKING,
        WAITING
    }

    private enum class TurnOutcome {
        ANSWERED,
        SILENCE,
        ERROR,
        COMPLETED
    }

    companion object {
        private const val TAG = "VoiceKioskService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voice_kiosk_kws_channel"

        const val SESSION_SILENCE_TIMEOUT_MS = AppConfig.SESSION_SILENCE_TIMEOUT_MS
        private const val SESSION_SILENCE_PROMPT = "没有听到问题，我先等着了"

        const val ACTION_START = "com.voicekiosk.app.action.START"
        const val ACTION_STOP = "com.voicekiosk.app.action.STOP"
        const val ACTION_META_HANDSHAKE = "com.voicekiosk.app.action.META_HANDSHAKE"
        const val HOST_SPEAK_MAX_CHARS = 200

        @Volatile
        private var hostSpeakHandler: ((String) -> Unit)? = null

        private val openQuestionRef = AtomicReference<OpenQuestion?>(null)
        private val openApprovalRef = AtomicReference<OpenApproval?>(null)
        val answerQueue = AnswerQueue()

        fun speakFromHost(text: String) {
            val capped = text.trim().take(HOST_SPEAK_MAX_CHARS)
            if (capped.isEmpty()) return
            hostSpeakHandler?.invoke(capped)
        }

        fun setOpenQuestion(q: OpenQuestion?) {
            openQuestionRef.set(q)
        }

        fun currentOpenQuestion(): OpenQuestion? = openQuestionRef.get()

        fun setOpenApproval(a: OpenApproval?) {
            openApprovalRef.set(a)
        }

        fun currentOpenApproval(): OpenApproval? = openApprovalRef.get()

        fun applyQuestionJson(raw: String?) {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s == "{}" || s == "null") {
                openQuestionRef.set(null)
                return
            }
            try {
                openQuestionRef.set(OpenQuestion.parse(JSONObject(s)))
            } catch (_: Exception) {
                // malformed JSON from a page is ignored
            }
        }

        fun applyApprovalJson(raw: String?) {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty() || s == "{}" || s == "null") {
                openApprovalRef.set(null)
                return
            }
            try {
                openApprovalRef.set(OpenApproval.parse(JSONObject(s)))
            } catch (_: Exception) {
                // malformed JSON from a page is ignored
            }
        }

        fun requestMetaHandshake(context: Context) {
            val intent = Intent(context, VoiceKioskService::class.java).apply {
                action = ACTION_META_HANDSHAKE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, VoiceKioskService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceKioskService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restart(context: Context) {
            stop(context)
            val appContext = context.applicationContext
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(appContext)
            }, 500L)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var toneGenerator: ToneGenerator? = null

    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false

    private var kws: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null

    private var asrRecognizer: OnlineRecognizer? = null
    @Volatile
    private var isAsrReady = false
    @Volatile
    private var isAsrDownloading = false

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var isRecording = false

    @Volatile
    private var pipelineState: PipelineState = PipelineState.KWS_LISTENING

    // S1 on-device routing. All null when the setting is off: turns then go straight to
    // the bridge through the unchanged path in chatAndSpeakTurn.
    @Volatile
    private var orchestrator: TurnOrchestrator? = null
    @Volatile private var memoryManager: com.voicekiosk.app.memory.MemoryManager? = null
    private var routerModel: RouterModelHost? = null
    private var localPlatform: AndroidLocalPlatform? = null
    private var routeDebug = false

    /** Whether the bridge passed the GET /health check. */
    @Volatile
    private var bridgeReachable = true

    /** Last cloud turn speak flag (honoured for TTS). */
    @Volatile
    private var lastCloudSpeak = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Initializing VoiceKioskService...")

        acquireLocks()
        initToneGenerator()
        initTts()
        startForegroundWithNotification()
        hostSpeakHandler = { text -> speakNonBlocking(text) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping service via ACTION_STOP")
                stopRecording()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_META_HANDSHAKE -> {
                checkBridgeHealth()
                if (!isRecording) {
                    startVoicePipeline()
                }
            }
            else -> {
                checkBridgeHealth()
                if (!isRecording) {
                    startVoicePipeline()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroying VoiceKioskService...")
        hostSpeakHandler = null
        stopRecording()
        teardownOnDeviceRouting()
        releaseTts()
        releaseLocks()
        releaseToneGenerator()
        WakeWordEventBus.emitStatus(false, "服务已停止")
        super.onDestroy()
    }

    // --- TextToSpeech Initialization ---

    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val res = engine.setLanguage(Locale.CHINA)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                val fallbackRes = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
                if (fallbackRes == TextToSpeech.LANG_MISSING_DATA || fallbackRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "zh-CN locale not supported by system TTS engine (code=$res)")
                } else {
                    isTtsReady = true
                    Log.i(TAG, "TTS initialized with SIMPLIFIED_CHINESE")
                }
            } else {
                isTtsReady = true
                Log.i(TAG, "TTS initialized with zh-CN")
            }
        } else {
            Log.e(TAG, "TextToSpeech onInit failed with status: $status")
        }
    }

    private fun releaseTts() {
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
            isTtsReady = false
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TextToSpeech", e)
        }
    }

    private fun speakBlocking(text: String) {
        val engine = tts
        if (!isTtsReady || engine == null || text.isBlank()) {
            Log.w(TAG, "TTS not ready or empty text, skipping speech playback")
            return
        }

        val utteranceId = "vk_${UUID.randomUUID()}"
        val latch = CountDownLatch(1)

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                Log.i(TAG, "TTS started speaking ($id)")
            }

            override fun onDone(id: String?) {
                Log.i(TAG, "TTS finished speaking ($id)")
                latch.countDown()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                Log.w(TAG, "TTS error ($id)")
                latch.countDown()
            }

            override fun onError(id: String?, errorCode: Int) {
                Log.w(TAG, "TTS error ($id, code=$errorCode)")
                latch.countDown()
            }
        })

        val speakResult = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (speakResult == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned ERROR")
            return
        }

        // Wait up to 20s for utterance playback to finish before re-arming KWS
        try {
            latch.await(20, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Non-blocking TTS for the WebView VoiceKioskHost.speak hook. */
    private fun speakNonBlocking(text: String) {
        val engine = tts
        if (!isTtsReady || engine == null || text.isBlank()) return
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "vk_host_${UUID.randomUUID()}")
    }

    // --- Foreground Service & Locks ---

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val wakeWord = AppConfig.getWakeWord(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Kiosk")
            .setContentText("正在监听唤醒词：$wakeWord")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Kiosk 语音监听服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持前台麦克风录音与 Sherpa-ONNX 唤醒与识别"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceKiosk::WakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WakeLock", e)
        }

        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VoiceKiosk::WiFiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WiFiLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WiFiLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock", e)
        }

        try {
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WiFiLock", e)
        }
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init ToneGenerator", e)
        }
    }

    private fun releaseToneGenerator() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing ToneGenerator", e)
        }
    }

    private fun playPromptBeep() {
        try {
            if (toneGenerator == null) {
                initToneGenerator()
            }
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
        } catch (e: Exception) {
            Log.e(TAG, "Error playing prompt beep", e)
        }
    }

    // --- KWS + ASR + AudioRecord Initialization ---

    private fun startVoicePipeline() {
        try {
            initKwsModel()
            initAudioRecord()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize KWS engine or AudioRecord", e)
            WakeWordEventBus.emitStatus(false, "初始化失败: ${e.message}")
            return
        }

        // Start background check/download of ASR model on first launch
        ensureAsrModelInBackground()

        setupOnDeviceRouting()

        pipelineState = PipelineState.KWS_LISTENING
        isRecording = true
        audioRecord?.startRecording()
        WakeWordEventBus.emitStatus(true, "正在监听：${AppConfig.getWakeWord(this)}")
        WakeWordEventBus.emitStateChanged(pipelineState.name)

        recordingThread = thread(name = "VoiceKiosk-AudioThread", isDaemon = true) {
            processAudioLoop()
        }
        Log.i(TAG, "Voice pipeline audio thread started")
    }

    private fun ensureAsrModelInBackground() {
        if (isAsrReady || isAsrDownloading) return
        isAsrDownloading = true
        thread(name = "VoiceKiosk-AsrModelInit", isDaemon = true) {
            try {
                val downloaded = AsrModelDownloader.ensureModelDownloaded(applicationContext)
                if (downloaded) {
                    initAsrRecognizer()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing ASR model", e)
                WakeWordEventBus.emitAsrDownloadProgress(-1, "ASR 模型初始化失败: ${e.message}")
            } finally {
                isAsrDownloading = false
            }
        }
    }

    @Synchronized
    private fun initAsrRecognizer() {
        if (isAsrReady && asrRecognizer != null) return
        val modelDir = AsrModelDownloader.getModelDir(applicationContext)
        val encoderFile = File(modelDir, AppConfig.ASR_ENCODER_FILENAME)
        val decoderFile = File(modelDir, AppConfig.ASR_DECODER_FILENAME)
        val joinerFile = File(modelDir, AppConfig.ASR_JOINER_FILENAME)
        val tokensFile = File(modelDir, AppConfig.ASR_TOKENS_FILENAME)

        if (!encoderFile.exists() || !decoderFile.exists() || !joinerFile.exists() || !tokensFile.exists()) {
            Log.w(TAG, "ASR model files not found in ${modelDir.absolutePath}")
            return
        }

        Log.i(TAG, "Initializing Sherpa-ONNX OnlineRecognizer from ${modelDir.absolutePath}")
        val featConfig = FeatureConfig(
            sampleRate = AppConfig.AUDIO_SAMPLE_RATE,
            featureDim = 80
        )
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = encoderFile.absolutePath,
                decoder = decoderFile.absolutePath,
                joiner = joinerFile.absolutePath
            ),
            tokens = tokensFile.absolutePath,
            numThreads = 2,
            debug = false,
            provider = "cpu",
            modelType = AppConfig.ASR_MODEL_TYPE
        )
        val endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0.0f),
            rule2 = EndpointRule(true, 1.2f, 0.0f),
            rule3 = EndpointRule(false, 0.0f, 8.0f)
        )
        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            endpointConfig = endpointConfig,
            enableEndpoint = true,
            decodingMethod = "greedy_search",
            maxActivePaths = 4
        )

        asrRecognizer = OnlineRecognizer(
            assetManager = null, // Load from filesystem paths in app private storage
            config = recognizerConfig
        )
        isAsrReady = true
        Log.i(TAG, "Sherpa-ONNX OnlineRecognizer initialized and ready")
    }

    private fun initKwsModel() {
        val wakeWord = AppConfig.getWakeWord(this)
        val threshold = AppConfig.getWakeThreshold(this)
        Log.i(TAG, "Loading Sherpa-ONNX KWS model with wakeWord='$wakeWord', threshold=$threshold")

        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = AppConfig.ENCODER_FILE,
                decoder = AppConfig.DECODER_FILE,
                joiner = AppConfig.JOINER_FILE
            ),
            tokens = AppConfig.TOKENS_FILE,
            modelType = "zipformer2"
        )

        val featConfig = FeatureConfig(
            sampleRate = AppConfig.AUDIO_SAMPLE_RATE,
            featureDim = 80
        )

        val spotterConfig = KeywordSpotterConfig(
            featConfig = featConfig,
            modelConfig = modelConfig,
            keywordsFile = AppConfig.KEYWORDS_FILE,
            keywordsThreshold = threshold,
            keywordsScore = 1.5f
        )

        kws = KeywordSpotter(
            assetManager = applicationContext.assets,
            config = spotterConfig
        )

        val customKeywords = AppConfig.getKeywordsDefinition(wakeWord, threshold)
        Log.i(TAG, "Creating KWS stream with keyword definition: $customKeywords")
        kwsStream = kws?.createStream(customKeywords)
        if (kwsStream == null || kwsStream?.ptr == 0L) {
            Log.w(TAG, "Fallback to default keywordsFile stream")
            kwsStream = kws?.createStream()
        }
        requireNotNull(kwsStream) { "Failed to create Sherpa-ONNX KWS stream" }
    }

    private fun initAudioRecord() {
        val minBufferSize = AudioRecord.getMinBufferSize(
            AppConfig.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, AppConfig.AUDIO_SAMPLE_RATE / 5 * 2)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            AppConfig.AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord failed to initialize (state != STATE_INITIALIZED)")
        }
    }

    // --- State Machine & Continuous Audio Processing Loop ---

    private fun processAudioLoop() {
        val sampleRate = AppConfig.AUDIO_SAMPLE_RATE
        val chunkSize = sampleRate / 10 // 100ms interval (1600 samples)
        val shortBuffer = ShortArray(chunkSize)

        Log.i(TAG, "Entering continuous voice pipeline audio loop...")

        while (isRecording) {
            val record = audioRecord ?: break
            val read = record.read(shortBuffer, 0, shortBuffer.size)
            if (read <= 0) {
                if (read < 0) {
                    Log.w(TAG, "AudioRecord read returned error code: $read")
                }
                continue
            }

            val floatSamples = FloatArray(read) { shortBuffer[it] / 32768.0f }
            val currentStream = kwsStream ?: break
            val currentKws = kws ?: break

            // State: KWS_LISTENING
            currentStream.acceptWaveform(floatSamples, sampleRate)
            while (currentKws.isReady(currentStream)) {
                currentKws.decode(currentStream)
                val result = currentKws.getResult(currentStream)
                if (result.keyword.isNotBlank()) {
                    val detectedKeyword = result.keyword
                    Log.i(TAG, "Wake word triggered: '$detectedKeyword'")
                    currentKws.reset(currentStream)

                    // 1. On wake: beep + banner
                    playPromptBeep()
                    WakeWordEventBus.emitWakeWord(detectedKeyword)

                    // Brief pause (180ms) so the beep itself is not captured into ASR
                    drainAudioSamples(record, shortBuffer, 180L)

                    // 2. Enter continuous multi-turn conversation session
                    routerModel?.cancelExclusive()
                    val sessionStartSeq = orchestrator?.context?.turns()?.lastOrNull()?.seq ?: 0L
                    runConversationSession(record, shortBuffer, sampleRate)
                    // S5: memory extraction runs after the session, in the background
                    orchestrator?.let { o ->
                        memoryManager?.onSessionEnded(o.context.turns().filter { it.seq > sessionStartSeq }.map { it.userText })
                    }

                    // 3. Re-arm KWS_LISTENING
                    currentKws.reset(currentStream)
                    pipelineState = PipelineState.KWS_LISTENING
                    WakeWordEventBus.emitStateChanged(pipelineState.name)
                    Log.i(TAG, "Re-armed KWS_LISTENING")
                    break
                }
            }
        }

        Log.i(TAG, "Exited voice pipeline audio loop")
    }

    private fun drainAudioSamples(record: AudioRecord, buffer: ShortArray, durationMs: Long) {
        val end = System.currentTimeMillis() + durationMs
        while (isRecording && System.currentTimeMillis() < end) {
            record.read(buffer, 0, buffer.size)
        }
    }

    private fun runConversationSession(
        record: AudioRecord,
        shortBuffer: ShortArray,
        sampleRate: Int
    ) {
        Log.i(TAG, "Starting multi-turn conversation session")
        var turnCount = 0
        while (isRecording) {
            val isFollowUp = turnCount > 0
            val noSpeechTimeoutMs = if (isFollowUp) {
                SESSION_SILENCE_TIMEOUT_MS
            } else {
                AppConfig.ASR_INITIAL_NO_SPEECH_TIMEOUT_MS
            }

            if (isFollowUp) {
                WakeWordEventBus.emitFollowUpListening(turnCount + 1)
            }

            val outcome = transitionToAsrAndChat(
                record = record,
                shortBuffer = shortBuffer,
                sampleRate = sampleRate,
                noSpeechTimeoutMs = noSpeechTimeoutMs,
                isFollowUp = isFollowUp
            )

            if (outcome != TurnOutcome.ANSWERED) {
                if (outcome == TurnOutcome.SILENCE && isFollowUp) {
                    pipelineState = PipelineState.SPEAKING
                    WakeWordEventBus.emitStateChanged(pipelineState.name)
                    speakBlocking(SESSION_SILENCE_PROMPT)
                    drainAudioSamples(record, shortBuffer, 150L)
                }
                Log.i(TAG, "Conversation session ended on turn ${turnCount + 1} with outcome=$outcome")
                return
            }

            turnCount++
        }
    }

    private fun transitionToAsrAndChat(
        record: AudioRecord,
        shortBuffer: ShortArray,
        sampleRate: Int,
        noSpeechTimeoutMs: Long,
        isFollowUp: Boolean
    ): TurnOutcome {
        pipelineState = PipelineState.ASR_LISTENING
        WakeWordEventBus.emitStateChanged(pipelineState.name)

        // Ensure ASR recognizer is ready (if download finished or needs retry)
        if (!isAsrReady) {
            if (AsrModelDownloader.isModelReady(applicationContext)) {
                try {
                    initAsrRecognizer()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to init ASR recognizer on wake", e)
                }
            } else {
                ensureAsrModelInBackground()
                WakeWordEventBus.emitChatOfflineError("语音识别模型正在下载中，请稍候再试")
                return TurnOutcome.ERROR
            }
        }

        val recognizer = asrRecognizer
        if (recognizer == null) {
            WakeWordEventBus.emitChatOfflineError("语音识别引擎未就绪")
            return TurnOutcome.ERROR
        }

        val recognizedText = listenSingleTurnAsr(
            recognizer = recognizer,
            record = record,
            shortBuffer = shortBuffer,
            sampleRate = sampleRate,
            noSpeechTimeoutMs = noSpeechTimeoutMs
        )

        Log.i(TAG, "ASR final result (isFollowUp=$isFollowUp): '$recognizedText'")
        if (recognizedText.isBlank()) {
            if (!isFollowUp) {
                WakeWordEventBus.emitAsrFinalResult("")
            }
            return TurnOutcome.SILENCE
        }

        // Emit recognized text to show '你说：<text>' on dashboard
        WakeWordEventBus.emitAsrFinalResult(recognizedText)

        // Transition to SPEAKING (Query Bridge conversation turn -> Speak reply via TTS)
        return chatAndSpeakTurn(record, shortBuffer, recognizedText)
    }

    private fun listenSingleTurnAsr(
        recognizer: OnlineRecognizer,
        record: AudioRecord,
        shortBuffer: ShortArray,
        sampleRate: Int,
        noSpeechTimeoutMs: Long
    ): String {
        val asrStream = recognizer.createStream()
        var recognizedText = ""
        try {
            val startTimeMs = System.currentTimeMillis()
            var lastVoiceActivityMs = startTimeMs
            var lastEnergyEmitMs = 0L
            var hasDetectedSpeech = false
            var lastPartial = ""

            Log.i(
                TAG,
                "ASR_LISTENING started (max=${AppConfig.ASR_MAX_DURATION_MS}ms, " +
                    "silence=${AppConfig.ASR_VAD_SILENCE_MS}ms, noSpeechTimeout=${noSpeechTimeoutMs}ms)"
            )

            while (isRecording) {
                val now = System.currentTimeMillis()
                val elapsed = now - startTimeMs
                if (elapsed >= AppConfig.ASR_MAX_DURATION_MS) {
                    Log.i(TAG, "ASR max duration (${AppConfig.ASR_MAX_DURATION_MS}ms) reached")
                    break
                }

                val read = record.read(shortBuffer, 0, shortBuffer.size)
                if (read <= 0) continue

                var sumSquares = 0.0
                val floatSamples = FloatArray(read) { i ->
                    val s = shortBuffer[i] / 32768.0f
                    sumSquares += s * s
                    s
                }
                val rms = sqrt(sumSquares / read).toFloat()

                if (now - lastEnergyEmitMs >= 80L) {
                    val normalizedEnergy = ((rms - 0.005f) / 0.12f).coerceIn(0f, 1f)
                    WakeWordEventBus.emitAudioEnergy(normalizedEnergy)
                    lastEnergyEmitMs = now
                }

                if (rms >= AppConfig.ASR_VAD_ENERGY_THRESHOLD) {
                    hasDetectedSpeech = true
                    lastVoiceActivityMs = System.currentTimeMillis()
                }

                asrStream.acceptWaveform(floatSamples, sampleRate)
                while (recognizer.isReady(asrStream)) {
                    recognizer.decode(asrStream)
                }

                val partial = recognizer.getResult(asrStream).text.trim()
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastPartial = partial
                    hasDetectedSpeech = true
                    lastVoiceActivityMs = System.currentTimeMillis()
                    WakeWordEventBus.emitAsrPartialResult(partial)
                }

                val silenceDuration = System.currentTimeMillis() - lastVoiceActivityMs
                // Endpoint when energy-based VAD detects >= 1.2s silence after speech, or sherpa endpoint rule fires
                if (hasDetectedSpeech && silenceDuration >= AppConfig.ASR_VAD_SILENCE_MS) {
                    Log.i(TAG, "ASR VAD silence endpoint triggered (${silenceDuration}ms below threshold)")
                    break
                }
                if (partial.isNotEmpty() && recognizer.isEndpoint(asrStream)) {
                    Log.i(TAG, "ASR Sherpa endpoint triggered")
                    break
                }
                // Stop if user stayed completely silent for noSpeechTimeoutMs (4s on initial wake, 8s on follow-up)
                if (!hasDetectedSpeech && elapsed >= noSpeechTimeoutMs) {
                    Log.i(TAG, "ASR no speech detected within ${noSpeechTimeoutMs}ms")
                    break
                }
            }

            asrStream.inputFinished()
            while (recognizer.isReady(asrStream)) {
                recognizer.decode(asrStream)
            }
            recognizedText = recognizer.getResult(asrStream).text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "Error during ASR recognition", e)
        } finally {
            WakeWordEventBus.emitAudioEnergy(0f)
            try {
                asrStream.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing ASR stream", e)
            }
        }
        return recognizedText
    }

    private fun chatAndSpeakTurn(
        record: AudioRecord,
        shortBuffer: ShortArray,
        recognizedText: String
    ): TurnOutcome {
        pipelineState = PipelineState.SPEAKING
        WakeWordEventBus.emitStateChanged(pipelineState.name)

        orchestrator?.let { return orchestratedTurn(it, record, shortBuffer, recognizedText) }

        tryLocalQuestionOrApproval(recognizedText)?.let { local ->
            WakeWordEventBus.emitChatReply(local)
            speakBlocking(local)
            drainAudioSamples(record, shortBuffer, 150L)
            return TurnOutcome.ANSWERED
        }

        val chatResult = postChat(com.voicekiosk.app.conversation.CloudRequest(recognizedText))
        return if (chatResult.isSuccess) {
            val cloud = chatResult.getOrNull()
            val reply = cloud?.reply.orEmpty().trim()
            if (reply.isNotEmpty()) {
                if (cloud?.route == "muse" && !cloud.conversationId.isNullOrEmpty()) {
                    return handleMuseWaitingTurn(
                        record = record,
                        shortBuffer = shortBuffer,
                        ackReply = reply,
                        conversationId = cloud.conversationId,
                        speakAck = cloud.speak
                    )
                }
                WakeWordEventBus.emitChatReply(reply)
                if (cloud?.speak != false) speakBlocking(reply)
                // Drain 150ms of residual speaker echo before next turn or KWS
                drainAudioSamples(record, shortBuffer, 150L)
                TurnOutcome.ANSWERED
            } else {
                WakeWordEventBus.emitChatOfflineError("服务未返回有效回答")
                TurnOutcome.ERROR
            }
        } else {
            val errorMsg = chatResult.exceptionOrNull()?.message ?: "网络连接失败，请检查网络或桥接服务"
            Log.w(TAG, "Bridge conversation failed: $errorMsg")
            // Offline notice on dashboard, NEVER speak a fake answer
            WakeWordEventBus.emitChatOfflineError(errorMsg)
            TurnOutcome.ERROR
        }
    }

    // --- S1 on-device routing (off by default) ---

    private fun setupOnDeviceRouting() {
        if (orchestrator != null) return
        if (!AppConfig.isOnDeviceRoutingEnabled(applicationContext)) {
            Log.i(TAG, "On-device routing off: all turns go to the bridge")
            return
        }
        try {
            routeDebug = AppConfig.isOnDeviceRouteDebugEnabled(applicationContext)
            val platform = AndroidLocalPlatform(
                applicationContext,
                preferredPlayer = { AppConfig.getOnDevicePlayerPackage(applicationContext) }
            ) { label, seconds -> announceTimer(label, seconds, 0) }
            localPlatform = platform
            val modelEnabled = AppConfig.isOnDeviceModelRoutingEnabled(applicationContext)
            var host: RouterModelHost? = null
            if (modelEnabled) {
                host = RouterModelHost(applicationContext)
                if (host.startLoading()) routerModel = host else Log.i(TAG, "L1 off: ${host.statusText}")
            }
            memoryManager = try { setupMemory(routerModel) } catch (e: Exception) { Log.e(TAG, "Memory setup failed", e); null }
            orchestrator = TurnOrchestrator(
                config = TurnOrchestrator.Config(
                    modelRoutingEnabled = modelEnabled,
                    chitchatEnabled = AppConfig.isOnDeviceChitchatEnabled(applicationContext),
                    speculativeCloud = AppConfig.isOnDeviceSpeculativeCloudEnabled(applicationContext)
                ),
                rules = RuleRouter(),
                classifier = routerModel?.classifier(),
                tools = LocalToolExecutor(
                    platform,
                    favoriteArtist = { memoryManager?.favoriteArtist() },
                    weather = if (AppConfig.isOnDeviceWeatherEnabled(applicationContext)) com.voicekiosk.app.ondevice.tools.OpenMeteoWeatherSource() else null,
                    defaultCity = {
                        val name = AppConfig.getWeatherCity(applicationContext)
                        com.voicekiosk.app.ondevice.tools.City(
                            name.removeSuffix("市"),
                            AppConfig.getWeatherLatitude(applicationContext).toDouble(),
                            AppConfig.getWeatherLongitude(applicationContext).toDouble()
                        )
                    }
                ),
                context = ConversationContext(),
                cloud = { req -> postChat(req) },
                memory = memoryManager,
                log = { Log.i(TAG, "route: $it") },
                openQuestion = { currentOpenQuestion() },
                openApproval = { currentOpenApproval() },
                postAnswer = { q, m -> postOpenAnswer(q, m) },
                onApprovalVoice = { ap, approve ->
                    WakeWordEventBus.emitRequestPin(ap.id, if (approve) "approve" else "reject")
                },
                answerQueue = answerQueue
            )
            Log.i(TAG, "On-device routing on (L1: ${routerModel?.statusText ?: host?.statusText ?: "off"})")
        } catch (e: Exception) {
            // Never break the voice pipeline: without an orchestrator turns go to the bridge.
            Log.e(TAG, "On-device routing setup failed; using cloud only", e)
            orchestrator = null
        }
    }

    /** S5: long-term memory extraction (needs on-device routing, the setting and a bridge token). */
    private fun setupMemory(host: RouterModelHost?): com.voicekiosk.app.memory.MemoryManager? {
        if (!AppConfig.isMemoryExtractionEnabled(applicationContext)) return null
        if (!AppConfig.hasBridgeAuthToken(applicationContext)) {
            Log.i(TAG, "Memory extraction off: no bridge token")
            return null
        }
        val m = com.voicekiosk.app.memory.MemoryManager(
            backend = com.voicekiosk.app.memory.HttpMemoryBackend(applicationContext),
            cache = com.voicekiosk.app.memory.LocalMemoryCache(
                com.voicekiosk.app.memory.KeystoreCodec.cacheFile(applicationContext), com.voicekiosk.app.memory.KeystoreCodec()
            ),
            model = {
                host?.takeIf { it.state == RouterModelHost.State.READY }?.let { h ->
                    { utts: List<String> ->
                        h.runExclusive { e, f -> com.voicekiosk.app.memory.ModelMemoryExtractor(e, f).extract(utts) }
                            ?: com.voicekiosk.app.memory.ModelMemoryExtractor.Outcome(emptyList(), "busy")
                    }
                }
            },
            canRunModel = { pipelineState == PipelineState.KWS_LISTENING && thermalBelowModerate() },
            log = { Log.i(TAG, it) }
        )
        m.refreshInBackground()
        Log.i(TAG, "Memory extraction on (model: ${host?.statusText ?: "off"})")
        return m
    }

    private fun thermalBelowModerate(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return true
        val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
        return pm.currentThermalStatus < android.os.PowerManager.THERMAL_STATUS_MODERATE
    }

    private fun teardownOnDeviceRouting() {
        memoryManager = null
        orchestrator = null
        localPlatform?.cancelTimers()
        localPlatform = null
        routerModel?.release()
        routerModel = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL && routerModel != null) {
            Log.w(TAG, "Memory pressure (level=$level): unloading the on-device model; rules keep working")
            routerModel?.release()
        }
    }

    private fun orchestratedTurn(
        orch: TurnOrchestrator,
        record: AudioRecord,
        shortBuffer: ShortArray,
        recognizedText: String
    ): TurnOutcome {
        val result = try {
            orch.handle(recognizedText)
        } catch (e: Exception) {
            Log.e(TAG, "Orchestrator failed; falling back to bridge", e)
            null
        }
        if (result == null) {
            // Same handling as the plain cloud path.
            val r = postChat(com.voicekiosk.app.conversation.CloudRequest(recognizedText))
            val cloud = r.getOrNull()
            val reply = cloud?.reply.orEmpty().trim()
            if (reply.isEmpty()) {
                WakeWordEventBus.emitChatOfflineError(r.exceptionOrNull()?.message ?: "服务未返回有效回答")
                return TurnOutcome.ERROR
            }
            if (cloud?.route == "muse" && !cloud.conversationId.isNullOrEmpty()) {
                return handleMuseWaitingTurn(
                    record = record,
                    shortBuffer = shortBuffer,
                    ackReply = reply,
                    conversationId = cloud.conversationId,
                    speakAck = cloud.speak
                )
            }
            WakeWordEventBus.emitChatReply(reply)
            if (cloud?.speak != false) speakBlocking(reply)
            drainAudioSamples(record, shortBuffer, 150L)
            return TurnOutcome.ANSWERED
        }
        val t = result.trace
        Log.i(
            TAG,
            "route source=${result.source} intent=${result.decision?.intent} slots=${result.decision?.slots} L0=${t.ruleMs}ms rule=${t.rule?.intent} " +
                "L1=${t.modelStatus}/${t.modelMs}ms total=${t.totalMs}ms" +
                if (result.decision?.intent == com.voicekiosk.app.ondevice.route.Intents.CHITCHAT && result.source != com.voicekiosk.app.conversation.TurnSource.CLOUD) " reply=${result.reply}" else ""
        )
        if (result.source == com.voicekiosk.app.conversation.TurnSource.CLOUD &&
            result.decision?.intent == com.voicekiosk.app.ondevice.route.Intents.MEMORY) {
            memoryManager?.onMemoryCommand()
        }
        val reply = result.reply
        if (reply == null) {
            // Offline notice on dashboard, NEVER speak a fake answer
            WakeWordEventBus.emitChatOfflineError(result.error ?: "网络连接失败，请检查网络或桥接服务")
            return TurnOutcome.ERROR
        }
        val shown = if (routeDebug) "$reply\n〔${result.source.label} · L0 ${t.ruleMs}ms · L1 ${t.modelStatus}${if (t.modelMs > 0) " ${t.modelMs}ms" else ""} · 共 ${t.totalMs}ms〕" else reply
        if (result.source == com.voicekiosk.app.conversation.TurnSource.CLOUD &&
            result.route == "muse" &&
            !result.conversationId.isNullOrEmpty()) {
            return handleMuseWaitingTurn(
                record = record,
                shortBuffer = shortBuffer,
                ackReply = shown,
                conversationId = result.conversationId,
                speakAck = lastCloudSpeak
            )
        }
        WakeWordEventBus.emitChatReply(shown)
        val skipTts = result.source == com.voicekiosk.app.conversation.TurnSource.CLOUD && !lastCloudSpeak
        if (!skipTts) speakBlocking(reply)
        drainAudioSamples(record, shortBuffer, 150L)
        return TurnOutcome.ANSWERED
    }

    /**
     * Handles the two-stage reply and waiting state for Muse async queue routes.
     * Speaks the initial ack prompt, enters the waiting state displaying an indicator on screen,
     * and polls GET /api/v1/conversations/{conversation_id} every 3 seconds on this background
     * thread until answered, timed out, or 2 minutes elapse.
     */
    private fun handleMuseWaitingTurn(
        record: AudioRecord,
        shortBuffer: ShortArray,
        ackReply: String,
        conversationId: String,
        speakAck: Boolean
    ): TurnOutcome {
        WakeWordEventBus.emitChatReply(ackReply)
        if (speakAck) {
            speakBlocking(ackReply)
        }
        drainAudioSamples(record, shortBuffer, 150L)

        Log.i(TAG, "Entering waiting state for Muse conversation_id=$conversationId")
        pipelineState = PipelineState.WAITING
        WakeWordEventBus.emitStateChanged(pipelineState.name)
        val waitingText = "等待 Muse 回复中"
        WakeWordEventBus.emitWaitingForMuse(waitingText)
        WakeWordEventBus.emitChatReply(waitingText)

        // Important design decision: The Android app has no WebSocket client (there is no WebSocket code
        // anywhere in android/). Rather than adding a WebSocket dependency, HTTP polling of
        // GET /api/v1/conversations/{conversation_id} with the device token as Bearer auth is used.
        // This reuses the existing HttpURLConnection code and is simpler and more reliable than
        // adding a new WebSocket dependency.
        val baseUrl = AppConfig.getBridgeUrl(applicationContext)
        val token = AppConfig.getDeviceToken(applicationContext)
        val startTime = System.currentTimeMillis()
        var finalReply: String? = null
        var answeredOrTimedOut = false

        while (isRecording && (System.currentTimeMillis() - startTime) < ConversationPoller.MAX_WAIT_MS) {
            val pollSleepEnd = System.currentTimeMillis() + ConversationPoller.POLL_INTERVAL_MS
            while (isRecording && System.currentTimeMillis() < pollSleepEnd) {
                record.read(shortBuffer, 0, shortBuffer.size)
                try {
                    Thread.sleep(50L)
                } catch (_: InterruptedException) {
                    break
                }
            }
            if (!isRecording) break

            val pollResult = ConversationPoller.fetchConversation(baseUrl, token, conversationId)
            if (pollResult.isSuccess) {
                val recordStatus = pollResult.getOrNull()
                val status = recordStatus?.status.orEmpty()
                Log.i(TAG, "Polled conversation $conversationId: status=$status")
                if (status == ConversationPoller.STATUS_ANSWERED) {
                    finalReply = recordStatus?.reply.orEmpty().trim().ifEmpty {
                        "Muse 已回复"
                    }
                    answeredOrTimedOut = true
                    break
                } else if (status == ConversationPoller.STATUS_TIMED_OUT) {
                    finalReply = recordStatus?.reply.orEmpty().trim().ifEmpty {
                        ConversationPoller.FALLBACK_TIMEOUT_REPLY
                    }
                    answeredOrTimedOut = true
                    break
                }
            } else {
                Log.w(TAG, "Poll conversation $conversationId failed: ${pollResult.exceptionOrNull()?.message}; retrying next tick")
            }
        }

        if (!answeredOrTimedOut) {
            Log.w(TAG, "Waiting for Muse conversation $conversationId timed out after 2 minutes")
            finalReply = ConversationPoller.FALLBACK_TIMEOUT_REPLY
        }

        val toSpeak = finalReply ?: ConversationPoller.FALLBACK_TIMEOUT_REPLY
        orchestrator?.context?.updateLastReply(toSpeak)
        pipelineState = PipelineState.SPEAKING
        WakeWordEventBus.emitStateChanged(pipelineState.name)
        WakeWordEventBus.emitChatReply(toSpeak)
        speakBlocking(toSpeak)
        drainAudioSamples(record, shortBuffer, 150L)

        return TurnOutcome.COMPLETED
    }

    /** Timer done: wait (up to ~1 min) until no conversation is running, then beep + speak. */
    private fun announceTimer(label: String?, seconds: Int, attempt: Int) {
        if (pipelineState != PipelineState.KWS_LISTENING && attempt < 40) {
            android.os.Handler(mainLooper).postDelayed({ announceTimer(label, seconds, attempt + 1) }, 1500L)
            return
        }
        val text = if (label != null) "时间到了，提醒你：$label。" else "计时${LocalToolExecutor.spokenDuration(seconds)}到了。"
        WakeWordEventBus.emitChatReply("⏰ $text")
        playPromptBeep()
        val engine = tts
        if (isTtsReady && engine != null) {
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, "vk_timer_${UUID.randomUUID()}")
        }
    }

    private fun installedVersionCode(): Int {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun checkBridgeHealth() {
        thread(name = "VoiceKiosk-HealthCheck", isDaemon = true) {
            val baseUrl = AppConfig.getBridgeUrl(applicationContext)
            val fetched = BridgeHealthClient.check(baseUrl)
            fetched.onSuccess { info ->
                bridgeReachable = info.ok
                WakeWordEventBus.emitBridgeNotice("")
            }.onFailure {
                bridgeReachable = false
                Log.w(TAG, "Bridge health check failed: ${it.message}")
            }
        }
    }

    /**
     * Fast path used when on-device routing is off: still map short answers onto
     * the open question / approval so "第二个" does not wait for a cloud round-trip.
     */
    private fun tryLocalQuestionOrApproval(text: String): String? {
        currentOpenApproval()?.let { ap ->
            val pin = QuestionAnswerMatcher.matchApproval(text)
            if (pin != null && pin.isApprovalPin) {
                WakeWordEventBus.emitRequestPin(ap.id, if (pin.approveVoice) "approve" else "reject")
                return "请在屏幕上输入审批 PIN"
            }
        }
        val q = currentOpenQuestion() ?: return null
        val m = QuestionAnswerMatcher.match(text, q) ?: return null
        if (!m.hasAnswer) return null
        val posted = postOpenAnswer(q, m)
        if (posted.isFailure) {
            val now = System.currentTimeMillis()
            val expMs = if (q.expiresAt > 0) (q.expiresAt * 1000.0).toLong() else now + AnswerQueue.FALLBACK_TTL_MS
            answerQueue.enqueue(q.id, m.option, m.text, expMs)
        } else {
            setOpenQuestion(null)
        }
        val chosen = m.option ?: m.text.orEmpty()
        return if (!m.option.isNullOrEmpty()) "好的，已选择$chosen" else "好的，已记录你的回答"
    }

    private fun postOpenAnswer(q: OpenQuestion, m: QuestionMatch): Result<Unit> {
        return Result.success(Unit)
    }

    private fun flushAnswerQueue() {
        answerQueue.takeReady()
    }

    /**
     * POST /api/v1/chat. Body carries text and session_id. Token is
     * sent as Bearer token; redirects are disabled. Idempotency-Key is
     * a random UUID per turn.
     */
    private fun postChat(req: com.voicekiosk.app.conversation.CloudRequest): Result<CloudReply> {
        val baseUrl = AppConfig.getBridgeUrl(applicationContext).trim().trimEnd('/')
        val bridgeToken = AppConfig.getDeviceToken(applicationContext)
        if (bridgeToken.isEmpty()) {
            return Result.failure(IllegalStateException(ErrorEnvelope.UNPAIRED_MESSAGE))
        }
        val idempotencyKey = UUID.randomUUID().toString()
        return postChatOnce(req, baseUrl, bridgeToken, idempotencyKey)
    }

    private fun postChatOnce(
        req: com.voicekiosk.app.conversation.CloudRequest,
        baseUrl: String,
        bridgeToken: String,
        idempotencyKey: String
    ): Result<CloudReply> {
        var conn: HttpURLConnection? = null
        return try {
            val endpointUrl = URL("$baseUrl${ConversationWire.PATH}")
            conn = (endpointUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = AppConfig.CHAT_TIMEOUT_MS
                readTimeout = AppConfig.CHAT_TIMEOUT_MS
                instanceFollowRedirects = false
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $bridgeToken")
                setRequestProperty("Idempotency-Key", idempotencyKey)
            }

            val payload = ConversationWire.requestJson(
                req,
                AppConfig.CHAT_DEFAULT_SESSION,
                System.currentTimeMillis()
            ).toString()

            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bodyStr = bodyStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                val parsed = ConversationWire.parseReply(JSONObject(bodyStr))
                lastCloudSpeak = parsed.speak
                flushAnswerQueue()
                if (parsed.reply.isNotEmpty()) {
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException("服务返回空内容"))
                }
            } else {
                Result.failure(IllegalStateException(ErrorEnvelope.userMessage(code, bodyStr)))
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("网络请求超时，当前处于离线或桥接不可达状态"))
        } catch (e: java.io.IOException) {
            Result.failure(IllegalStateException("网络不可用，无法连接语音桥接服务"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("请求失败：${e.message}"))
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        try {
            kwsStream?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing KWS OnlineStream", e)
        }
        kwsStream = null

        try {
            kws?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing KeywordSpotter", e)
        }
        kws = null

        try {
            asrRecognizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing OnlineRecognizer", e)
        }
        asrRecognizer = null
        isAsrReady = false
    }
}
