package com.voicekiosk.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.voicekiosk.app.R
import com.voicekiosk.app.WakeWordEventBus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen minimal black alert UI showing alarm title and time, with Dismiss and Snooze buttons.
 * Supports setShowWhenLocked and turnScreenOn, keeps screen on, and listens for voice commands
 * while ringing to dismiss or snooze.
 */
class AlarmAlertActivity : AppCompatActivity(), WakeWordEventBus.Listener {

    private var scheduleId: String = ""
    private var alarmTitle: String = ""
    private var speechRecognizer: SpeechRecognizer? = null
    private var isActionHandled = false

    private val autoStoppedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val stoppedId = intent?.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID).orEmpty()
            if (stoppedId.isEmpty() || stoppedId == scheduleId) {
                Log.i(TAG, "Alarm auto-stopped received; finishing activity")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Keep Screen On
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 2. Show When Locked & Turn Screen On
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        setContentView(R.layout.activity_alarm_alert)

        // Read intent extras
        scheduleId = intent?.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        alarmTitle = intent?.getStringExtra(AlarmScheduler.EXTRA_TITLE).takeUnless { it.isNullOrBlank() } ?: "闹钟"

        // Bind layout views (all guaranteed to exist in layout and layout-land)
        val tvAlarmTime = findViewById<TextView>(R.id.tvAlarmTime)
        val tvAlarmTitle = findViewById<TextView>(R.id.tvAlarmTitle)
        val tvVoiceHint = findViewById<TextView>(R.id.tvVoiceHint)
        val btnSnooze = findViewById<View>(R.id.btnSnooze)
        val btnDismiss = findViewById<View>(R.id.btnDismiss)

        // Format and display time
        val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        tvAlarmTime?.text = timeFormat.format(Date())
        tvAlarmTitle?.text = alarmTitle
        tvVoiceHint?.text = "可语音说“我知道了”关闭，或“稍后提醒”贪睡"

        // Click listeners
        btnDismiss?.setOnClickListener {
            dismissCurrentAlarm()
        }

        btnSnooze?.setOnClickListener {
            snoozeCurrentAlarm(5)
        }

        // Register auto-stopped receiver
        val filter = IntentFilter(AlarmRingingService.ACTION_ALARM_AUTO_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(autoStoppedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(autoStoppedReceiver, filter)
        }

        initSpeechRecognizer()
    }

    override fun onResume() {
        super.onResume()
        WakeWordEventBus.register(this)
        AlarmRingingService.duckAudio(this, true)
        startSpeechListening()
    }

    override fun onPause() {
        super.onPause()
        WakeWordEventBus.unregister(this)
        AlarmRingingService.duckAudio(this, false)
        stopSpeechListening()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(autoStoppedReceiver)
        } catch (_: Exception) {}

        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    // WakeWordEventBus voice event hooks
    override fun onWakeWordDetected(keyword: String) {
        // Audio already ducked
    }

    override fun onServiceStatusChanged(running: Boolean, message: String) {}

    override fun onAsrPartialResult(text: String) {
        processVoiceInput(text)
    }

    override fun onAsrFinalResult(text: String) {
        processVoiceInput(text)
    }

    private fun processVoiceInput(transcript: String) {
        if (isActionHandled || transcript.isBlank()) return

        val result = AlarmVoiceCommandMatcher.match(transcript)
        when (result.action) {
            AlarmVoiceAction.DISMISS -> {
                Log.i(TAG, "Voice dismiss command recognized: '$transcript'")
                dismissCurrentAlarm()
            }
            AlarmVoiceAction.SNOOZE -> {
                Log.i(TAG, "Voice snooze command recognized: '$transcript' (${result.snoozeMinutes}m)")
                snoozeCurrentAlarm(result.snoozeMinutes)
            }
            AlarmVoiceAction.NONE -> {
                // Ignore unrecognized phrases while ringing
            }
        }
    }

    private fun dismissCurrentAlarm() {
        if (isActionHandled) return
        isActionHandled = true
        Log.i(TAG, "Dismissing alarm $scheduleId")

        if (scheduleId.isNotEmpty()) {
            AlarmScheduler.dismissAlarm(this, scheduleId)
        }
        AlarmRingingService.stopRinging(this)
        finish()
    }

    private fun snoozeCurrentAlarm(minutes: Int) {
        if (isActionHandled) return
        isActionHandled = true
        Log.i(TAG, "Snoozing alarm $scheduleId for $minutes minute(s)")

        if (scheduleId.isNotEmpty()) {
            AlarmScheduler.snoozeAlarm(this, scheduleId, minutes)
        }
        AlarmRingingService.stopRinging(this)
        finish()
    }

    private fun initSpeechRecognizer() {
        try {
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            if (!isActionHandled && !isFinishing) {
                                startSpeechListening()
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let { processVoiceInput(it) }
                            if (!isActionHandled && !isFinishing) {
                                startSpeechListening()
                            }
                        }
                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let { processVoiceInput(it) }
                        }
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SpeechRecognizer initialization skipped", e)
        }
    }

    private fun startSpeechListening() {
        if (isActionHandled || speechRecognizer == null) return
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start speech recognition listening", e)
        }
    }

    private fun stopSpeechListening() {
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "AlarmAlertActivity"
    }
}
