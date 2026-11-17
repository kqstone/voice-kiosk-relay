package com.voicekiosk.app.alarm

import org.json.JSONObject
import java.util.UUID

/**
 * Unified entity model for alarms, count-down timers, and schedules per plan §2.1.
 * Stored locally and synchronised with the relay bridge in later phases.
 */
data class AlarmItem(
    val id: String = generateId(),
    val type: String = TYPE_ALARM,
    val title: String,
    val targetTimeUtc: Double,
    val durationSeconds: Int = 0,
    val recurrence: String = "",
    val status: String = STATUS_ACTIVE,
    val origin: String = ORIGIN_APK_VOICE,
    val createdAtUtc: Double = System.currentTimeMillis() / 1000.0,
    val updatedAtUtc: Double = System.currentTimeMillis() / 1000.0,
    val metadata: Map<String, Any?> = emptyMap()
) {
    /** Target time converted to epoch milliseconds for AlarmManager. */
    val targetTimeMillis: Long
        get() = (targetTimeUtc * 1000.0).toLong()

    /** Anti-duplicate token pairing schedule id and target time. */
    val antiDuplicateToken: String
        get() = "${id}_${targetTimeUtc}"

    val isTerminal: Boolean
        get() = status in TERMINAL_STATUSES

    val isActive: Boolean
        get() = status == STATUS_ACTIVE

    val isRinging: Boolean
        get() = status == STATUS_RINGING

    val isSnoozed: Boolean
        get() = status == STATUS_SNOOZED

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

        const val ORIGIN_APK_VOICE = "apk_voice"
        const val ORIGIN_APK_UI = "apk_ui"
        const val ORIGIN_MUSE_VOICE = "muse_voice"
        const val ORIGIN_BRIDGE_RULE = "bridge_rule"

        fun generateId(): String {
            val hex = UUID.randomUUID().toString().replace("-", "").take(12)
            return "sch_$hex"
        }

        fun fromJsonObject(obj: JSONObject): AlarmItem {
            val id = obj.optString("id", generateId())
            val type = obj.optString("type", TYPE_ALARM)
            val title = obj.optString("title", "")
            val targetTimeUtc = obj.optDouble("target_time_utc", 0.0)
            val durationSeconds = obj.optInt("duration_seconds", 0)
            val recurrence = obj.optString("recurrence", "")
            val status = obj.optString("status", STATUS_ACTIVE)
            val origin = obj.optString("origin", ORIGIN_APK_VOICE)
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
                metadata = metaMap
            )
        }
    }
}
