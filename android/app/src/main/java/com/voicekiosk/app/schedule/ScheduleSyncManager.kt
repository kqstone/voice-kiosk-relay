package com.voicekiosk.app.schedule

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.alarm.AlarmScheduler
import com.voicekiosk.app.alarm.LocalAlarmStore
import com.voicekiosk.app.bridge.BridgeWebSocketClient
import com.voicekiosk.app.countdown.CountdownManager
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors

/**
 * Coordinates two-way synchronization between device local schedules, alarms, countdowns,
 * and the relay bridge schedules engine (/api/v1/schedules).
 *
 * Rules:
 * - Pull on app start and on network reconnect.
 * - Push local changes right after each local operation.
 * - Run a silent 60-second pull fallback in case the WebSocket drops.
 * - On a stale cursor or a server reset flag, do a full resync from cursor "".
 * - Never send the bridge snapshot format upstream; candidates payload strictly matches Phase 1 contract.
 * - The schedule board shows today plus tomorrow only.
 * - Zero hardcoded deployment values anywhere.
 */
class ScheduleSyncManager(
    private val context: Context,
    private val diskCache: LocalScheduleCache = LocalScheduleCache.get(context),
    private val alarmStore: LocalAlarmStore = LocalAlarmStore.get(context)
) {
    companion object {
        private const val TAG = "ScheduleSyncManager"
        private const val FALLBACK_POLL_INTERVAL_MS = 60_000L
    }

    interface Listener {
        fun onSchedulesUpdated(items: List<ScheduleItem>)
    }

    private var listener: Listener? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isStarted = false

    private var wsClient: BridgeWebSocketClient? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isStarted) return
            pullSnapshot(silent = true)
            pushLocalChanges()
            mainHandler.postDelayed(this, FALLBACK_POLL_INTERVAL_MS)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        // 1. Initial render from local cache, alarms, and countdowns immediately
        notifyUpdated()

        // 2. Perform initial snapshot pull and candidate push in background
        executor.execute {
            pullSnapshot(silent = false)
            pushLocalChanges()
            connectWebSocket()
        }

        // 3. Schedule 60-second periodic fallback polling
        mainHandler.postDelayed(pollRunnable, FALLBACK_POLL_INTERVAL_MS)
    }

    fun stop() {
        isStarted = false
        mainHandler.removeCallbacks(pollRunnable)
        disconnectWebSocket()
    }

    fun onNetworkAvailable() {
        if (!isStarted) return
        executor.execute {
            pullSnapshot(silent = false)
            pushLocalChanges()
            if (wsClient == null) {
                connectWebSocket()
            }
        }
    }

    /**
     * Re-reads current state from local cache, alarm engine, and countdown manager,
     * filters to today plus tomorrow, and notifies the UI listener.
     */
    fun notifyLocalMutation() {
        notifyUpdated()
        executor.execute {
            pushLocalChanges()
        }
    }

    /**
     * Pulls incremental snapshot from bridge. If server indicates reset or cursor is stale (400),
     * automatically falls back to full resync from empty cursor.
     */
    fun pullSnapshot(silent: Boolean = false) {
        executor.execute {
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val token = AppConfig.getBridgeAuthTokenForUrl(context, bridgeUrl)
            if (bridgeUrl.isBlank()) return@execute

            val currentCursor = diskCache.getCursor()
            val snapResult = ScheduleApiClient.fetchSnapshot(
                baseUrl = bridgeUrl,
                token = token,
                since = currentCursor
            )

            if (snapResult.isSuccess) {
                val snap = snapResult.getOrThrow()
                if (snap.reset) {
                    Log.i(TAG, "Server requested full reset. Performing full resync.")
                    diskCache.applySnapshot(
                        incoming = snap.items,
                        deletedIds = snap.deletedIds,
                        newCursor = snap.cursor,
                        reset = true
                    )
                } else {
                    diskCache.applySnapshot(
                        incoming = snap.items,
                        deletedIds = snap.deletedIds,
                        newCursor = snap.cursor,
                        reset = false
                    )
                }
                notifyUpdated()
            } else {
                val err = snapResult.exceptionOrNull()
                val isStaleCursor = err?.message?.contains("400") == true ||
                    ScheduleSyncLogic.shouldPerformFullResync(
                        resetFlag = false,
                        httpStatusCode = 400,
                        currentCursor = currentCursor
                    )

                if (isStaleCursor && currentCursor.isNotEmpty()) {
                    Log.w(TAG, "Stale cursor detected ($currentCursor). Initiating full resync fallback...")
                    val fullResult = ScheduleApiClient.fetchSnapshot(
                        baseUrl = bridgeUrl,
                        token = token,
                        since = ""
                    )
                    if (fullResult.isSuccess) {
                        val fullSnap = fullResult.getOrThrow()
                        diskCache.applySnapshot(
                            incoming = fullSnap.items,
                            deletedIds = fullSnap.deletedIds,
                            newCursor = fullSnap.cursor,
                            reset = true
                        )
                        notifyUpdated()
                    } else if (!silent) {
                        Log.e(TAG, "Full resync fallback failed: ${fullResult.exceptionOrNull()?.message}")
                    }
                } else if (!silent) {
                    Log.w(TAG, "Schedule snapshot fetch failed: ${err?.message}")
                }
            }
        }
    }

    /**
     * Pushes pending candidates and any newly created local alarms to the bridge.
     */
    fun pushLocalChanges() {
        executor.execute {
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val token = AppConfig.getBridgeAuthTokenForUrl(context, bridgeUrl)
            if (bridgeUrl.isBlank()) return@execute

            val pending = diskCache.getPendingCandidates().toMutableList()

            // Also check for local alarms from Phase 2 that should be candidates
            val localAlarms = alarmStore.getAllAlarms().map { ScheduleItem.fromAlarmItem(it) }
            val existingIds = diskCache.getAllItems().map { it.id }.toSet()
            for (alarm in localAlarms) {
                if (alarm.id !in existingIds && pending.none { it.id == alarm.id }) {
                    pending.add(alarm)
                }
            }

            if (pending.isEmpty()) return@execute

            val submitRes = ScheduleApiClient.submitCandidates(
                baseUrl = bridgeUrl,
                token = token,
                candidates = pending
            )

            if (submitRes.isSuccess) {
                val resp = submitRes.getOrThrow()
                val uploadedIds = pending.map { it.id }.toSet()
                diskCache.removePendingCandidates(uploadedIds)

                // Merge updated items returned by bridge
                val updatedItems = resp.results.mapNotNull { it.item }
                if (updatedItems.isNotEmpty()) {
                    for (item in updatedItems) {
                        diskCache.saveItem(item)
                    }
                }
                notifyUpdated()
            } else {
                Log.w(TAG, "Failed to submit schedule candidates: ${submitRes.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Toggles an item to completed status.
     * Updates locally immediately (optimistic UI), synchronises to LocalAlarmStore if it's an alarm,
     * and persists to the bridge via POST /api/v1/schedules/{id}/action.
     */
    fun toggleComplete(scheduleId: String) {
        // 1. Dismiss local alarm if it belongs to Phase 2 alarm store
        val localAlarm = alarmStore.getAlarm(scheduleId)
        if (localAlarm != null) {
            AlarmScheduler.dismissAlarm(context, scheduleId)
        }

        // 2. Mark completed in local schedule cache
        val cached = diskCache.getItem(scheduleId)
        val nowSec = System.currentTimeMillis() / 1000.0
        val completedItem = if (cached != null) {
            ScheduleSyncLogic.toggleItemComplete(cached, nowSec)
        } else if (localAlarm != null) {
            ScheduleSyncLogic.toggleItemComplete(ScheduleItem.fromAlarmItem(localAlarm), nowSec)
        } else {
            null
        }

        if (completedItem != null) {
            diskCache.saveItem(completedItem)
            notifyUpdated()
        }

        // 3. Network push to bridge
        executor.execute {
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val token = AppConfig.getBridgeAuthTokenForUrl(context, bridgeUrl)
            if (bridgeUrl.isBlank()) {
                if (completedItem != null) diskCache.queueCandidate(completedItem)
                return@execute
            }

            val actionRes = ScheduleApiClient.executeAction(
                baseUrl = bridgeUrl,
                token = token,
                scheduleId = scheduleId,
                action = "complete"
            )

            if (actionRes.isSuccess) {
                val updated = actionRes.getOrThrow()
                diskCache.saveItem(updated)
                notifyUpdated()
            } else {
                Log.w(TAG, "Bridge toggle action failed, queueing for sync: ${actionRes.exceptionOrNull()?.message}")
                if (completedItem != null) {
                    diskCache.queueCandidate(completedItem)
                }
            }
        }
    }

    /**
     * Deletes a schedule item.
     * Deletes locally immediately (optimistic UI), cancels in AlarmManager if it's an alarm,
     * and propagates deletion to bridge via DELETE /api/v1/schedules/{id}.
     */
    fun deleteItem(scheduleId: String) {
        // 1. Cancel in local alarm engine if applicable
        if (alarmStore.getAlarm(scheduleId) != null) {
            AlarmScheduler.cancelAlarm(context, scheduleId)
            alarmStore.deleteAlarm(scheduleId)
        }

        // 2. Remove from local schedule cache
        diskCache.deleteItem(scheduleId)
        notifyUpdated()

        // 3. Propagate deletion to bridge
        executor.execute {
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val token = AppConfig.getBridgeAuthTokenForUrl(context, bridgeUrl)
            if (bridgeUrl.isBlank()) return@execute

            val delRes = ScheduleApiClient.deleteSchedule(
                baseUrl = bridgeUrl,
                token = token,
                scheduleId = scheduleId
            )
            if (delRes.isFailure) {
                Log.w(TAG, "Bridge delete schedule failed: ${delRes.exceptionOrNull()?.message}")
            }
        }
    }

    /**
     * Aggregates schedule items from local cache, Phase 2 alarm store, and Phase 4 countdown manager,
     * filters to today plus tomorrow only, and sorts by target time.
     */
    fun getTimelineItemsForBoard(nowMillis: Long = System.currentTimeMillis()): List<ScheduleItem> {
        val cachedItems = diskCache.getAllItems()
        val localAlarms = alarmStore.getAllAlarms().map { ScheduleItem.fromAlarmItem(it) }

        // Phase 4 Countdown timers
        val countdownState = CountdownManager.currentState
        val activeCountdowns = countdownState.activeTimers.map { ScheduleItem.fromCountdownItem(it) }
        val completedCountdown = countdownState.lastCompletedTimer?.let {
            listOf(ScheduleItem.fromCountdownItem(it))
        }.orEmpty()

        // Merge by id (terminal state domination and last-write-wins)
        val combined = mutableMapOf<String, ScheduleItem>()
        for (item in cachedItems) {
            combined[item.id] = item
        }
        for (alarm in localAlarms) {
            val existing = combined[alarm.id]
            if (existing == null) {
                combined[alarm.id] = alarm
            } else if (alarm.isTerminal && !existing.isTerminal) {
                combined[alarm.id] = alarm
            } else if (!existing.isTerminal && alarm.updatedAtUtc >= existing.updatedAtUtc) {
                combined[alarm.id] = alarm
            }
        }
        for (cd in activeCountdowns + completedCountdown) {
            combined[cd.id] = cd
        }

        // Filter to today plus tomorrow only
        return ScheduleSyncLogic.filterTodayAndTomorrow(
            items = combined.values.toList(),
            nowMillis = nowMillis
        )
    }

    private fun notifyUpdated() {
        val items = getTimelineItemsForBoard()
        mainHandler.post {
            listener?.onSchedulesUpdated(items)
        }
    }

    private fun connectWebSocket() {
        disconnectWebSocket()
        try {
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val trimmed = bridgeUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return

            val wsUrl = trimmed
                .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
                .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://") + "/ws"

            val uri = URI(wsUrl)
            wsClient = BridgeWebSocketClient(uri, object : BridgeWebSocketClient.Listener {
                override fun onOpen() {
                    Log.i(TAG, "Schedules WebSocket connected")
                }

                override fun onMessage(text: String) {
                    handleWsMessage(text)
                }

                override fun onClose(code: Int, reason: String) {
                    Log.i(TAG, "Schedules WebSocket closed: $code $reason")
                    wsClient = null
                }

                override fun onError(t: Throwable) {
                    Log.w(TAG, "Schedules WebSocket error: ${t.message}")
                    wsClient = null
                }
            })
            wsClient?.connect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initiate Schedules WebSocket connection: ${e.message}")
        }
    }

    private fun disconnectWebSocket() {
        try {
            wsClient?.close()
        } catch (_: Exception) {}
        wsClient = null
    }

    private fun handleWsMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            if (type == "schedule_update") {
                Log.d(TAG, "Received schedule_update WebSocket broadcast, pulling incremental snapshot")
                pullSnapshot(silent = true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse schedule WS message: $text", e)
        }
    }
}
