package com.voicekiosk.app.memory

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Voice confirmation of non-low-risk candidates (used by the turn orchestrator). */
interface MemoryConfirmations {
    /** A pending candidate to ask about now, marked as asked; null if none / too soon. */
    fun nextToAsk(nowMs: Long): MemoryCandidate?
    fun answer(id: String, accepted: Boolean)
    /** Asked but not answered (other utterance, timeout, session end): dropped. */
    fun expire(id: String)
}

/**
 * On-device memory (plan §5, S5):
 *
 *     session ends → [onSessionEnded] (background, never on the reply path)
 *       → eligible utterances only ([MemoryRules.eligible]: first-person statements, no
 *         questions/requests/negations/hearsay, no sensitive content)
 *       → rule extraction (all tiers) + model extraction (HIGH tier, idle, not hot, bounded)
 *       → [MemoryRules.validate] (canonical wording, grounded, sensitive filter again)
 *       → dedupe against the cached bridge snapshot and the local queue
 *       → low-risk categories: upload as "auto"; others (or a low-risk fact that would
 *         change an existing keyed memory): wait for a voice "是/不用" ([MemoryConfirmations])
 *       → POST /api/v2/memory/candidates (device token); the bridge re-validates and stays the
 *         source of truth; GET /api/v2/memory/snapshot?since= refreshes the cache.
 */
