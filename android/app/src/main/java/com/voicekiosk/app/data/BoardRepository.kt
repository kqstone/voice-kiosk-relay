package com.voicekiosk.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.bridge.BridgeWebSocketClient
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executors

/**
 * Repository orchestrating board discovery, synchronization, disk caching, and two-way toggle sync.
 *
 * - Loads cached boards immediately on start for instant offline rendering.
 * - Subscribes to relay bridge WebSocket /ws for real-time board_update broadcasts.
 * - Polls GET /api/v1/boards every 60 seconds as a fallback.
 * - Handles optimistic two-way check-off sync via POST /api/v1/boards/{board_id}/items/{item_id}/toggle.
 * - Zero domain, subject, person names, or board IDs hardcoded.
 * - Zero estimated-time fields anywhere.
 */
class BoardRepository(
    private val context: Context,
    private val diskCache: BoardDiskCache = BoardDiskCache(context)
) {
    companion object {
        private const val TAG = "BoardRepository"
        private const val FALLBACK_POLL_INTERVAL_MS = 60_000L
    }

    interface Listener {
        fun onBoardsUpdated(boards: List<BoardModel>)
        fun onBoardUpdated(board: BoardModel)
    }

    private var listener: Listener? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentBoards: List<BoardModel> = emptyList()

    @Volatile
    private var isStarted = false

    private var wsClient: BridgeWebSocketClient? = null

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isStarted) return
            refreshAllBoards(silent = true)
            mainHandler.postDelayed(this, FALLBACK_POLL_INTERVAL_MS)
        }
    }

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun start() {
        if (isStarted) return
        isStarted = true

        // 1. Load cached boards immediately for instant offline render
        executor.execute {
            val cached = diskCache.loadAllBoards()
            if (cached.isNotEmpty()) {
                currentBoards = cached
                notifyBoardsUpdated(cached)
            }
            // 2. Fetch fresh boards from bridge
            refreshAllBoards(silent = false)
            // 3. Connect WebSocket for realtime board_update broadcasts
            connectWebSocket()
        }

        // 4. Schedule 60-second periodic fallback polling
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
            refreshAllBoards(silent = false)
            if (wsClient == null) {
                connectWebSocket()
            }
        }
    }

    fun refreshAllBoards(silent: Boolean = false) {
        executor.execute {
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val token = AppConfig.getBridgeAuthTokenForUrl(context, bridgeUrl)
            val idsResult = BoardApiClient.fetchBoardIds(bridgeUrl, token)

            if (idsResult.isSuccess) {
                val ids = idsResult.getOrThrow()
                val fetchedBoards = mutableListOf<BoardModel>()
                for (id in ids) {
                    val boardRes = BoardApiClient.fetchBoard(bridgeUrl, token, id)
                    if (boardRes.isSuccess) {
                        val (model, rawJson) = boardRes.getOrThrow()
                        fetchedBoards.add(model)
                        diskCache.saveBoard(id, rawJson)
                    }
                }
                diskCache.saveManifest(ids)
                currentBoards = fetchedBoards
                notifyBoardsUpdated(fetchedBoards)
            } else {
                if (!silent) {
                    Log.w(TAG, "Failed to fetch board IDs from bridge: ${idsResult.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun refreshBoard(boardId: String) {
        executor.execute {
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val token = AppConfig.getBridgeAuthTokenForUrl(context, bridgeUrl)
            val boardRes = BoardApiClient.fetchBoard(bridgeUrl, token, boardId)
            if (boardRes.isSuccess) {
                val (model, rawJson) = boardRes.getOrThrow()
                diskCache.saveBoard(boardId, rawJson)

                val existing = currentBoards.toMutableList()
                val idx = existing.indexOfFirst { it.boardId == boardId }
                if (idx != -1) {
                    existing[idx] = model
                    currentBoards = existing
                    notifyBoardUpdated(model)
                } else {
                    existing.add(model)
                    currentBoards = existing
                    diskCache.saveManifest(existing.map { it.boardId })
                    notifyBoardsUpdated(existing)
                }
            } else {
                Log.w(TAG, "Failed to fetch updated board $boardId: ${boardRes.exceptionOrNull()?.message}")
            }
        }
    }

    fun toggleItem(boardId: String, itemId: String, completed: Boolean) {
        executor.execute {
            // 1. Update in-memory state
            val existing = currentBoards.toMutableList()
            val bIdx = existing.indexOfFirst { it.boardId == boardId }
            if (bIdx != -1) {
                val b = existing[bIdx]
                val updatedComps = b.components.map { comp ->
                    if (comp is BoardComponent.Checklist) {
                        val newItems = comp.items.map { itm ->
                            if (itm.id == itemId) itm.copy(completed = completed) else itm
                        }
                        BoardComponent.Checklist(newItems)
                    } else comp
                }
                // Recalculate badge
                val allChecklistItems = updatedComps.filterIsInstance<BoardComponent.Checklist>().flatMap { it.items }
                val total = allChecklistItems.size
                val compCount = allChecklistItems.count { it.completed }
                val newBadge = if (total > 0) "$compCount / $total 完成" else ""
                val updatedBoard = b.copy(
                    titleComponent = b.titleComponent.copy(badge = newBadge),
                    components = updatedComps
                )
                existing[bIdx] = updatedBoard
                currentBoards = existing
                // Save to disk cache
                updateAndSaveCachedBoard(boardId, itemId, completed)
            }

            // 2. Network sync: POST /api/v1/boards/{board_id}/items/{item_id}/toggle
            val bridgeUrl = AppConfig.getBridgeUrl(context)
            val token = AppConfig.getBridgeAuthTokenForUrl(context, bridgeUrl)
            val toggleResult = BoardApiClient.toggleItem(bridgeUrl, token, boardId, itemId, completed)
            if (toggleResult.isFailure) {
                Log.w(TAG, "Failed to toggle item $itemId on bridge: ${toggleResult.exceptionOrNull()?.message}")
            }
        }
    }

    private fun updateAndSaveCachedBoard(boardId: String, itemId: String, completed: Boolean) {
        try {
            val cachedFile = diskCache.loadBoard(boardId) ?: return
            val raw = cachedFile.rawJson
            if (raw.isNotEmpty()) {
                val root = JSONObject(raw)
                val dataObj = root.optJSONObject("data") ?: root
                val candidateKeys = listOf("tasks", "items", "checklist", "todo", "content", "list")
                for (key in candidateKeys) {
                    val arr = dataObj.optJSONArray(key) ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val id = item.optString("id", "item_$i")
                        if (id == itemId) {
                            item.put("completed", completed)
                            item.put("done", completed)
                            break
                        }
                    }
                }
                diskCache.saveBoard(boardId, root.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cached board JSON", e)
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
                    Log.i(TAG, "Bridge WebSocket connected")
                }

                override fun onMessage(text: String) {
                    handleWsMessage(text)
                }

                override fun onClose(code: Int, reason: String) {
                    Log.i(TAG, "Bridge WebSocket closed: $code $reason")
                    wsClient = null
                }

                override fun onError(t: Throwable) {
                    Log.w(TAG, "Bridge WebSocket error: ${t.message}")
                    wsClient = null
                }
            })
            wsClient?.connect()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initiate WebSocket connection: ${e.message}")
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
            if (type == "board_update") {
                val boardId = json.optString("board_id")
                if (boardId.isNotEmpty()) {
                    refreshBoard(boardId)
                } else {
                    refreshAllBoards(silent = false)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse WS message: $text", e)
        }
    }

    private fun notifyBoardsUpdated(boards: List<BoardModel>) {
        mainHandler.post {
            listener?.onBoardsUpdated(boards)
        }
    }

    private fun notifyBoardUpdated(board: BoardModel) {
        mainHandler.post {
            listener?.onBoardUpdated(board)
        }
    }
}
