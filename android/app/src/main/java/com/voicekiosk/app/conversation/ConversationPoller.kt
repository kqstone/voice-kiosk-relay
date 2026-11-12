package com.voicekiosk.app.conversation

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Record returned by GET /api/v1/conversations/{conversation_id}.
 *
 * @property id The conversation ID.
 * @property status Current status: "pending", "answered", or "timed_out".
 * @property reply The reply text from Muse or fallback timeout message.
 */
data class ConversationRecord(
    val id: String,
    val status: String,
    val reply: String
)

/**
 * Polling helper for async Muse queue responses.
 *
 * Important design decision: The Android app has no WebSocket client (there is no WebSocket code
 * anywhere in android/). Do NOT add a WebSocket client in this phase. Instead, use HTTP polling
 * to fetch the Muse answer: poll GET /api/v1/conversations/{conversation_id} with the device
 * token as Bearer auth. This reuses the existing HttpURLConnection code and is simpler and more
 * reliable than adding a new WebSocket dependency.
 */
object ConversationPoller {
    const val CONNECT_TIMEOUT_MS = 5000
    const val READ_TIMEOUT_MS = 5000
    const val POLL_INTERVAL_MS = 3000L
    const val MAX_WAIT_MS = 120_000L // 2 minutes

    const val STATUS_PENDING = "pending"
    const val STATUS_ANSWERED = "answered"
    const val STATUS_TIMED_OUT = "timed_out"

    const val FALLBACK_TIMEOUT_REPLY = "Muse 现在没回，稍后再试"

    fun conversationUrl(baseUrl: String, conversationId: String): String {
        val root = baseUrl.trim().trimEnd('/')
        return "$root/api/v1/conversations/$conversationId"
    }

    /**
     * Polls GET /api/v1/conversations/{conversation_id} once.
     * Connect and read timeouts are 5 seconds. Bearer token auth is sent if present.
     */
    fun fetchConversation(
        baseUrl: String,
        token: String,
        conversationId: String
    ): Result<ConversationRecord> {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(conversationUrl(baseUrl, conversationId))
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doInput = true
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                val json = JSONObject(body)
                val id = json.optString("id", conversationId).trim()
                val status = json.optString("status", "").trim()
                val reply = json.optString("reply", "").trim()
                Result.success(ConversationRecord(id = id, status = status, reply = reply))
            } else {
                Result.failure(IOException("HTTP $code: $body"))
            }
        } catch (e: SocketTimeoutException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {}
        }
    }
}
