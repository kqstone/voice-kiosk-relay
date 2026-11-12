package com.voicekiosk.app

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * First-launch downloader and SHA256 verifier for the Sherpa-ONNX streaming ASR model.
 *
 * Per docs/SPEC-v1.md §0 & §4, the >200MB class ASR model is NOT bundled in the APK.
 * It is downloaded on first launch to the app's private filesDir (`filesDir/models/<ASR_MODEL_NAME>`)
 * with progress reported to the dashboard via [WakeWordEventBus].
 *
 * Note on model identifier:
 * - Task specification names `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-01` (UNVERIFIED exact date string in upstream; returns HTTP 404 on GitHub/ModelScope).
 * - Upstream k2-fsa / ModelScope canonical name for the 2023-02 bilingual streaming Zipformer transducer is `sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`.
 * - This downloader checks the verified ModelScope per-file URLs (`pkufool/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`) and HuggingFace mirror, while retaining the placeholder URL for `2023-02-01` marked UNVERIFIED.
 */
object AsrModelDownloader {
    private const val TAG = "AsrModelDownloader"
    private const val CONNECT_TIMEOUT_MS = 15000
    private const val READ_TIMEOUT_MS = 60000

    data class ModelComponent(
        val fileName: String,
        val expectedSha256: String,
        val approxBytes: Long,
        val urls: List<String>
    )

    // Verified ModelScope base (HTTP 200 & SHA256 verified against upstream repo metadata)
    private const val MODELSCOPE_VERIFIED_BASE =
        "https://www.modelscope.cn/api/v1/models/pkufool/${AppConfig.ASR_UPSTREAM_MODEL_NAME}/repo?Revision=master&FilePath="

    // HuggingFace upstream base (may require auth/proxy in some networks)
    private const val HF_UPSTREAM_BASE =
        "https://huggingface.co/csukuangfj/${AppConfig.ASR_UPSTREAM_MODEL_NAME}/resolve/main/"

    // UNVERIFIED placeholder base for exact `2023-02-01` date string (upstream uses 2023-02-20; GitHub release has .tar.bz2 archive at:
    // https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2)
    private const val UNVERIFIED_PLACEHOLDER_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${AppConfig.ASR_MODEL_NAME}/"

    private val REQUIRED_COMPONENTS = listOf(
        ModelComponent(
            fileName = AppConfig.ASR_TOKENS_FILENAME,
            expectedSha256 = "a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3",
            approxBytes = 56_317L,
            urls = listOf(
                "${MODELSCOPE_VERIFIED_BASE}${AppConfig.ASR_TOKENS_FILENAME}",
                "${HF_UPSTREAM_BASE}${AppConfig.ASR_TOKENS_FILENAME}",
                "${UNVERIFIED_PLACEHOLDER_BASE}${AppConfig.ASR_TOKENS_FILENAME}" // UNVERIFIED placeholder
            )
        ),
        ModelComponent(
            fileName = AppConfig.ASR_ENCODER_FILENAME,
            expectedSha256 = "8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b",
            approxBytes = 181_895_032L,
            urls = listOf(
                "${MODELSCOPE_VERIFIED_BASE}${AppConfig.ASR_ENCODER_FILENAME}",
                "${HF_UPSTREAM_BASE}${AppConfig.ASR_ENCODER_FILENAME}",
                "${UNVERIFIED_PLACEHOLDER_BASE}${AppConfig.ASR_ENCODER_FILENAME}" // UNVERIFIED placeholder
            )
        ),
        ModelComponent(
            fileName = AppConfig.ASR_DECODER_FILENAME,
            expectedSha256 = "2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d",
            approxBytes = 13_876_452L,
            urls = listOf(
                "${MODELSCOPE_VERIFIED_BASE}${AppConfig.ASR_DECODER_FILENAME}",
                "${HF_UPSTREAM_BASE}${AppConfig.ASR_DECODER_FILENAME}",
                "${UNVERIFIED_PLACEHOLDER_BASE}${AppConfig.ASR_DECODER_FILENAME}" // UNVERIFIED placeholder
            )
        ),
        ModelComponent(
            fileName = AppConfig.ASR_JOINER_FILENAME,
            expectedSha256 = "1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0",
            approxBytes = 3_228_404L,
            urls = listOf(
                "${MODELSCOPE_VERIFIED_BASE}${AppConfig.ASR_JOINER_FILENAME}",
                "${HF_UPSTREAM_BASE}${AppConfig.ASR_JOINER_FILENAME}",
                "${UNVERIFIED_PLACEHOLDER_BASE}${AppConfig.ASR_JOINER_FILENAME}" // UNVERIFIED placeholder
            )
        )
    )

    fun getModelDir(context: Context): File {
        return File(context.filesDir, AppConfig.ASR_MODEL_DIR)
    }

    fun isModelReady(context: Context): Boolean {
        val dir = getModelDir(context)
        if (!dir.exists() || !dir.isDirectory) return false
        return REQUIRED_COMPONENTS.all { comp ->
            val file = File(dir, comp.fileName)
            file.exists() && file.length() > 0 && verifySha256(file, comp.expectedSha256)
        }
    }

