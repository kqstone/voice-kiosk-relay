package com.voicekiosk.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * App-internal, non-exported BroadcastReceiver invoked by AlarmManager when an exact alarm fires.
 * Validates the anti-duplicate check against local storage, acquires a capped wake lock,
 * and launches AlarmRingingService & AlarmAlertActivity.
 */
class AlarmBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmBroadcastReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val scheduleId = intent.getStringExtra(AlarmScheduler.EXTRA_SCHEDULE_ID) ?: return
        val receivedToken = intent.getStringExtra(AlarmScheduler.EXTRA_ANTI_DUPLICATE_TOKEN)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "闹钟"
        val targetTimeUtc = intent.getDoubleExtra(AlarmScheduler.EXTRA_TARGET_TIME_UTC, 0.0)

        Log.i(TAG, "Alarm broadcast received for $scheduleId, token=$receivedToken")

        val store = LocalAlarmStore.get(context)
        val storedItem = store.getAlarm(scheduleId)

        // Anti-duplicate check
        if (AlarmScheduler.isDuplicateOrStale(storedItem, receivedToken, System.currentTimeMillis())) {
            Log.w(TAG, "Ignoring stale or duplicate alarm broadcast for $scheduleId")
            return
        }

        // Acquire partial wake lock with a hard 3-minute cap
        AlarmRingingService.acquireWakeLock(context)

        // Mark alarm status as ringing in local storage
        store.updateStatus(scheduleId, AlarmItem.STATUS_RINGING)

        // Launch foreground ringing service
        val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
            action = AlarmRingingService.ACTION_START_RINGING
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(AlarmScheduler.EXTRA_TITLE, title)
            putExtra(AlarmScheduler.EXTRA_TARGET_TIME_UTC, targetTimeUtc)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AlarmRingingService", e)
        }

        // Launch full-screen minimal black alert UI
        val activityIntent = Intent(context, AlarmAlertActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(AlarmScheduler.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(AlarmScheduler.EXTRA_TITLE, title)
            putExtra(AlarmScheduler.EXTRA_TARGET_TIME_UTC, targetTimeUtc)
        }
        try {
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AlarmAlertActivity directly", e)
        }
    }
}
