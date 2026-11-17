package com.voicekiosk.app.schedule

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Pure functions governing synchronization logic, cursor math, terminal state domination,
 * candidate payloads, and today-plus-tomorrow date filtering.
 * Designed for pure host-JVM testing without Android dependencies.
 */
object ScheduleSyncLogic {

    /**
     * Compares two monotonic cursors of the format "{updated_at_utc}_{id}".
     * Returns negative if c1 < c2, 0 if c1 == c2, positive if c1 > c2.
     * Empty cursor is treated as smallest.
     */
    fun compareCursors(c1: String, c2: String): Int {
        if (c1 == c2) return 0
        if (c1.isEmpty()) return -1
        if (c2.isEmpty()) return 1

        val p1 = c1.indexOf('_')
        val p2 = c2.indexOf('_')
        if (p1 == -1 || p2 == -1) return c1.compareTo(c2)

        val ts1 = c1.substring(0, p1).toDoubleOrNull() ?: 0.0
        val ts2 = c2.substring(0, p2).toDoubleOrNull() ?: 0.0
        val tsCmp = ts1.compareTo(ts2)
        if (tsCmp != 0) return tsCmp

        val id1 = c1.substring(p1 + 1)
        val id2 = c2.substring(p2 + 1)
        return id1.compareTo(id2)
    }

    /**
     * Determines whether a full resync is needed based on server reset flag or HTTP status code.
     * Stale cursor or server 400 triggers a full resync from cursor "".
     */
    fun shouldPerformFullResync(
        resetFlag: Boolean,
        httpStatusCode: Int = 200,
        currentCursor: String = ""
    ): Boolean {
        if (resetFlag) return true
        if (httpStatusCode == 400) return true
        if (httpStatusCode in 404..499 && currentCursor.isNotEmpty()) return true
        return false
    }

    /**
     * Merges snapshot from the bridge into the local item set.
     *
     * Rules per plan §3.3 & §3.4:
     * - If [reset] is true: server snapshot replaces all local state completely.
     * - Deletions: any id in [deletedIds] is physically removed.
     * - Terminal state domination: once an item reaches terminal state (dismissed, completed, cancelled),
     *   an incoming non-terminal status (active, ringing, snoozed) must NEVER overwrite it.
     * - Non-conflicting / same lifecycle class: Last-Write-Wins based on updatedAtUtc.
     */
    fun mergeSnapshot(
        localItems: List<ScheduleItem>,
        incomingItems: List<ScheduleItem>,
        deletedIds: Collection<String>,
        reset: Boolean
    ): List<ScheduleItem> {
        val deletedSet = deletedIds.toSet()
        if (reset) {
            return incomingItems
                .filter { it.id !in deletedSet }
                .sortedWith(compareBy({ it.targetTimeUtc }, { it.id }))
        }

        val resultMap = localItems.filter { it.id !in deletedSet }.associateBy { it.id }.toMutableMap()

        for (incoming in incomingItems) {
            if (incoming.id in deletedSet) continue

            val existing = resultMap[incoming.id]
            if (existing == null) {
                resultMap[incoming.id] = incoming
            } else {
                // Terminal state protection:
                if (existing.isTerminal && !incoming.isTerminal) {
                    // Retain terminal status, but accept updated title/metadata if incoming is newer
                    if (incoming.updatedAtUtc >= existing.updatedAtUtc) {
                        resultMap[incoming.id] = existing.copy(
                            title = incoming.title,
                            metadata = existing.metadata + incoming.metadata,
                            updatedAtUtc = maxOf(existing.updatedAtUtc, incoming.updatedAtUtc)
                        )
                    }
                } else if (!existing.isTerminal && incoming.isTerminal) {
                    resultMap[incoming.id] = incoming
                } else {
                    // Both terminal or both non-terminal: Last-Write-Wins
                    if (incoming.updatedAtUtc >= existing.updatedAtUtc) {
                        resultMap[incoming.id] = incoming
                    }
                }
            }
        }

        return resultMap.values.sortedWith(compareBy({ it.targetTimeUtc }, { it.id }))
    }

    /**
     * Builds candidate payload JSON object strictly matching the Phase 1 bridge contract:
     * {"candidates": [ ... ]}
     *
     * NEVER sends {"items": [...]} or snapshot wire format upstream.
     */
    fun buildCandidatePayload(candidates: List<ScheduleItem>): JSONObject {
        val arr = JSONArray()
        for (c in candidates) {
            arr.put(c.toJsonObject())
        }
        return JSONObject().apply {
            put("candidates", arr)
        }
    }

    /**
     * Filters items to "today plus tomorrow only" as per user decision.
     * Evaluates against local system timezone:
     * - Today start: 00:00:00.000 local time
     * - Tomorrow end: 23:59:59.999 local time (strictly before dayAfterTomorrow at 00:00:00)
     */
    fun filterTodayAndTomorrow(
        items: List<ScheduleItem>,
        nowMillis: Long = System.currentTimeMillis(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): List<ScheduleItem> {
        val nowZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zoneId)
        val todayStart = nowZdt.toLocalDate().atStartOfDay(zoneId).toInstant().toEpochMilli() / 1000.0
        val dayAfterTomorrowStart = nowZdt.toLocalDate().plusDays(2).atStartOfDay(zoneId).toInstant().toEpochMilli() / 1000.0

        return items.filter { item ->
            item.targetTimeUtc >= todayStart && item.targetTimeUtc < dayAfterTomorrowStart
        }.sortedWith(compareBy({ it.targetTimeUtc }, { it.id }))
    }

    /**
     * Toggles an item to completed status, updating metadata and timestamp.
     */
    fun toggleItemComplete(
        item: ScheduleItem,
        nowSeconds: Double = System.currentTimeMillis() / 1000.0
    ): ScheduleItem {
        val newMeta = item.metadata.toMutableMap()
        newMeta["completed_at_utc"] = nowSeconds
        return item.copy(
            status = ScheduleItem.STATUS_COMPLETED,
            updatedAtUtc = nowSeconds,
            metadata = newMeta
        )
    }
}
