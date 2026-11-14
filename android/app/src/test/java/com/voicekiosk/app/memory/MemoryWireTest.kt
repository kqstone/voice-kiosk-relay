package com.voicekiosk.app.memory

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryWireTest {
    @Test
    fun endpointPathsUseV1() {
        assertEquals("/api/v1/memory/snapshot", MemoryWire.SNAPSHOT_PATH)
        assertEquals("/api/v1/memory/candidates", MemoryWire.CANDIDATES_PATH)
    }

    @Test
    fun requestAndResults() {
        val req = MemoryWire.request(listOf(
            MemoryCandidate("a", MemoryFact(MemoryCategory.CITY, "住在杭州", "所在城市"), 0, "auto"),
            MemoryCandidate("b", MemoryFact(MemoryCategory.FAMILY, "女儿叫朵朵"), 0, "confirmed")
        )).getJSONArray("candidates")
        assertEquals("auto", req.getJSONObject(0).getString("origin"))
        assertEquals("所在城市", req.getJSONObject(0).getString("key"))
        assertEquals("confirmed", req.getJSONObject(1).getString("origin"))
        assertFalse(req.getJSONObject(1).has("key"))
        val res = MemoryWire.results(JSONObject("""{"results":[{"id":"a","status":"created","item":{"id":5,"key":"所在城市","content":"住在杭州"}},{"id":"b","status":"rejected_sensitive"}],"changed":true}"""))
        assertEquals(listOf(true, false), res.map { it.stored })
        assertEquals(MemoryItem(5, "所在城市", "住在杭州"), res[0].item)
        assertEquals(listOf("住在杭州"), MemoryWire.items(JSONObject("""{"items":[{"id":5,"key":"所在城市","content":"住在杭州"},{"id":6,"content":""}]}""").getJSONArray("items")).map { it.content })
    }

    @Test
    fun snapshotParsesCursorDeletionsAndReset() {
        val full = MemoryWire.snapshot(JSONObject("""{"items":[{"id":1,"key":"称呼","content":"希望被称呼为小明"}],"deleted_ids":[],"cursor":"12","reset":true}"""))
        assertTrue(full.reset)
        assertEquals("12", full.cursor)
        assertEquals(listOf(1L), full.items.map { it.id })
        assertEquals(emptyList<Long>(), full.deletedIds)

        val incr = MemoryWire.snapshot(JSONObject("""{"items":[{"id":2,"key":"","content":"喜欢听周杰伦的歌"}],"deleted_ids":[1,9],"cursor":"15","reset":false}"""))
        assertFalse(incr.reset)
        assertEquals(listOf(1L, 9L), incr.deletedIds)
        assertEquals("15", incr.cursor)
    }

    @Test
    fun mergeAppliesDeletionsAndReplacesOnReset() {
        val existing = listOf(
            MemoryItem(1, "称呼", "希望被称呼为小明"),
            MemoryItem(2, "", "喜欢喝绿茶"),
            MemoryItem(3, "所在城市", "住在杭州")
        )
        val incremental = MemorySnapshot(
            items = listOf(MemoryItem(3, "所在城市", "住在成都"), MemoryItem(4, "", "喜欢听周杰伦的歌")),
            deletedIds = listOf(2L),
            cursor = "20",
            reset = false
        )
        val merged = MemoryWire.merge(existing, incremental)
        assertEquals(listOf("住在成都", "喜欢听周杰伦的歌", "希望被称呼为小明"), merged.map { it.content })

        val reset = MemorySnapshot(
            items = listOf(MemoryItem(9, "", "喜欢听林俊杰的歌")),
            deletedIds = listOf(1L, 2L, 3L),
            cursor = "1",
            reset = true
        )
        assertEquals(listOf("喜欢听林俊杰的歌"), MemoryWire.merge(existing, reset).map { it.content })
    }
}