class MemoryManager(
    private val backend: MemoryBackend,
    private val cache: LocalMemoryCache,
    /** Runs model extraction exclusively on the shared engine; null when unavailable (not HIGH tier, not loaded). */
    private val model: (() -> ((List<String>) -> ModelMemoryExtractor.Outcome)?) = { null },
    /** Idle and not thermally stressed. */
    private val canRunModel: () -> Boolean = { true },
    private val executor: Executor = DEFAULT_EXECUTOR,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val newId: () -> String = { "m" + java.util.UUID.randomUUID().toString().replace("-", "").take(16) }
) : MemoryConfirmations {

    data class Report(
        val facts: List<MemoryFact>,
        val auto: List<MemoryFact>,
        val pending: List<MemoryFact>,
        val known: List<MemoryFact>,
        val modelStatus: String,
        val modelMs: Long
    )

    @Volatile private var lastAskMs = 0L

    /** Background extraction for one finished conversation session. */
    fun onSessionEnded(utterances: List<String>) {
        if (utterances.none { MemoryRules.eligible(it) } && cache.candidates().none { it.state != STATE_PENDING }) return
        executor.execute {
            try {
                val r = process(utterances)
                if (r.facts.isNotEmpty() || r.modelStatus != "skipped") {
                    log("memory: ${r.facts.size} facts (auto ${r.auto.size}, confirm ${r.pending.size}, known ${r.known.size}), model ${r.modelStatus} ${r.modelMs}ms")
                }
            } catch (e: Exception) {
                log("memory extraction failed: ${e.message}")
            }
        }
    }

    /** Synchronous core of [onSessionEnded] (tests, eval). */
    fun process(utterances: List<String>, upload: Boolean = true): Report {
        val facts = extract(utterances)
        val now = clock()
        val auto = mutableListOf<MemoryFact>()
        val pending = mutableListOf<MemoryFact>()
        val known = mutableListOf<MemoryFact>()
        synchronized(this) {
            val snap = cache.snapshot()
            val queued = cache.candidates()
            for (f in facts.facts) {
                val n = MemoryRules.norm(f.content)
                if (snap.any { MemoryRules.norm(it.content) == n } || queued.any { MemoryRules.norm(it.fact.content) == n }) { known += f; continue }
                val conflict = f.key.isNotEmpty() && (snap.any { it.key == f.key } || queued.any { it.fact.key == f.key })
                if (f.category.autoWrite && !conflict) {
                    cache.upsert(MemoryCandidate(newId(), f, now, STATE_AUTO)); auto += f
                } else {
                    queued.filter { it.state == STATE_PENDING && it.fact.key.isNotEmpty() && it.fact.key == f.key }.forEach { cache.remove(listOf(it.id)) }
                    cache.upsert(MemoryCandidate(newId(), f, now, STATE_PENDING)); pending += f
                }
            }
            val pend = cache.candidates().filter { it.state == STATE_PENDING }
            val stale = pend.filter { now - it.createdMs > PENDING_TTL_MS } + pend.dropLast(MAX_PENDING)
            if (stale.isNotEmpty()) cache.remove(stale.map { it.id })
        }
        if (upload) flush()
        return Report(facts.facts, auto, pending, known, facts.status, facts.ms)
    }

    private data class Extracted(val facts: List<MemoryFact>, val status: String, val ms: Long)

    private fun extract(utterances: List<String>): Extracted {
        val eligible = utterances.filter { MemoryRules.eligible(it) }
        if (eligible.isEmpty()) return Extracted(emptyList(), "skipped", 0)
        val ruleFacts = eligible.flatMap { MemoryRules.extract(it) }
        // the model only looks at utterances the rules found nothing in
        val rest = eligible.filter { MemoryRules.extract(it).isEmpty() }
        var status = "skipped"
        var ms = 0L
        var modelFacts = emptyList<MemoryFact>()
        if (rest.isNotEmpty()) {
            val run = model()
            status = when {
                run == null -> "unavailable"
                !canRunModel() -> "deferred (busy/hot)"
                else -> {
                    val o = run(rest)
                    ms = o.ms
                    modelFacts = o.facts
                    o.status
                }
            }
        }
        val all = (ruleFacts + modelFacts).filter { !SensitiveFilter.isSensitive(it.content) }.distinctBy { MemoryRules.norm(it.content) }
        return Extracted(all, status, ms)
    }

    /** Uploads queued auto / confirmed candidates; failures stay queued for next time. */
    fun flush() {
        val ready = cache.candidates().filter { it.state == STATE_AUTO || it.state == STATE_CONFIRMED }
        for (batch in ready.chunked(MAX_PER_REQUEST)) {
            val res = backend.submit(batch).getOrElse { log("memory upload failed: ${it.message}"); return }
            val byId = res.associateBy { it.id }
            val done = batch.filter { byId[it.id] != null }
            cache.remove(done.map { it.id })
            cache.mergeStored(res.filter { it.stored }.mapNotNull { it.item })
            res.filter { !it.stored }.forEach { log("memory candidate ${it.status}: ${batch.firstOrNull { b -> b.id == it.id }?.fact?.category?.wire}") }
        }
    }

    /** Refreshes the cached snapshot when older than [maxAgeMs] (or always with 0). */
    fun refreshSnapshot(maxAgeMs: Long = SNAPSHOT_MAX_AGE_MS) {
        if (cache.snapshotAgeMs(clock()) < maxAgeMs) return
        backend.snapshot(cache.cursor()).onSuccess { cache.applySnapshot(it, clock()) }.onFailure { log("memory snapshot failed: ${it.message}") }
    }

    /** After a voice memory command (记住/忘掉/更正) went to the bridge: refresh in the background. */
    fun onMemoryCommand() = executor.execute { refreshSnapshot(0) }

    fun refreshInBackground() = executor.execute { refreshSnapshot(); flush() }

    // ---- confirmations -------------------------------------------------------------------

    @Synchronized
    override fun nextToAsk(nowMs: Long): MemoryCandidate? {
        if (nowMs - lastAskMs < ASK_SPACING_MS) return null
        val c = cache.candidates().firstOrNull { it.state == STATE_PENDING && it.askedMs == 0L && nowMs - it.createdMs <= PENDING_TTL_MS }
            ?: return null
        val asked = c.copy(askedMs = nowMs)
        cache.upsert(asked)
        lastAskMs = nowMs
        return asked
    }

    override fun answer(id: String, accepted: Boolean) {
        synchronized(this) {
            val c = cache.candidates().firstOrNull { it.id == id } ?: return
            if (accepted) cache.upsert(c.copy(state = STATE_CONFIRMED)) else cache.remove(listOf(id))
        }
        if (accepted) executor.execute { flush() }
    }

    @Synchronized
    override fun expire(id: String) = cache.remove(listOf(id))

    // ---- local personalisation (offline-capable) ------------------------------------------

    /** Known favourite artist: "最喜欢的歌手是X", else the newest "喜欢听X的歌". */
    fun favoriteArtist(): String? {
        val texts = cache.snapshot().map { it.content } +
            cache.candidates().filter { it.state != STATE_PENDING }.map { it.fact.content }
        texts.firstNotNullOfOrNull { Regex("^最喜欢的歌手是(.+)$").matchEntire(it)?.groupValues?.get(1) }?.let { return it }
        return texts.firstNotNullOfOrNull { Regex("^喜欢听(.+)的歌$").matchEntire(it)?.groupValues?.get(1) }
    }

    /** For the settings page: stored items and local candidates. */
    fun view(): Pair<List<MemoryItem>, List<MemoryCandidate>> = cache.snapshot() to cache.candidates()

    companion object {
        const val STATE_AUTO = "auto"
        const val STATE_CONFIRMED = "confirmed"
        const val STATE_PENDING = "pending"
        const val MAX_PER_REQUEST = 5
        const val MAX_PENDING = 3
        const val PENDING_TTL_MS = 24 * 3600_000L
        const val ASK_SPACING_MS = 2 * 60_000L
        const val SNAPSHOT_MAX_AGE_MS = 6 * 3600_000L

        private val DEFAULT_EXECUTOR: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VoiceKiosk-Memory").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        }

        private val YES = Regex("^(?:是|是的|对|对的|好|好的|好啊|可以|行|嗯|嗯嗯|要|记住|记住吧|记吧|没问题|当然|记一下)(?:的|啊|呀|吧|了)?$")
        private val NO = Regex("^(?:不用|不要|不|别|算了|不需要|不必|别记|不记|不用记|不要记|先不用)(?:了|啦|吧|啊)?$")

        /** true = yes, false = no, null = not an answer. */
        fun parseAnswer(text: String): Boolean? {
            val t = text.trim().replace(Regex("[\\s，,。.！!？?～~]+"), "")
            return when {
                YES.matches(t) -> true
                NO.matches(t) -> false
                else -> null
            }
        }
    }
}
