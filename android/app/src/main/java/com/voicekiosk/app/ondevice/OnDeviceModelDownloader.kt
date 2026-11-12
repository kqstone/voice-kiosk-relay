package com.voicekiosk.app.ondevice

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads catalog GGUF models into the app's private `filesDir/llm/` (same pattern as
 * AsrModelDownloader: private dir, `.part` temp file, SHA256 verification, fallback URLs).
 *
 * - Resumable: an existing `.part` is continued with an HTTP Range request; servers that
 *   ignore Range (HTTP 200) restart from zero.
 * - A finished file is only renamed into place after its SHA256 matches the catalog.
 * - Models whose hash is UNVERIFIED are refused unless [allowUnverified] (debug benchmark).
 * - After a successful verification a small `.verified` sidecar (size + mtime + sha) avoids
 *   re-hashing gigabytes on every screen open; any change to the file invalidates it.
 * - Blocking; run on a background thread. [cancel] aborts at the next buffer.
 */
class OnDeviceModelDownloader(
    private val modelDir: File,
    private val allowUnverified: Boolean = false
) {
    interface Listener {
        fun onProgress(model: OnDeviceModel, downloadedBytes: Long, totalBytes: Long) {}
        fun onStatus(model: OnDeviceModel, message: String) {}
    }

    sealed class Result {
        data class Ready(val file: File) : Result()
        data class Failed(val reason: String) : Result()
        object Cancelled : Result()
    }

    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    fun fileFor(model: OnDeviceModel) = File(modelDir, model.fileName)

    private fun partFor(model: OnDeviceModel) = File(modelDir, model.fileName + ".part")

    private fun sidecarFor(model: OnDeviceModel) = File(modelDir, model.fileName + ".verified")

    /** Fast check (sidecar only, no hashing). */
    fun isReadyFast(model: OnDeviceModel): Boolean {
        val f = fileFor(model)
        return f.isFile && f.length() == model.sizeBytes && sidecarMatches(model, f)
    }

    fun delete(model: OnDeviceModel) {
        fileFor(model).delete()
        partFor(model).delete()
        sidecarFor(model).delete()
    }

    fun ensure(model: OnDeviceModel, listener: Listener? = null): Result {
        cancelled.set(false)
        OnDeviceModelCatalog.usabilityError(model, allowUnverified)?.let { return Result.Failed(it) }
        if (!modelDir.exists() && !modelDir.mkdirs()) return Result.Failed("无法创建模型目录")

        val target = fileFor(model)
        if (target.isFile) {
            if (isReadyFast(model)) return Result.Ready(target)
            listener?.onStatus(model, "正在校验已有文件…")
            if (verifyAndMark(model, target)) return Result.Ready(target)
            Log.w(TAG, "existing ${model.fileName} failed verification; re-downloading")
            target.delete()
            sidecarFor(model).delete()
        }

        val part = partFor(model)
        val needed = model.sizeBytes - (if (part.isFile) part.length() else 0L)
        val free = try { StatFs(modelDir.absolutePath).availableBytes } catch (_: Exception) { Long.MAX_VALUE }
        if (free < needed + DeviceTierDetector.STORAGE_HEADROOM_BYTES) {
            return Result.Failed("存储空间不足：需要约 ${DeviceTierDetector.fmtGiB(needed + DeviceTierDetector.STORAGE_HEADROOM_BYTES)}，可用 ${DeviceTierDetector.fmtGiB(free)}")
        }

        var lastError = "所有下载地址均失败"
        for (url in model.urls) {
            if (cancelled.get()) return Result.Cancelled
            listener?.onStatus(model, "下载中：${hostOf(url)}")
            when (val r = downloadResumable(model, url, part, listener)) {
                DownloadOutcome.Complete -> {
                    listener?.onStatus(model, "正在校验 SHA256…")
                    if (!matchesExpected(model, part)) {
                        lastError = "SHA256 校验失败（${hostOf(url)}）"
                        Log.w(TAG, "$lastError for ${model.fileName}")
                        part.delete()
                        continue
                    }
                    target.delete()
                    if (!part.renameTo(target)) return Result.Failed("重命名失败")
                    writeSidecar(model, target)
                    return Result.Ready(target)
                }
                DownloadOutcome.Cancelled -> return Result.Cancelled
                is DownloadOutcome.Error -> {
                    lastError = r.reason
                    Log.w(TAG, "download ${model.fileName} from $url failed: ${r.reason}")
                }
            }
        }
        return Result.Failed(lastError)
    }

    private sealed class DownloadOutcome {
        object Complete : DownloadOutcome()
        object Cancelled : DownloadOutcome()
        data class Error(val reason: String) : DownloadOutcome()
    }

    private fun downloadResumable(
        model: OnDeviceModel,
        startUrl: String,
        part: File,
        listener: Listener?
    ): DownloadOutcome {
        var existing = if (part.isFile) part.length() else 0L
        if (existing > model.sizeBytes) {
            part.delete()
            existing = 0L
        }
        if (existing == model.sizeBytes) return DownloadOutcome.Complete

        var conn: HttpURLConnection? = null
        try {
            var url = startUrl
            var redirects = 0
            while (true) {
                if (!url.startsWith("https://")) return DownloadOutcome.Error("拒绝非 HTTPS 重定向")
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "VoiceKiosk-LLM-Bench/1")
                    if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank() || ++redirects > MAX_REDIRECTS) return DownloadOutcome.Error("重定向异常")
                    url = URL(URL(url), loc).toString()
                    continue
                }
                when (code) {
                    HttpURLConnection.HTTP_OK -> {
                        if (existing > 0) {
                            Log.i(TAG, "server ignored Range; restarting ${model.fileName}")
                            existing = 0L
                        }
                    }
                    HttpURLConnection.HTTP_PARTIAL -> Unit
                    416 -> { // range not satisfiable: local part is bogus
                        part.delete()
                        return DownloadOutcome.Error("HTTP 416，已清除临时文件")
                    }
                    else -> return DownloadOutcome.Error("HTTP $code")
                }
                break
            }

            val c = conn!!
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(existing)
                raf.seek(existing)
                c.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = existing
                    var lastReport = 0L
                    while (true) {
                        if (cancelled.get()) return DownloadOutcome.Cancelled
                        val n = input.read(buf)
                        if (n < 0) break
                        if (done + n > model.sizeBytes) return DownloadOutcome.Error("服务器返回的数据超出预期大小")
                        raf.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 4L * 1024 * 1024 || done == model.sizeBytes) {
                            lastReport = done
                            listener?.onProgress(model, done, model.sizeBytes)
                        }
                    }
                    if (done != model.sizeBytes) return DownloadOutcome.Error("下载中断（${done}/${model.sizeBytes}），可重试续传")
                }
            }
            return DownloadOutcome.Complete
        } catch (e: Exception) {
            return DownloadOutcome.Error("${e.javaClass.simpleName}: ${e.message}（可重试续传）")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun matchesExpected(model: OnDeviceModel, file: File): Boolean {
        if (file.length() != model.sizeBytes) return false
        val expected = model.sha256
        if (expected == null) {
            // Only reachable for UNVERIFIED entries in debug benchmark mode.
            Log.w(TAG, "${model.fileName}: no expected SHA256; accepted because allowUnverified=true")
            return allowUnverified
        }
        return sha256Hex(file).equals(expected, ignoreCase = true)
    }

    private fun verifyAndMark(model: OnDeviceModel, file: File): Boolean {
        if (!matchesExpected(model, file)) return false
        writeSidecar(model, file)
        return true
    }

    private fun sidecarLine(model: OnDeviceModel, file: File) =
        "${model.sha256 ?: "none"} ${file.length()} ${file.lastModified()}"

    private fun writeSidecar(model: OnDeviceModel, file: File) {
        try { sidecarFor(model).writeText(sidecarLine(model, file)) } catch (_: Exception) {}
    }

    private fun sidecarMatches(model: OnDeviceModel, file: File): Boolean = try {
        model.sha256 != null && sidecarFor(model).readText().trim() == sidecarLine(model, file)
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "OnDeviceModelDl"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MAX_REDIRECTS = 8

        fun defaultDir(context: Context) = File(context.filesDir, "llm")

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = fis.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun hostOf(url: String) = try { URL(url).host } catch (_: Exception) { "?" }
    }
}
