package com.voicekiosk.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/** Descriptor for unit-testing AlarmManager intent construction without Android runtime mocks. */
data class AlarmIntentDescriptor(
    val targetClass: String,
    val action: String,
    val scheduleId: String,
    val antiDuplicateToken: String,
    val title: String,
    val targetTimeUtc: Double,
    val requestCode: Int,
    val flags: Int
)

/**
 * On-device exact alarm scheduler wrapping AlarmManager.setExactAndAllowWhileIdle with RTC_WAKEUP.
 * Provides safe settings redirect when exact alarm permission is missing on Android 12+ (API 31+).
 */
object AlarmScheduler {
    private const val TAG = "AlarmScheduler"

    const val ACTION_ALARM_FIRE = "com.voicekiosk.app.alarm.ACTION_ALARM_FIRE"
    const val EXTRA_SCHEDULE_ID = "com.voicekiosk.app.alarm.EXTRA_SCHEDULE_ID"
    const val EXTRA_ANTI_DUPLICATE_TOKEN = "com.voicekiosk.app.alarm.EXTRA_ANTI_DUPLICATE_TOKEN"
    const val EXTRA_TITLE = "com.voicekiosk.app.alarm.EXTRA_TITLE"
    const val EXTRA_TARGET_TIME_UTC = "com.voicekiosk.app.alarm.EXTRA_TARGET_TIME_UTC"

    const val DEFAULT_SNOOZE_SECONDS = 5 * 60 // 5 minutes

