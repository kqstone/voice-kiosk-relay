package com.voicekiosk.app.alarm

import android.content.Context
import android.util.Log
import com.voicekiosk.app.memory.ByteCodec
import com.voicekiosk.app.memory.KeystoreCodec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Identity codec for plain byte handling, used in unit tests or when keystore is disabled. */
object PlainByteCodec : ByteCodec {
    override fun encode(plain: ByteArray): ByteArray = plain
    override fun decode(stored: ByteArray): ByteArray = stored
}

/**
 * Thread-safe on-device persistent cache for scheduled alarms and items.
 * Strictly mirrors the app's established storage pattern from LocalMemoryCache:
 * encrypted (or encoded) JSON with atomic write via temp file and rename.
 */
class LocalAlarmStore(
    private val file: File?,
    private val codec: ByteCodec = PlainByteCodec
) {
    private val alarms = mutableListOf<AlarmItem>()

    init {
        load()
    }

    @Synchronized
    fun getAllAlarms(): List<AlarmItem> = alarms.toList()

    @Synchronized
    fun getActiveAlarms(): List<AlarmItem> = alarms.filter { it.status == AlarmItem.STATUS_ACTIVE }

    @Synchronized
    fun getAlarm(id: String): AlarmItem? = alarms.firstOrNull { it.id == id }

    @Synchronized
    fun saveAlarm(item: AlarmItem) {
        val index = alarms.indexOfFirst { it.id == item.id }
        if (index >= 0) {
            alarms[index] = item
        } else {
            alarms.add(item)
        }
        save()
    }

    @Synchronized
    fun updateStatus(
        id: String,
        status: String,
        updatedAtUtc: Double = System.currentTimeMillis() / 1000.0
    ): AlarmItem? {
        val index = alarms.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = alarms[index]
        val updated = current.copy(status = status, updatedAtUtc = updatedAtUtc)
        alarms[index] = updated
        save()
        return updated
    }

    @Synchronized
    fun deleteAlarm(id: String): Boolean {
        val removed = alarms.removeAll { it.id == id }
        if (removed) save()
        return removed
    }

    @Synchronized
    fun clearAll() {
        alarms.clear()
        save()
    }

    private fun save() {
        val f = file ?: return
        val root = JSONObject().apply {
            put("v", 1)
            put("saved_at", System.currentTimeMillis())
            val arr = JSONArray()
            alarms.forEach { arr.put(it.toJsonObject()) }
            put("alarms", arr)
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
            Log.e(TAG, "Failed to save alarm store to ${f.path}", e)
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return

        try {
            val raw = codec.decode(f.readBytes())
            val root = JSONObject(String(raw, Charsets.UTF_8))
            val arr = root.optJSONArray("alarms") ?: JSONArray()
            alarms.clear()
            for (i in 0 until arr.length()) {
                val itemObj = arr.optJSONObject(i) ?: continue
                alarms.add(AlarmItem.fromJsonObject(itemObj))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable alarm store file, starting empty", e)
            alarms.clear()
        }
    }

    companion object {
        private const val TAG = "LocalAlarmStore"
        private const val FILE_NAME = "alarms/alarms_cache.bin"

        @Volatile
        private var defaultInstance: LocalAlarmStore? = null

        fun get(context: Context): LocalAlarmStore {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: run {
                    val file = File(context.noBackupFilesDir, FILE_NAME)
                    val codec = runCatching {
                        KeystoreCodec("voice_kiosk_alarm_cache_key_v1")
                    }.getOrDefault(PlainByteCodec)
                    LocalAlarmStore(file, codec).also { defaultInstance = it }
                }
            }
        }

        fun resetDefaultInstanceForTesting() {
            defaultInstance = null
        }
    }
}
