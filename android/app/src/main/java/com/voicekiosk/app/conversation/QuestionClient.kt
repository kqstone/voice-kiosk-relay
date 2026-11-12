package com.voicekiosk.app.conversation

import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.bridge.ErrorEnvelope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** POST `/api/v2/questions/{id}/answer` with the device token. */
object QuestionClient {
    fun postAnswer(
        bridgeUrl: String,
        token: String,
        questionId: String,
        option: String? = null,
        text: String? = null
    ): Result<Unit> {
        val id = questionId.trim()
        if (id.isEmpty()) return Result.failure(IllegalArgumentException("empty question id"))
        if (token.isEmpty()) return Result.failure(IllegalStateException(ErrorEnvelope.UNPAIRED_MESSAGE))
        val base = bridgeUrl.trim().trimEnd('/')
        if (AppConfig.normalizeBridgeOrigin(base).isEmpty()) {
            return Result.failure(IllegalStateException("桥接服务地址格式无效"))
        }
        val payload = QuestionWire.answerJson(option, text).toString()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base${QuestionWire.answerPath(id)}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = AppConfig.CHAT_TIMEOUT_MS
                readTimeout = AppConfig.CHAT_TIMEOUT_MS
                instanceFollowRedirects = false
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) Result.success(Unit)
            else Result.failure(IllegalStateException(ErrorEnvelope.userMessage(code, body)))
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("timeout"))
        } catch (e: java.io.IOException) {
            Result.failure(IllegalStateException("offline"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.javaClass.simpleName))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