    /**
     * Checks if the app is authorized to schedule exact alarms.
     * On Android 12+ (API 31+), delegates to AlarmManager.canScheduleExactAlarms().
     * On earlier versions, always returns true.
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * Constructs the Intent to redirect user to exact alarm settings.
     * Never crashes and provides fallback to application details settings.
     */
    fun getExactAlarmSettingIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Safely opens exact alarm permission settings or general app settings.
     * Returns true if the activity started successfully, false otherwise.
     */
    fun openExactAlarmSettings(context: Context): Boolean {
        return try {
            val intent = getExactAlarmSettingIntent(context)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch exact alarm settings, falling back to app settings", e)
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed to launch fallback app details settings", fallbackEx)
                false
            }
        }
    }

    /**
     * Checks if the app is whitelisted from battery optimizations.
     * On Android 6.0+ (API 23+), delegates to PowerManager.isIgnoringBatteryOptimizations().
     * On earlier versions, always returns true.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } else {
            true
        }
    }

    /**
     * Constructs the Intent to request battery optimization whitelist or open power settings.
     */
    fun getBatteryOptimizationSettingIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Safely opens battery optimization whitelist request or application details settings.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        return try {
            val intent = getBatteryOptimizationSettingIntent(context)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch battery optimization request, falling back to app settings", e)
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
                true
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed to launch fallback app details settings", fallbackEx)
                false
            }
        }
    }

    /** Builds descriptor describing the alarm intent, for pure JVM unit test verification. */
    fun buildAlarmIntentDescriptor(
        item: AlarmItem,
        isMarshmallowOrHigher: Boolean = true
    ): AlarmIntentDescriptor {
        val flags = if (isMarshmallowOrHigher) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return AlarmIntentDescriptor(
            targetClass = AlarmBroadcastReceiver::class.java.name,
            action = ACTION_ALARM_FIRE,
            scheduleId = item.id,
            antiDuplicateToken = item.antiDuplicateToken,
            title = item.title,
            targetTimeUtc = item.targetTimeUtc,
            requestCode = item.id.hashCode(),
            flags = flags
        )
    }

    /** Builds the explicit broadcast Intent targeting AlarmBroadcastReceiver. */
    fun buildAlarmIntent(context: Context, item: AlarmItem): Intent {
        return Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
            putExtra(EXTRA_SCHEDULE_ID, item.id)
            putExtra(EXTRA_ANTI_DUPLICATE_TOKEN, item.antiDuplicateToken)
            putExtra(EXTRA_TITLE, item.title)
            putExtra(EXTRA_TARGET_TIME_UTC, item.targetTimeUtc)
        }
    }

    /** Builds the PendingIntent wrapping buildAlarmIntent. */
    fun buildPendingIntent(
        context: Context,
        item: AlarmItem,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT
    ): PendingIntent {
        val intent = buildAlarmIntent(context, item)
        val requestCode = item.id.hashCode()
        val finalFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags or PendingIntent.FLAG_IMMUTABLE
        } else {
            flags
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, finalFlags)
    }

    /**
     * Schedules an alarm with AlarmManager.setExactAndAllowWhileIdle using RTC_WAKEUP.
     * Persists the alarm locally and gracefully handles permission rejection.
     */
    fun scheduleAlarm(context: Context, item: AlarmItem): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Log.e(TAG, "AlarmManager service unavailable")
            return false
        }

        if (!canScheduleExactAlarms(context)) {
            Log.w(TAG, "Exact alarm permission missing; redirecting to settings")
            openExactAlarmSettings(context)
            return false
        }

        // Persist item in local store
        val store = LocalAlarmStore.get(context)
        store.saveAlarm(item)

        val triggerAtMillis = item.targetTimeMillis
        val pendingIntent = buildPendingIntent(context, item)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
            Log.i(TAG, "Successfully scheduled alarm ${item.id} at $triggerAtMillis")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while scheduling exact alarm ${item.id}", e)
            openExactAlarmSettings(context)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error scheduling alarm ${item.id}", e)
            false
        }
    }

    /**
     * Cancels an alarm in AlarmManager and updates local status to CANCELLED.
     */
    fun cancelAlarm(context: Context, scheduleId: String): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(context, AlarmBroadcastReceiver::class.java).apply {
            action = ACTION_ALARM_FIRE
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
        }
        val requestCode = scheduleId.hashCode()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE
        }
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        if (pendingIntent != null && alarmManager != null) {
            try {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cancel PendingIntent for $scheduleId", e)
            }
        }
        LocalAlarmStore.get(context).updateStatus(scheduleId, AlarmItem.STATUS_CANCELLED)
        return true
    }

    /**
     * Pure function selecting active alarms to reschedule after boot.
     * Only selects alarms whose status is active and target time is in the future.
     */
    fun selectActiveAlarmsToReschedule(
        items: List<AlarmItem>,
        nowMillis: Long
    ): List<AlarmItem> {
        return items.filter { item ->
            item.status == AlarmItem.STATUS_ACTIVE && item.targetTimeMillis > nowMillis
        }
    }

    /**
     * Reschedules every locally stored active alarm whose target time is still in the future.
     * Called on device boot via BootReceiver.
     */
    fun rescheduleAllActiveAlarms(context: Context): Int {
        val store = LocalAlarmStore.get(context)
        val allAlarms = store.getAllAlarms()
        val nowMillis = System.currentTimeMillis()
        val toReschedule = selectActiveAlarmsToReschedule(allAlarms, nowMillis)

        var count = 0
        for (alarm in toReschedule) {
            if (scheduleAlarm(context, alarm)) {
                count++
            }
        }
        Log.i(TAG, "Rescheduled $count / ${toReschedule.size} active alarm(s)")
        return count
    }

    /**
     * Pure function computing updated alarm item for snooze.
     * Increments snooze_count, sets new target time, and updates anti-duplicate token.
     */
    fun computeSnooze(
        item: AlarmItem,
        snoozeSeconds: Int = DEFAULT_SNOOZE_SECONDS,
        nowSeconds: Double = System.currentTimeMillis() / 1000.0
    ): AlarmItem {
        val currentSnoozeCount = (item.metadata["snooze_count"] as? Number)?.toInt() ?: 0
        val newMetadata = item.metadata.toMutableMap().apply {
            put("snooze_count", currentSnoozeCount + 1)
            put("snooze_interval_seconds", snoozeSeconds)
        }
        val newTargetTimeUtc = nowSeconds + snoozeSeconds
        return item.copy(
            targetTimeUtc = newTargetTimeUtc,
            status = AlarmItem.STATUS_SNOOZED,
            updatedAtUtc = nowSeconds,
            metadata = newMetadata
        )
    }

    /**
     * Pure function computing updated alarm item for dismissal.
     * Marks status as dismissed and records completed_at_utc.
     */
    fun computeDismiss(
        item: AlarmItem,
        nowSeconds: Double = System.currentTimeMillis() / 1000.0
    ): AlarmItem {
        val newMetadata = item.metadata.toMutableMap().apply {
            put("completed_at_utc", nowSeconds)
        }
        return item.copy(
            status = AlarmItem.STATUS_DISMISSED,
            updatedAtUtc = nowSeconds,
            metadata = newMetadata
        )
    }

    /**
     * Pure anti-duplicate check verifying broadcast validity.
     * Returns true if the firing should be ignored (duplicate, stale, or cancelled).
     */
    fun isDuplicateOrStale(
        storedItem: AlarmItem?,
        receivedToken: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (storedItem == null) return true
        if (storedItem.isTerminal) return true
        if (storedItem.isRinging) return true
        if (receivedToken.isNullOrEmpty() || storedItem.antiDuplicateToken != receivedToken) return true
        return false
    }

    /**
     * Snoozes the alarm by scheduling a new trigger time and persisting it.
     */
    fun snoozeAlarm(context: Context, scheduleId: String, snoozeMinutes: Int = 5): AlarmItem? {
        val store = LocalAlarmStore.get(context)
        val item = store.getAlarm(scheduleId) ?: return null
        val snoozeSeconds = maxOf(1, snoozeMinutes) * 60
        val snoozed = computeSnooze(item, snoozeSeconds)
        store.saveAlarm(snoozed)
        scheduleAlarm(context, snoozed)
        return snoozed
    }

    /**
     * Dismisses the alarm and records completion in local store.
     */
    fun dismissAlarm(context: Context, scheduleId: String): AlarmItem? {
        val store = LocalAlarmStore.get(context)
        val item = store.getAlarm(scheduleId) ?: return null
        val dismissed = computeDismiss(item)
        store.saveAlarm(dismissed)
        cancelAlarm(context, scheduleId)
        return dismissed
    }
}
