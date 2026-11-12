package com.voicekiosk.app.memory

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Encrypts / decrypts the cache file (AndroidKeyStore AES-GCM on the device). */
interface ByteCodec {
    fun encode(plain: ByteArray): ByteArray
    fun decode(stored: ByteArray): ByteArray
}

/**
 * The device's copy of the shared family memory (read-only snapshot from the bridge, which
 * stays the source of truth) plus the local queue: candidates waiting for upload and
 * candidates waiting for a voice confirmation. Persisted as one encrypted JSON file so it
 * survives restarts and works offline. Thread-safe.
 */
class LocalMemoryCache(private val file: File?, private val codec: ByteCodec) {
    private var snapshot: List<MemoryItem> = emptyList()
    private var snapshotMs: Long = 0L
    private var cursor: String = ""
    private val queue = mutableListOf<MemoryCandidate>()

    init { load() }

    @Synchronized fun snapshot(): List<MemoryItem> = snapshot
    @Synchronized fun cursor(): String = cursor
    @Synchronized fun snapshotAgeMs(now: Long): Long = if (snapshotMs == 0L) Long.MAX_VALUE else now - snapshotMs
    @Synchronized fun candidates(): List<MemoryCandidate> = queue.toList()

    @Synchronized
    fun setSnapshot(items: List<MemoryItem>, now: Long, cursor: String = "") {
        snapshot = items
        snapshotMs = now
        this.cursor = cursor
        save()
    }

    /** Applies an incremental (or reset) snapshot from the bridge and persists [MemorySnapshot.cursor]. */
    @Synchronized
    fun applySnapshot(snap: MemorySnapshot, now: Long) {
        snapshot = MemoryWire.merge(snapshot, snap)
        cursor = snap.cursor
        snapshotMs = now
        save()
    }

    /** Adds stored items (bridge answer) without waiting for the next snapshot. */
    @Synchronized
    fun mergeStored(items: List<MemoryItem>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.toSet()
        snapshot = items + snapshot.filter { it.id !in ids && !(it.key.isNotEmpty() && items.any { n -> n.key == it.key }) }
        save()
    }

    @Synchronized
    fun upsert(c: MemoryCandidate) {
        queue.removeAll { it.id == c.id }
        queue += c
        while (queue.size > MAX_QUEUE) queue.removeAt(0)
        save()
    }

    @Synchronized
    fun remove(ids: Collection<String>) {
        if (queue.removeAll { it.id in ids }) save()
    }

    @Synchronized
    fun clearAll() {
        snapshot = emptyList(); snapshotMs = 0L; cursor = ""; queue.clear(); save()
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject().apply {
            put("v", 2)
            put("snapshot_ms", snapshotMs)
            put("cursor", cursor)
            put("snapshot", JSONArray().apply {
                snapshot.forEach { put(JSONObject().put("id", it.id).put("key", it.key).put("content", it.content).put("updated_at", it.updatedAt)) }
            })
            put("queue", JSONArray().apply {
                queue.forEach { c ->
                    put(JSONObject().put("id", c.id).put("cat", c.fact.category.wire).put("content", c.fact.content)
                        .put("key", c.fact.key).put("src", c.fact.source.name).put("created", c.createdMs)
                        .put("state", c.state).put("asked", c.askedMs))
                }
            })
        }
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeBytes(codec.encode(o.toString().toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {
        }
    }

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        try {
            val o = JSONObject(String(codec.decode(f.readBytes()), Charsets.UTF_8))
            snapshotMs = o.optLong("snapshot_ms", 0L)
            cursor = o.optString("cursor", "")
            val s = o.optJSONArray("snapshot") ?: JSONArray()
            snapshot = (0 until s.length()).map { i ->
                val it = s.getJSONObject(i)
                MemoryItem(it.getLong("id"), it.optString("key"), it.getString("content"), it.optDouble("updated_at", 0.0))
            }
            val q = o.optJSONArray("queue") ?: JSONArray()
            for (i in 0 until q.length()) {
                val it = q.getJSONObject(i)
                val cat = MemoryCategory.fromWire(it.getString("cat")) ?: continue
                queue += MemoryCandidate(
                    it.getString("id"),
                    MemoryFact(cat, it.getString("content"), it.optString("key"), runCatching { MemoryFact.Source.valueOf(it.optString("src")) }.getOrDefault(MemoryFact.Source.RULE)),
                    it.optLong("created"), it.optString("state"), it.optLong("asked")
                )
            }
        } catch (_: Exception) {
            // unreadable (key lost after reinstall / restore): start empty, the bridge has the truth
            snapshot = emptyList(); snapshotMs = 0L; cursor = ""; queue.clear()
        }
    }

    companion object {
        const val MAX_QUEUE = 20
    }
}