    /**
     * Ensures all ASR model files exist in [getModelDir] and match their SHA256 checksums.
     * Emits progress via [WakeWordEventBus.emitAsrDownloadProgress].
     * Returns true if ready, false if download or verification failed.
     */
    fun ensureModelDownloaded(context: Context): Boolean {
        val dir = getModelDir(context)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val totalBytes = REQUIRED_COMPONENTS.sumOf { it.approxBytes }.coerceAtLeast(1L)
        var completedBytes = 0L

        // Fast check if all files already exist
        if (isModelReady(context)) {
            Log.i(TAG, "ASR model already present at ${dir.absolutePath}")
            return true
        }

        Log.i(TAG, "Starting first-launch ASR model download to ${dir.absolutePath}...")
        WakeWordEventBus.emitAsrDownloadProgress(0, "首次启动：正在下载语音识别模型 (0%)...")

        for (comp in REQUIRED_COMPONENTS) {
            val targetFile = File(dir, comp.fileName)
            if (targetFile.exists() && verifySha256(targetFile, comp.expectedSha256)) {
                Log.i(TAG, "Component ${comp.fileName} already verified.")
                completedBytes += comp.approxBytes
                val pct = ((completedBytes * 100L) / totalBytes).toInt().coerceIn(0, 99)
                WakeWordEventBus.emitAsrDownloadProgress(pct, "正在准备语音识别模型 ($pct%)...")
                continue
            }

            var downloaded = false
            for (url in comp.urls) {
                Log.i(TAG, "Downloading ${comp.fileName} from $url")
                val tmpFile = File(dir, "${comp.fileName}.tmp")
                if (tmpFile.exists()) tmpFile.delete()

                val ok = downloadFileWithProgress(
                    urlStr = url,
                    destFile = tmpFile,
                    baseCompletedBytes = completedBytes,
                    totalBytes = totalBytes,
                    fileName = comp.fileName
                )

                if (ok) {
                    val shaOk = verifySha256(tmpFile, comp.expectedSha256)
                    if (shaOk) {
                        if (targetFile.exists()) targetFile.delete()
                        if (tmpFile.renameTo(targetFile)) {
                            Log.i(TAG, "Downloaded and verified ${comp.fileName} (SHA256 OK)")
                            downloaded = true
                            break
                        } else {
                            Log.e(TAG, "Failed to rename ${tmpFile.name} to ${targetFile.name}")
                        }
                    } else {
                        Log.e(TAG, "SHA256 mismatch for ${comp.fileName} from $url")
                        tmpFile.delete()
                    }
                } else {
                    tmpFile.delete()
                }
            }

            if (!downloaded) {
                Log.e(TAG, "Exhausted all URLs for ASR component: ${comp.fileName}")
                WakeWordEventBus.emitAsrDownloadProgress(
                    -1,
                    "ASR 模型下载失败（请检查网络后重启服务）"
                )
                return false
            }

            completedBytes += comp.approxBytes
        }

        WakeWordEventBus.emitAsrDownloadProgress(100, "ASR 语音模型准备就绪")
        return true
    }

    private fun downloadFileWithProgress(
        urlStr: String,
        destFile: File,
        baseCompletedBytes: Long,
        totalBytes: Long,
        fileName: String
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            var currentUrl = urlStr
            var redirects = 0
            while (redirects < 5) {
                val url = URL(currentUrl)
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "VoiceKiosk/0.1.0")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return false
                    currentUrl = loc
                    redirects++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "HTTP $code from $currentUrl")
                    conn.disconnect()
                    return false
                }
                break
            }

            val input = conn?.inputStream ?: return false
            input.use { inStream ->
                FileOutputStream(destFile).use { outStream ->
                    val buffer = ByteArray(32 * 1024)
                    var fileDownloaded = 0L
                    var lastReportedPct = -1
                    var read: Int
                    while (inStream.read(buffer).also { read = it } != -1) {
                        outStream.write(buffer, 0, read)
                        fileDownloaded += read
                        val currentTotal = baseCompletedBytes + fileDownloaded
                        val pct = ((currentTotal * 100L) / totalBytes).toInt().coerceIn(0, 99)
                        if (pct != lastReportedPct) {
                            lastReportedPct = pct
                            WakeWordEventBus.emitAsrDownloadProgress(
                                pct,
                                "首次启动：正在下载语音模型 $fileName ($pct%)..."
                            )
                        }
                    }
                    outStream.flush()
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Download error from $urlStr: ${e.message}")
            return false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun verifySha256(file: File, expectedHex: String): Boolean {
        if (!file.exists()) return false
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(32 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val actualHex = digest.digest().joinToString("") { "%02x".format(it) }
            val match = actualHex.equals(expectedHex, ignoreCase = true)
            if (!match) {
                Log.w(TAG, "SHA256 mismatch for ${file.name}: expected=$expectedHex, actual=$actualHex")
            }
            match
        } catch (e: Exception) {
            Log.e(TAG, "Error computing SHA256 for ${file.name}", e)
            false
        }
    }
}
