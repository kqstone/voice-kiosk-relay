package com.voicekiosk.app.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * HTTP client for relay bridge board endpoints:
 * - GET /api/v1/boards
 * - GET /api/v1/boards/{board_id}
 * - POST /api/v1/boards/{board_id}/items/{item_id}/toggle
 *
 * Uses standard HttpURLConnection and passes Bearer device token auth.
 */
object BoardApiClient {
    private const val TAG = "BoardApiClient"
    private const val TIMEOUT_MS = 6000

    fun fetchBoardIds(baseUrl: String, token: String): Result<List<String>> {
        var conn: HttpURLConnection? = null
        return try {
            val root = baseUrl.trim().trimEnd('/')
            val url = URL("$root/api/v1/boards")
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
                val arr = JSONArray(text)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                Result.success(list)
            } else {
                Result.failure(IllegalStateException("HTTP $code: $text"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun fetchBoard(baseUrl: String, token: String, boardId: String): Result<Pair<BoardModel, String>> {
        var conn: HttpURLConnection? = null
        return try {
            val root = baseUrl.trim().trimEnd('/')
            val encodedId = URLEncoder.encode(boardId, "UTF-8")
            val url = URL("$root/api/v1/boards/$encodedId")
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
                val model = BoardParser.parseBoard(boardId, text)
                Result.success(Pair(model, text))
            } else {
                Result.failure(IllegalStateException("HTTP $code: $text"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    fun toggleItem(
        baseUrl: String,
        token: String,
        boardId: String,
        itemId: String,
        completed: Boolean?
    ): Result<Boolean> {
        var conn: HttpURLConnection? = null
        return try {
            val root = baseUrl.trim().trimEnd('/')
            val encodedBoardId = URLEncoder.encode(boardId, "UTF-8")
            val encodedItemId = URLEncoder.encode(itemId, "UTF-8")
            val url = URL("$root/api/v1/boards/$encodedBoardId/items/$encodedItemId/toggle")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                if (token.isNotEmpty()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val bodyJson = JSONObject()
            if (completed != null) {
                bodyJson.put("completed", completed)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                it.write(bodyJson.toString())
                it.flush()
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code in 200..299) {
                val resp = JSONObject(text)
                val newCompleted = resp.optBoolean("completed", completed ?: false)
                Result.success(newCompleted)
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
