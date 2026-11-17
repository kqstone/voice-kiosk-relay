package com.voicekiosk.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.voicekiosk.app.R

/**
 * Foreground service executing the physical alarm alert:
 * - Maintains a PowerManager.PARTIAL_WAKE_LOCK with a hard 3-minute release cap.
 * - Loops the bundled offline ringtone (R.raw.alarm_ringtone) on AudioManager.STREAM_ALARM.
 * - Displays a high-priority foreground notification with a full-screen intent targeting AlarmAlertActivity.
 * - Automatically stops ringing and dismisses if 3 minutes elapse with no touch or voice input.
 */
class AlarmRingingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentScheduleId: String = ""
    private var currentTitle: String = ""

    private val autoStopRunnable = Runnable {
        Log.i(TAG, "Hard 3-minute cap elapsed for alarm $currentScheduleId; auto-dismissing")
        if (currentScheduleId.isNotEmpty()) {
            AlarmScheduler.dismissAlarm(this, currentScheduleId)
            sendBroadcast(Intent(ACTION_ALARM_AUTO_STOPPED).apply {
                setPackage(packageName)
                putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, currentScheduleId)
            })
        }
        stopRinging()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_RINGING
        val scheduleId = intent?.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID).orEmpty()
        val title = intent?.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "闹钟"

        if (scheduleId.isNotEmpty()) {
            currentScheduleId = scheduleId
            currentTitle = title
        }

        when (action) {
            ACTION_START_RINGING -> {
                acquireWakeLock(this)
                startForegroundNotification(currentScheduleId, currentTitle)
                startAudioPlayback()
                // Ensure hard 3-minute release cap
                mainHandler.removeCallbacks(autoStopRunnable)
                mainHandler.postDelayed(autoStopRunnable, HARD_CAP_MS)
            }
            ACTION_STOP_RINGING -> {
                stopRinging()
            }
            ACTION_DISMISS -> {
                if (currentScheduleId.isNotEmpty()) {
                    AlarmScheduler.dismissAlarm(this, currentScheduleId)
                }
                stopRinging()
            }
            ACTION_SNOOZE -> {
                if (currentScheduleId.isNotEmpty()) {
                    AlarmScheduler.snoozeAlarm(this, currentScheduleId, 5)
                }
                stopRinging()
            }
            ACTION_DUCK_AUDIO -> {
                val duck = intent?.getBooleanExtra(EXTRA_DUCK_STATE, false) ?: false
                setAudioDucking(duck)
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification(scheduleId: String, title: String) {
        createNotificationChannel()

        val fullScreenIntent = Intent(this, AlarmAlertActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(AlarmScheduler.EXTRA_TITLE, title)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            scheduleId.hashCode(),
            fullScreenIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val dismissIntent = Intent(this, AlarmRingingService::class.java).apply {
            action = ACTION_DISMISS
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
        }
        val dismissPendingIntent = PendingIntent.getService(
            this,
            scheduleId.hashCode() + 1,
            dismissIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val snoozeIntent = Intent(this, AlarmRingingService::class.java).apply {
            action = ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
        }
        val snoozePendingIntent = PendingIntent.getService(
            this,
            scheduleId.hashCode() + 2,
            snoozeIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("闹钟正在响铃")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(0, "关闭", dismissPendingIntent)
            .addAction(0, "稍后提醒", snoozePendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "闹钟响铃提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "闹钟到达时的全屏告警与声音提示"
                    setSound(null, null) // Audio is managed directly by MediaPlayer on STREAM_ALARM
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun startAudioPlayback() {
        if (mediaPlayer != null) return

        try {
            // Attempt bundled offline ringtone first
            mediaPlayer = MediaPlayer.create(this, R.raw.alarm_ringtone)?.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(AudioManager.STREAM_ALARM)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_ALARM)
                }
                isLooping = true
            }

            if (mediaPlayer == null) {
                Log.w(TAG, "Bundled ringtone failed to open, falling back to system default alarm")
                val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(this@AlarmRingingService, alertUri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setLegacyStreamType(AudioManager.STREAM_ALARM)
                                .build()
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        setAudioStreamType(AudioManager.STREAM_ALARM)
                    }
                    isLooping = true
                    prepare()
                }
            }

            mediaPlayer?.start()
            Log.i(TAG, "Alarm audio playback started on STREAM_ALARM")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start alarm audio playback", e)
        }
    }

    fun setAudioDucking(duck: Boolean) {
        try {
            val volume = if (duck) 0.25f else 1.0f
            mediaPlayer?.setVolume(volume, volume)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set audio ducking", e)
        }
    }

    private fun stopRinging() {
        mainHandler.removeCallbacks(autoStopRunnable)

        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media player", e)
        } finally {
            mediaPlayer = null
        }

        releaseWakeLock()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    override fun onDestroy() {
        activeServiceInstance = null
        stopRinging()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingingService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "voice_kiosk_alarm_ringing_channel"
        const val HARD_CAP_MS = 3 * 60 * 1000L // 3 minutes

        const val ACTION_START_RINGING = "com.voicekiosk.app.alarm.ACTION_START_RINGING"
        const val ACTION_STOP_RINGING = "com.voicekiosk.app.alarm.ACTION_STOP_RINGING"
        const val ACTION_DISMISS = "com.voicekiosk.app.alarm.ACTION_DISMISS"
        const val ACTION_SNOOZE = "com.voicekiosk.app.alarm.ACTION_SNOOZE"
        const val ACTION_DUCK_AUDIO = "com.voicekiosk.app.alarm.ACTION_DUCK_AUDIO"
        const val EXTRA_DUCK_STATE = "com.voicekiosk.app.alarm.EXTRA_DUCK_STATE"

        const val ACTION_ALARM_AUTO_STOPPED = "com.voicekiosk.app.alarm.ACTION_ALARM_AUTO_STOPPED"

        @Volatile
        private var activeServiceInstance: AlarmRingingService? = null

        private var wakeLock: PowerManager.WakeLock? = null

        @Synchronized
        fun acquireWakeLock(context: Context) {
            try {
                if (wakeLock == null) {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceKiosk::AlarmRingingWakeLock").apply {
                        setReferenceCounted(false)
                    }
                }
                wakeLock?.let {
                    if (!it.isHeld) {
                        it.acquire(HARD_CAP_MS)
                        Log.i(TAG, "Acquired partial wake lock with ${HARD_CAP_MS / 1000}s hard cap")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to acquire alarm partial wake lock", e)
            }
        }

        @Synchronized
        fun releaseWakeLock() {
            try {
                wakeLock?.let {
                    if (it.isHeld) {
                        it.release()
                        Log.i(TAG, "Released alarm partial wake lock")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release alarm partial wake lock", e)
            } finally {
                wakeLock = null
            }
        }

        fun stopRinging(context: Context) {
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = ACTION_STOP_RINGING
            }
            context.startService(intent)
        }

        fun duckAudio(context: Context, duck: Boolean) {
            activeServiceInstance?.setAudioDucking(duck)
        }
    }
}
