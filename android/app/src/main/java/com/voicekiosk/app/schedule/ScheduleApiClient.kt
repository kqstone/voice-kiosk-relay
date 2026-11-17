package com.voicekiosk.app.schedule

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Snapshot response DTO from GET /api/v1/schedules/snapshot. */
data class ScheduleSnapshotResponse(
    val items: List<ScheduleItem>,
    val deletedIds: List<String>,
    val cursor: String,
    val reset: Boolean,
    val serverTimeUtc: Double
)

/** Candidate submission item result DTO. */
data class CandidateResult(
    val id: String,
    val status: String,
    val item: ScheduleItem?
)

/** Candidates batch submission response DTO. */
data class ScheduleCandidatesResponse(
    val results: List<CandidateResult>,
    val changed: Boolean
)

/**
 * HTTP client for relay bridge schedules endpoints:
 * - GET /api/v1/schedules/snapshot?since={cursor}
 * - POST /api/v1/schedules/candidates
 * - POST /api/v1/schedules/{id}/action
 * - DELETE /api/v1/schedules/{id}
 *
 * Uses standard HttpURLConnection and passes Bearer device token auth.
 * Zero hardcoded deployment URLs, domains, ports, or keys.
 */
object ScheduleApiClient {
    private const val TAG = "ScheduleApiClient"
    private const val TIMEOUT_MS = 6000

    fun fetchSnapshot(
        baseUrl: String,
        token: String,
        since: String = "",
        type: String? = null
    ): Result<ScheduleSnapshotResponse> {
        var conn: HttpURLConnection? = null
        return try {
            val root = baseUrl.trim().trimEnd('/')
            var urlStr = "$root/api/v1/schedules/snapshot"
            val queryParams = mutableListOf<String>()
            if (since.isNotEmpty()) {
                queryParams.add("since=${URLEncoder.encode(since, "UTF-8")}")
            }
            if (!type.isNullOrEmpty()) {
                queryParams.add("type=${URLEncoder.encode(type, "UTF-8")}")
            }
            if (queryParams.isNotEmpty()) {
                urlStr += "?" + queryParams.joinToString("&")
            }

            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                val json = JSONObject(text)
                val itemsArr = json.optJSONArray("items") ?: JSONArray()
                val items = mutableListOf<ScheduleItem>()
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    items.add(ScheduleItem.fromJsonObject(obj))
                }

                val delArr = json.optJSONArray("deleted_ids") ?: JSONArray()
                val deletedIds = mutableListOf<String>()
                for (i in 0 until delArr.length()) {
                    deletedIds.add(delArr.getString(i))
                }

                val cursor = json.optString("cursor", "")
                val reset = json.optBoolean("reset", false)
                val serverTimeUtc = json.optDouble("server_time_utc", 0.0)

                Result.success(
                    ScheduleSnapshotResponse(
                        items = items,
                        deletedIds = deletedIds,
                        cursor = cursor,
                        reset = reset,
                        serverTimeUtc = serverTimeUtc
                    )
                )
            } else {
                Result.failure(IllegalStateException("HTTP $code: $text"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun submitCandidates(
        baseUrl: String,
        token: String,
        candidates: List<ScheduleItem>,
        idempotencyKey: String? = null
    ): Result<ScheduleCandidatesResponse> {
        var conn: HttpURLConnection? = null
        return try {
            val root = baseUrl.trim().trimEnd('/')
            val url = URL("$root/api/v1/schedules/candidates")
            val payload = ScheduleSyncLogic.buildCandidatePayload(candidates)

            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (!idempotencyKey.isNullOrEmpty()) {
                    setRequestProperty("Idempotency-Key", idempotencyKey)
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
                it.flush()
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                val json = JSONObject(text)
                val resultsArr = json.optJSONArray("results") ?: JSONArray()
                val results = mutableListOf<CandidateResult>()
                for (i in 0 until resultsArr.length()) {
                    val obj = resultsArr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    val status = obj.optString("status", "")
                    val itemObj = obj.optJSONObject("item")
                    val item = if (itemObj != null) ScheduleItem.fromJsonObject(itemObj) else null
                    results.add(CandidateResult(id, status, item))
                }
                val changed = json.optBoolean("changed", false)
                Result.success(ScheduleCandidatesResponse(results, changed))
            } else {
                Result.failure(IllegalStateException("HTTP $code: $text"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun executeAction(
        baseUrl: String,
        token: String,
        scheduleId: String,
        action: String = "complete",
        snoozeSeconds: Int = 300
    ): Result<ScheduleItem> {
        var conn: HttpURLConnection? = null
        return try {
            val root = baseUrl.trim().trimEnd('/')
            val encodedId = URLEncoder.encode(scheduleId, "UTF-8")
            val url = URL("$root/api/v1/schedules/$encodedId/action")

            val payload = JSONObject().apply {
                put("action", action)
                put("snooze_seconds", snoozeSeconds)
            }

            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(payload.toString())
                it.flush()
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                val json = JSONObject(text)
                Result.success(ScheduleItem.fromJsonObject(json))
            } else {
                Result.failure(IllegalStateException("HTTP $code: $text"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun deleteSchedule(
        baseUrl: String,
        token: String,
        scheduleId: String
    ): Result<Boolean> {
        var conn: HttpURLConnection? = null
        return try {
            val root = baseUrl.trim().trimEnd('/')
            val encodedId = URLEncoder.encode(scheduleId, "UTF-8")
            val url = URL("$root/api/v1/schedules/$encodedId")

            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                Result.success(true)
            } else {
                Result.failure(IllegalStateException("HTTP $code: $text"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
