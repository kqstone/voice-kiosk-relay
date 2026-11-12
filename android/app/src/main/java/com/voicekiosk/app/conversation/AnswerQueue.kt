package com.voicekiosk.app.conversation

/**
 * Bounded offline queue of question answers. Entries expire with the question
 * ([QueuedAnswer.expiresAtMs]); the queue never grows past [maxSize] (oldest dropped).
 */
class AnswerQueue(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSize: Int = MAX_SIZE
) {
    data class QueuedAnswer(
        val questionId: String,
        val option: String? = null,
        val text: String? = null,
        val expiresAtMs: Long,
        val queuedAtMs: Long
    )

    private val items = ArrayDeque<QueuedAnswer>()

    @Synchronized
    fun enqueue(
        questionId: String,
        option: String?,
        text: String?,
        expiresAtMs: Long
    ): Boolean {
        val id = questionId.trim()
        if (id.isEmpty()) return false
        val now = clock()
        pruneLocked(now)
        if (expiresAtMs > 0 && expiresAtMs <= now) return false
        items.removeAll { it.questionId == id }
        val exp = if (expiresAtMs > 0) expiresAtMs else now + FALLBACK_TTL_MS
        items.addLast(
            QueuedAnswer(
                questionId = id,
                option = option?.trim()?.ifEmpty { null },
                text = text?.trim()?.ifEmpty { null },
                expiresAtMs = exp,
                queuedAtMs = now
            )
        )
        while (items.size > maxSize) items.removeFirst()
        return true
    }

    @Synchronized
    fun pending(): List<QueuedAnswer> {
        pruneLocked(clock())
        return items.toList()
    }

    /** Remove and return non-expired entries (caller posts them). */
    @Synchronized
    fun takeReady(): List<QueuedAnswer> {
        pruneLocked(clock())
        val out = items.toList()
        items.clear()
        return out
    }

    @Synchronized
    fun remove(questionId: String) {
        items.removeAll { it.questionId == questionId }
    }

    @Synchronized
    fun size(): Int {
        pruneLocked(clock())
        return items.size
    }

    private fun pruneLocked(now: Long) {
        items.removeAll { it.expiresAtMs in 1 until now }
    }

    companion object {
        const val MAX_SIZE = 8
        const val FALLBACK_TTL_MS = 30 * 60_000L
    }
}
