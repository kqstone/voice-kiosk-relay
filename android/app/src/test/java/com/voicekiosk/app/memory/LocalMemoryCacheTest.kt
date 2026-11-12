package com.voicekiosk.app.memory

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class LocalMemoryCacheTest {
    private val xor = object : ByteCodec {
        override fun encode(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() }
        override fun decode(stored: ByteArray) = encode(stored)
    }

    @Test
    fun persistsSnapshotAndQueueEncoded() {
        val f = File.createTempFile("memcache", ".bin").apply { delete(); deleteOnExit() }
        val a = LocalMemoryCache(f, xor)
        a.setSnapshot(listOf(MemoryItem(1, "称呼", "希望被称呼为小明")), 42)
        a.upsert(MemoryCandidate("c1", MemoryFact(MemoryCategory.FAMILY, "女儿叫朵朵", "称呼·女儿"), 7, "pending", 9))
        assertEquals(false, String(f.readBytes(), Charsets.UTF_8).contains("小明"))
        val b = LocalMemoryCache(f, xor)
        assertEquals(a.snapshot(), b.snapshot())
        assertEquals(a.candidates(), b.candidates())
        assertEquals(8, b.snapshotAgeMs(50))
    }

    @Test
    fun unreadableFileStartsEmpty() {
        val f = File.createTempFile("memcache", ".bin").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
        val c = LocalMemoryCache(f, IdentityCodec)
        assertEquals(0, c.snapshot().size)
        assertEquals(Long.MAX_VALUE, c.snapshotAgeMs(1))
    }

    @Test
    fun mergeStoredReplacesSameKey() {
        val c = LocalMemoryCache(null, IdentityCodec)
        c.setSnapshot(listOf(MemoryItem(1, "所在城市", "住在杭州"), MemoryItem(2, "", "喜欢喝绿茶")), 1)
        c.mergeStored(listOf(MemoryItem(1, "所在城市", "住在成都")))
        assertEquals(listOf("住在成都", "喜欢喝绿茶"), c.snapshot().map { it.content })
    }

    @Test
    fun persistCursorAndApplyDeletions() {
        val f = File.createTempFile("memcache", ".bin").apply { delete(); deleteOnExit() }
        val a = LocalMemoryCache(f, xor)
        a.setSnapshot(listOf(MemoryItem(1, "称呼", "希望被称呼为小明"), MemoryItem(2, "", "喜欢喝绿茶")), 10, cursor = "5")
        assertEquals("5", a.cursor())
        a.applySnapshot(
            MemorySnapshot(
                items = listOf(MemoryItem(3, "", "喜欢听周杰伦的歌")),
                deletedIds = listOf(2L),
                cursor = "9",
                reset = false
            ),
            now = 20
        )
        assertEquals("9", a.cursor())
        assertEquals(listOf("喜欢听周杰伦的歌", "希望被称呼为小明"), a.snapshot().map { it.content })
        val b = LocalMemoryCache(f, xor)
        assertEquals("9", b.cursor())
        assertEquals(a.snapshot(), b.snapshot())
    }

    @Test
    fun queueIsBounded() {
        val c = LocalMemoryCache(null, IdentityCodec)
        repeat(LocalMemoryCache.MAX_QUEUE + 5) { c.upsert(MemoryCandidate("c$it", MemoryFact(MemoryCategory.MUSIC, "喜欢听X${it}的歌"), 0, "auto")) }
        assertEquals(LocalMemoryCache.MAX_QUEUE, c.candidates().size)
        assertEquals("c5", c.candidates().first().id)
    }
}
