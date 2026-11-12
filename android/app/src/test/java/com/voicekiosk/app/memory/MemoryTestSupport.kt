package com.voicekiosk.app.memory

import java.util.concurrent.Executor

object IdentityCodec : ByteCodec {
    override fun encode(plain: ByteArray) = plain
    override fun decode(stored: ByteArray) = stored
}

object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

/** In-memory bridge: auto/confirmed rules like nas-bridge handle_device_memory_request. */
class FakeBackend : MemoryBackend {
    val items = mutableListOf<MemoryItem>()
    val submitted = mutableListOf<MemoryCandidate>()
    var fail = false
    private var nextId = 1L

    override fun snapshot(since: String): Result<MemorySnapshot> =
        if (fail) Result.failure(IllegalStateException("offline"))
        else Result.success(MemorySnapshot(items.toList(), emptyList(), cursor = "1", reset = true))

    override fun submit(candidates: List<MemoryCandidate>): Result<List<SubmitResult>> {
        if (fail) return Result.failure(IllegalStateException("offline"))
        submitted += candidates
        return Result.success(candidates.map { c ->
            val confirmed = c.state == MemoryManager.STATE_CONFIRMED
            if (!confirmed && !c.fact.category.autoWrite) return@map SubmitResult(c.id, "rejected_needs_confirmation")
            items.firstOrNull { it.content == c.fact.content }?.let { return@map SubmitResult(c.id, "deduplicated", it) }
            val same = items.indexOfFirst { c.fact.key.isNotEmpty() && it.key == c.fact.key }
            if (same >= 0) {
                if (!confirmed) return@map SubmitResult(c.id, "skipped_conflict")
                val u = items[same].copy(content = c.fact.content)
                items[same] = u
                return@map SubmitResult(c.id, "updated", u)
            }
            val it = MemoryItem(nextId++, c.fact.key, c.fact.content)
            items += it
            SubmitResult(c.id, "created", it)
        })
    }
}
