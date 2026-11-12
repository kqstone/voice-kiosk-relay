package com.voicekiosk.app.bridge

import com.voicekiosk.app.AppConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Uniform error envelope: {"error":{"code","message","details"}} or {"detail": "..."}. */
object ErrorEnvelope {
    const val UNPAIRED_MESSAGE = "设备未配对或已被移除，请在设置里重新配对"

    data class Parsed(val code: String, val message: String)

    fun parse(body: String): Parsed? {
        return try {
            val json = JSONObject(body)
            val err = json.optJSONObject("error")
            if (err != null) {
                val code = err.optString("code", "")
                val message = err.optString("message", "")
                if (code.isNotEmpty() || message.isNotEmpty()) return Parsed(code, message)
            }
            val detail = json.optString("detail", "").trim()
            if (detail.isNotEmpty()) {
                return Parsed("error", detail)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * User-facing text for a failed HTTP call. 401 and 403 are treated as unpaired.
     */
    fun userMessage(httpCode: Int, body: String): String {
        if (httpCode == 401 || httpCode == 403) return UNPAIRED_MESSAGE
        val parsed = parse(body)
        val msg = parsed?.message?.trim().orEmpty()
        return msg.ifEmpty { "服务错误 (HTTP $httpCode)" }
    }
}

/** GET /health check (no auth). Returns {"ok": true} when reachable. */
object BridgeHealth {
    const val PATH = "/health"

    data class Info(val ok: Boolean)

    fun parse(json: JSONObject): Info = Info(
        ok = json.optBoolean("ok", false)
    )
}

object BridgeHealthClient {
    fun check(bridgeUrl: String): Result<BridgeHealth.Info> {
        var conn: HttpURLConnection? = null
        return try {
            val base = bridgeUrl.trim().trimEnd('/')
            conn = (URL("$base${BridgeHealth.PATH}").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                val parsed = BridgeHealth.parse(JSONObject(text))
                if (parsed.ok) {
                    Result.success(parsed)
                } else {
                    Result.failure(IllegalStateException("桥接服务未就绪"))
                }
            } else {
                Result.failure(IllegalStateException(ErrorEnvelope.userMessage(code, text)))
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(IllegalStateException("timeout"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.javaClass.simpleName))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}

/**
 * Origin check for VoiceKioskHost.deviceToken(): the stored token is visible to JS
 * only when the current page origin equals the origin the token is bound to.
 */
object OriginBoundToken {
    fun forPage(pageUrl: String, boundOrigin: String, token: String): String {
        if (token.isEmpty() || boundOrigin.isEmpty()) return ""
        val pageOrigin = AppConfig.normalizeBridgeOrigin(pageUrl)
        return if (pageOrigin.isNotEmpty() && pageOrigin == boundOrigin) token else ""
    }
}

/**
 * Origin check for JS host methods that must only run for the bound bridge
 * (requestPin, questionOpened, approvalOpened). Same rule as OriginBoundToken.
 */
object OriginBoundHost {
    fun allowsPage(pageUrl: String, boundOrigin: String): Boolean {
        if (boundOrigin.isEmpty()) return false
        val pageOrigin = AppConfig.normalizeBridgeOrigin(pageUrl)
        return pageOrigin.isNotEmpty() && pageOrigin == boundOrigin
    }

    fun pinRequestIdOk(requestId: String): Boolean {
        val id = requestId.trim()
        return id.length in 1..64 && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
    }
}
