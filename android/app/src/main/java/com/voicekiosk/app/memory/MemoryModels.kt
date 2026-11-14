package com.voicekiosk.app.memory

/**
 * Categories of facts the device may extract (plan §5). The first five are low-risk and
 * are written without asking; the others become candidates the user confirms by voice.
 * Sensitive information (health, finance, IDs, credentials, phone/email, precise address
 * or location) has no category: it is never extracted ([SensitiveFilter]).
 *
 * [wire] matches nas-bridge DEVICE_MEMORY_*_CATEGORIES.
 */
enum class MemoryCategory(val wire: String, val autoWrite: Boolean, val label: String) {
    MUSIC("music", true, "音乐偏好"),
    NICKNAME("nickname", true, "称呼"),
    ROUTINE("routine", true, "作息"),
    DEVICE("device", true, "使用偏好"),
    CITY("city", true, "所在城市"),
    FAMILY("family", false, "家人/宠物称呼"),
    PREFERENCE("preference", false, "其他偏好");

    companion object {
        fun fromWire(s: String): MemoryCategory? = values().firstOrNull { it.wire == s }
    }
}

/** One extracted fact in its canonical wording (e.g. "喜欢听周杰伦的歌"). */
data class MemoryFact(
    val category: MemoryCategory,
    val content: String,
    /** Bridge memory key for single-valued facts (称呼, 所在城市, …); "" for multi-valued ones. */
    val key: String = "",
    /** Which extractor produced it. */
    val source: Source = Source.RULE,
    /** The user utterance it came from. */
    val evidence: String = ""
) {
    enum class Source { RULE, MODEL }
}

/** A memory item as stored by the bridge ([com.voicekiosk.app.memory.MemoryBackend.snapshot]). */
data class MemoryItem(val id: Long, val key: String, val content: String, val updatedAt: Double = 0.0)

/** Incremental snapshot from `GET /api/v1/memory/snapshot?since=`. */
data class MemorySnapshot(
    val items: List<MemoryItem>,
    val deletedIds: List<Long> = emptyList(),
    val cursor: String = "",
    val reset: Boolean = false
)

/** Waiting for upload (auto-written or confirmed) or for a voice confirmation. */
data class MemoryCandidate(
    val id: String,
    val fact: MemoryFact,
    val createdMs: Long,
    /** "auto" or "confirmed" once queued for upload; "pending" while waiting for a yes/no. */
    val state: String,
    val askedMs: Long = 0L
)

/** Per-candidate bridge answer (`POST /api/v1/memory/candidates`). */
data class SubmitResult(val id: String, val status: String, val item: MemoryItem? = null) {
    /** Stored (or already there): drop it from the queue and show it in the cache. */
    val stored: Boolean
        get() = status == "created" || status == "deduplicated" || status == "updated"
}

/** The bridge's long-term memory as the device sees it (device token only). */
interface MemoryBackend {
    fun snapshot(since: String = ""): Result<MemorySnapshot>
    fun submit(candidates: List<MemoryCandidate>): Result<List<SubmitResult>>
}
