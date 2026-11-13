package com.voicekiosk.app.ondevice.route

import com.voicekiosk.app.ondevice.tools.LocalPlatform
import com.voicekiosk.app.ondevice.tools.MediaAction
import com.voicekiosk.app.ondevice.tools.PlaySearchResult
import java.time.ZoneId
import java.time.ZonedDateTime

class FakePlatform : LocalPlatform {
    var now: Long = ZonedDateTime.of(2026, 11, 10, 15, 5, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    var volIndex = 7
    var volMax = 15
    var mutedState = false
    val mediaActions = mutableListOf<MediaAction>()
    var mediaOk = true
    var playResult = PlaySearchResult.STARTED
    var customPlayerName: String? = null
    var lastSearch: Triple<String, String?, String?>? = null
    val timers = mutableListOf<Pair<Int, String?>>()
    var throwOnVolume = false

    override fun nowMillis() = now
    override fun zone(): ZoneId = ZoneId.of("Asia/Shanghai")
    override fun volume(): Pair<Int, Int> { if (throwOnVolume) throw IllegalStateException("boom"); return volIndex to volMax }
    override fun setVolumeIndex(index: Int) { volIndex = index }
    override fun setMuted(muted: Boolean) { mutedState = muted }
    override fun isMuted() = mutedState
    override fun sendMediaAction(action: MediaAction): Boolean { mediaActions += action; return mediaOk }
    override fun playFromSearch(query: String, artist: String?, song: String?): Pair<PlaySearchResult, String?> {
        lastSearch = Triple(query, artist, song)
        val name = when (playResult) {
            PlaySearchResult.STARTED -> customPlayerName ?: "QQ音乐"
            PlaySearchResult.UNINSTALLED_FALLBACK -> customPlayerName ?: "网易云音乐"
            PlaySearchResult.BACKGROUND_NOT_SUPPORTED -> customPlayerName
            PlaySearchResult.FAILED -> customPlayerName
            PlaySearchResult.NO_PLAYER -> null
        }
        return playResult to name
    }
    override fun startTimer(seconds: Int, label: String?): Boolean { timers += seconds to label; return true }
    override fun cancelTimers(): Int { val n = timers.size; timers.clear(); return n }
}
