package com.voicekiosk.app.schedule

import com.voicekiosk.app.alarm.AlarmItem
import com.voicekiosk.app.countdown.CountdownItem
import org.json.JSONObject
import java.util.UUID

/**
 * Unified entity model for alarms, count-down timers, and schedules per plan §2.1 & Phase 1 schema.
 * Reuses the Phase 1 ScheduleItem JSON shape directly for the local model.
 */
data class ScheduleItem(
    val id: String = generateId(),
    val type: String = TYPE_SCHEDULE,
    val title: String,
    val targetTimeUtc: Double,
    val durationSeconds: Int = 0,
    val recurrence: String = "",
    val status: String = STATUS_ACTIVE,
    val origin: String = ORIGIN_APK_UI,
    val createdAtUtc: Double = System.currentTimeMillis() / 1000.0,
    val updatedAtUtc: Double = System.currentTimeMillis() / 1000.0,
    val metadata: Map<String, Any?> = emptyMap()
) {
    /** Target time converted to epoch milliseconds. */
    val targetTimeMillis: Long
        get() = (targetTimeUtc * 1000.0).toLong()

    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    val isCompleted: Boolean
        get() = status == STATUS_COMPLETED || status == STATUS_DISMISSED

    val isActive: Boolean
        get() = status == STATUS_ACTIVE

    /**
     * Serializes to the wire format matching the Phase 1 bridge schema exactly.
     */
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("type", type)
        obj.put("title", title)
        obj.put("target_time_utc", targetTimeUtc)
        obj.put("duration_seconds", durationSeconds)
        obj.put("recurrence", recurrence)
        obj.put("status", status)
        obj.put("origin", origin)
        obj.put("created_at_utc", createdAtUtc)
        obj.put("updated_at_utc", updatedAtUtc)
        val metaObj = JSONObject()
        metadata.forEach { (k, v) ->
            if (v != null) metaObj.put(k, v)
        }
        obj.put("metadata", metaObj)
        return obj
    }

    /**
     * Converts to Phase 2 AlarmItem for alarm engine interoperability.
     */
    fun toAlarmItem(): AlarmItem {
        return AlarmItem(
            id = id,
            type = type,
            title = title,
            targetTimeUtc = targetTimeUtc,
            durationSeconds = durationSeconds,
            recurrence = recurrence,
            status = status,
            origin = origin,
            createdAtUtc = createdAtUtc,
            updatedAtUtc = updatedAtUtc,
            metadata = metadata
        )
    }

    companion object {
        const val TYPE_ALARM = "alarm"
        const val TYPE_COUNTDOWN = "countdown"
        const val TYPE_SCHEDULE = "schedule"

        const val STATUS_ACTIVE = "active"
        const val STATUS_RINGING = "ringing"
        const val STATUS_SNOOZED = "snoozed"
        const val STATUS_DISMISSED = "dismissed"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_CANCELLED = "cancelled"

        val TERMINAL_STATUSES = setOf(STATUS_DISMISSED, STATUS_COMPLETED, STATUS_CANCELLED)
        val NON_TERMINAL_STATUSES = setOf(STATUS_ACTIVE, STATUS_RINGING, STATUS_SNOOZED)

        const val ORIGIN_APK_VOICE = "apk_voice"
        const val ORIGIN_APK_UI = "apk_ui"
        const val ORIGIN_MUSE_VOICE = "muse_voice"
        const val ORIGIN_MUSE_PUSH = "muse_push"
        const val ORIGIN_BRIDGE_RULE = "bridge_rule"

        fun generateId(): String {
            val hex = UUID.randomUUID().toString().replace("-", "").take(12)
            return "sch_$hex"
        }

        fun fromJsonObject(obj: JSONObject): ScheduleItem {
            val id = obj.optString("id", generateId())
            val type = obj.optString("type", TYPE_SCHEDULE)
            val title = obj.optString("title", "")
            val targetTimeUtc = obj.optDouble("target_time_utc", 0.0)
            val durationSeconds = obj.optInt("duration_seconds", 0)
            val recurrence = obj.optString("recurrence", "")
            val status = obj.optString("status", STATUS_ACTIVE)
            val origin = obj.optString("origin", ORIGIN_APK_UI)
            val createdAtUtc = obj.optDouble("created_at_utc", System.currentTimeMillis() / 1000.0)
            val updatedAtUtc = obj.optDouble("updated_at_utc", System.currentTimeMillis() / 1000.0)

            val metaMap = mutableMapOf<String, Any?>()
            val metaObj = obj.optJSONObject("metadata")
            if (metaObj != null) {
                val keys = metaObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    metaMap[key] = metaObj.opt(key)
                }
            }

            return ScheduleItem(
                id = id,
                type = type,
                title = title,
                targetTimeUtc = targetTimeUtc,
                durationSeconds = durationSeconds,
                recurrence = recurrence,
                status = status,
                origin = origin,
                createdAtUtc = createdAtUtc,
                updatedAtUtc = updatedAtUtc,
                metadata = metaMap
            )
        }

        /**
         * Maps a Phase 2 LocalAlarmStore AlarmItem into ScheduleItem.
         */
        fun fromAlarmItem(alarm: AlarmItem): ScheduleItem {
            return ScheduleItem(
                id = alarm.id,
                type = alarm.type,
                title = alarm.title,
                targetTimeUtc = alarm.targetTimeUtc,
                durationSeconds = alarm.durationSeconds,
                recurrence = alarm.recurrence,
                status = alarm.status,
                origin = alarm.origin,
                createdAtUtc = alarm.createdAtUtc,
                updatedAtUtc = alarm.updatedAtUtc,
                metadata = alarm.metadata
            )
        }

        /**
         * Maps a Phase 4 CountdownItem into ScheduleItem.
         */
        fun fromCountdownItem(cd: CountdownItem, label: String? = cd.label): ScheduleItem {
            val now = System.currentTimeMillis()
            val isExp = cd.isExpired(now)
            return ScheduleItem(
                id = "cd_${cd.id.replace("-", "").take(12)}",
                type = TYPE_COUNTDOWN,
                title = label?.takeIf { it.isNotBlank() } ?: "倒计时",
                targetTimeUtc = cd.targetTimeMillis / 1000.0,
                durationSeconds = cd.durationSeconds,
                recurrence = "",
                status = if (isExp) STATUS_COMPLETED else STATUS_ACTIVE,
                origin = ORIGIN_APK_VOICE,
                createdAtUtc = cd.startTimeMillis / 1000.0,
                updatedAtUtc = now / 1000.0,
                metadata = mapOf("countdown_id" to cd.id)
            )
        }
    }
}
