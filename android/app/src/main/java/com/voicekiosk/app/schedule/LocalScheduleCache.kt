package com.voicekiosk.app.schedule

import android.content.Context
import com.voicekiosk.app.alarm.PlainByteCodec
import com.voicekiosk.app.memory.ByteCodec
import com.voicekiosk.app.memory.KeystoreCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Thread-safe on-device persistent cache for schedules, monotonic cursor, and offline candidate queue.
 * Strictly mirrors the app's established storage pattern from LocalMemoryCache and LocalAlarmStore:
 * encrypted (or encoded) JSON with atomic write via temp file and rename.
 */
class LocalScheduleCache(
    private val file: File?,
    private val codec: ByteCodec = PlainByteCodec
) {
    private var snapshot = mutableListOf<ScheduleItem>()
    private var snapshotMs: Long = 0L
    private var cursor: String = ""
    private val pendingQueue = mutableListOf<ScheduleItem>()

    init {
        load()
    }

    @Synchronized
    fun getAllItems(): List<ScheduleItem> = snapshot.toList()

    @Synchronized
    fun getItem(id: String): ScheduleItem? = snapshot.firstOrNull { it.id == id }

    @Synchronized
    fun getCursor(): String = cursor

    @Synchronized
    fun setCursor(newCursor: String) {
        this.cursor = newCursor
        save()
    }

    @Synchronized
    fun getPendingCandidates(): List<ScheduleItem> = pendingQueue.toList()

    @Synchronized
    fun queueCandidate(item: ScheduleItem) {
        pendingQueue.removeAll { it.id == item.id }
        pendingQueue.add(item)
        while (pendingQueue.size > MAX_QUEUE) {
            pendingQueue.removeAt(0)
        }
        save()
    }

    @Synchronized
    fun removePendingCandidates(ids: Collection<String>) {
        if (pendingQueue.removeAll { it.id in ids }) {
            save()
        }
    }

    @Synchronized
    fun saveItem(item: ScheduleItem) {
        val idx = snapshot.indexOfFirst { it.id == item.id }
        if (idx >= 0) {
            snapshot[idx] = item
        } else {
            snapshot.add(item)
        }
        save()
    }

    @Synchronized
    fun deleteItem(id: String): Boolean {
        val removedFromSnap = snapshot.removeAll { it.id == id }
        val removedFromQueue = pendingQueue.removeAll { it.id == id }
        if (removedFromSnap || removedFromQueue) {
            save()
        }
        return removedFromSnap
    }

    @Synchronized
    fun applySnapshot(
        incoming: List<ScheduleItem>,
        deletedIds: Collection<String>,
        newCursor: String,
        reset: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        snapshot = ScheduleSyncLogic.mergeSnapshot(
            localItems = snapshot,
            incomingItems = incoming,
            deletedIds = deletedIds,
            reset = reset
        ).toMutableList()

        if (newCursor.isNotEmpty()) {
            this.cursor = newCursor
        }
        this.snapshotMs = nowMs
        save()
    }

    @Synchronized
    fun clearAll() {
        snapshot.clear()
        snapshotMs = 0L
        cursor = ""
        pendingQueue.clear()
        save()
    }

    private fun save() {
        val f = file ?: return
        val root = JSONObject().apply {
            put("v", 1)
            put("snapshot_ms", snapshotMs)
            put("cursor", cursor)

            val snapArr = JSONArray()
            snapshot.forEach { snapArr.put(it.toJsonObject()) }
            put("snapshot", snapArr)

            val queueArr = JSONArray()
            pendingQueue.forEach { queueArr.put(it.toJsonObject()) }
            put("pending_queue", queueArr)
        }

        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            val bytes = codec.encode(root.toString().toByteArray(Charsets.UTF_8))
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            // No Log here: android.util.Log throws in local unit tests.
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return

        try {
            val raw = codec.decode(f.readBytes())
            val root = JSONObject(String(raw, Charsets.UTF_8))
            snapshotMs = root.optLong("snapshot_ms", 0L)
            cursor = root.optString("cursor", "")

            snapshot.clear()
            val snapArr = root.optJSONArray("snapshot") ?: JSONArray()
            for (i in 0 until snapArr.length()) {
                val obj = snapArr.optJSONObject(i) ?: continue
                snapshot.add(ScheduleItem.fromJsonObject(obj))
            }

            pendingQueue.clear()
            val queueArr = root.optJSONArray("pending_queue") ?: JSONArray()
            for (i in 0 until queueArr.length()) {
                val obj = queueArr.optJSONObject(i) ?: continue
                pendingQueue.add(ScheduleItem.fromJsonObject(obj))
            }
        } catch (e: Exception) {
            // No Log here: android.util.Log throws in local unit tests.
            snapshot.clear()
            pendingQueue.clear()
            cursor = ""
            snapshotMs = 0L
        }
    }

    companion object {
        private const val FILE_NAME = "schedules/schedules_cache.bin"
        const val MAX_QUEUE = 100

        @Volatile
        private var defaultInstance: LocalScheduleCache? = null

        fun get(context: Context): LocalScheduleCache {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: run {
                    val file = File(context.noBackupFilesDir, FILE_NAME)
                    val codec = runCatching {
                        KeystoreCodec("voice_kiosk_schedule_cache_key_v1")
                    }.getOrDefault(PlainByteCodec)
                    LocalScheduleCache(file, codec).also { defaultInstance = it }
                }
            }
        }

        fun resetDefaultInstanceForTesting() {
            defaultInstance = null
        }
    }
}
