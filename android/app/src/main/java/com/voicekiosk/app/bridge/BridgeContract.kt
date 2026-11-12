package com.voicekiosk.app.bridge

import com.voicekiosk.app.AppConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Uniform v2 error envelope: `{"error":{"code","message","details"}}`. */
object ErrorEnvelope {
    const val UNPAIRED_MESSAGE = "设备未配对或已被移除，请在设置里重新配对"

    data class Parsed(val code: String, val message: String)

    fun parse(body: String): Parsed? {
        return try {
            val err = JSONObject(body).optJSONObject("error") ?: return null
            val code = err.optString("code", "")
            val message = err.optString("message", "")
            if (code.isEmpty() && message.isEmpty()) null else Parsed(code, message)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * User-facing text for a failed HTTP call. 401 is always treated as unpaired
     * (LLM upstream failures are mapped off 401 by the bridge). 426 / version
     * mismatch is surfaced via the meta handshake, not as unpaired.
     */
    fun userMessage(httpCode: Int, body: String): String {
        if (httpCode == 401) return UNPAIRED_MESSAGE
        val parsed = parse(body)
        val msg = parsed?.message?.trim().orEmpty()
        return msg.ifEmpty { "服务错误 (HTTP $httpCode)" }
    }
}

/** `GET /api/v2/meta` handshake (no auth). */
object BridgeMeta {
    const val PATH = "/api/v2/meta"
    const val EXPECTED_API_VERSION = 2
    const val NOTICE_API_MISMATCH = "请升级 App / 桥接服务（接口版本不匹配）"
    const val NOTICE_CLIENT_TOO_OLD = "请升级 App（当前版本过低）"

    data class Info(
        val apiVersion: Int,
        val minClientVersion: Int,
        val serverVersion: String = ""
    )

    data class Decision(
        /** Cloud conversation/memory calls are attempted only when api_version matches. */
        val cloudAllowed: Boolean,
        /** Non-blocking dashboard notice; null means clear any previous notice. */
        val notice: String?
    )

    fun parse(json: JSONObject): Info = Info(
        apiVersion = json.optInt("api_version", 0),
        minClientVersion = json.optInt("min_client_version", 0),
        serverVersion = json.optString("server_version", "")
    )

    fun decide(info: Info, clientVersionCode: Int): Decision {
        val notice = when {
            info.apiVersion != EXPECTED_API_VERSION -> NOTICE_API_MISMATCH
            clientVersionCode < info.minClientVersion -> NOTICE_CLIENT_TOO_OLD
            else -> null
        }
        return Decision(
            cloudAllowed = info.apiVersion == EXPECTED_API_VERSION,
            notice = notice
        )
    }
}

object BridgeMetaClient {
    fun fetch(bridgeUrl: String): Result<BridgeMeta.Info> {
        var conn: HttpURLConnection? = null
        return try {
            val base = bridgeUrl.trim().trimEnd('/')
            conn = (URL("$base${BridgeMeta.PATH}").openConnection() as HttpURLConnection).apply {
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
                Result.success(BridgeMeta.parse(JSONObject(text)))
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

/** Device pairing: `POST /api/v2/pairing` (no auth). Never log the token. */
object PairingWire {
    const val PATH = "/api/v2/pairing"
    const val DEFAULT_DEVICE_NAME = "kiosk"

    data class Result(val deviceToken: String, val deviceId: String)

    fun parseCode(raw: String): String? {
        val c = raw.trim()
        return if (c.length == 8 && c.all { it.isDigit() }) c else null
    }

    fun sanitizedDeviceName(raw: String): String {
        val t = raw.trim().filter { it.code !in 0..31 && it.code != 127 }.take(64)
        return t.ifEmpty { DEFAULT_DEVICE_NAME }
    }

    fun requestJson(code: String, deviceName: String): JSONObject =
        JSONObject()
            .put("code", parseCode(code) ?: code.trim())
            .put("device_name", sanitizedDeviceName(deviceName))

    fun parseResponse(json: JSONObject): Result? {
        val token = json.optString("device_token", "").trim()
        if (token.isEmpty()) return null
        return Result(deviceToken = token, deviceId = json.optString("device_id", "").trim())
    }
}

object PairingClient {
    fun pair(bridgeUrl: String, code: String, deviceName: String): kotlin.Result<PairingWire.Result> {
        if (AppConfig.normalizeBridgeOrigin(bridgeUrl).isEmpty()) {
            return kotlin.Result.failure(IllegalStateException("桥接服务地址格式无效（需包含 http:// 或 https:// 主机地址）"))
        }
        if (PairingWire.parseCode(code) == null) {
            return kotlin.Result.failure(IllegalStateException("请输入 8 位数字配对码"))
        }
        val payload = PairingWire.requestJson(code, deviceName).toString()
        var conn: HttpURLConnection? = null
        return try {
            val base = bridgeUrl.trim().trimEnd('/')
            conn = (URL("$base${PairingWire.PATH}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = AppConfig.CHAT_TIMEOUT_MS
                readTimeout = AppConfig.CHAT_TIMEOUT_MS
                instanceFollowRedirects = false
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { os ->
                os.write(payload.toByteArray(Charsets.UTF_8))
                os.flush()
            }
            val httpCode = conn.responseCode
            val text = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (httpCode in 200..299) {
                val parsed = PairingWire.parseResponse(JSONObject(text))
                    ?: return kotlin.Result.failure(IllegalStateException("配对响应无效"))
                kotlin.Result.success(parsed)
            } else {
                kotlin.Result.failure(IllegalStateException(ErrorEnvelope.userMessage(httpCode, text)))
            }
        } catch (e: java.net.SocketTimeoutException) {
            kotlin.Result.failure(IllegalStateException("网络请求超时，无法完成配对"))
        } catch (e: java.io.IOException) {
            kotlin.Result.failure(IllegalStateException("网络不可用，无法连接桥接服务"))
        } catch (e: Exception) {
            kotlin.Result.failure(IllegalStateException("配对失败"))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}

/**
 * Origin check for `VoiceKioskHost.deviceToken()`: the stored token is visible to JS
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
 * (`requestPin`, `questionOpened`, `approvalOpened`). Same rule as [OriginBoundToken].
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
