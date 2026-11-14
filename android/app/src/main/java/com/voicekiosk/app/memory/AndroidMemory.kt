package com.voicekiosk.app.memory

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.voicekiosk.app.AppConfig
import com.voicekiosk.app.bridge.ErrorEnvelope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable AndroidKeyStore key; stored as iv(12) + ciphertext. */
class KeystoreCodec(private val alias: String = "voice_kiosk_memory_cache_key_v1") : ByteCodec {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override fun encode(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    override fun decode(stored: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, stored.copyOfRange(0, 12)))
        return c.doFinal(stored.copyOfRange(12, stored.size))
    }

    companion object {
        fun cacheFile(context: Context) = File(context.noBackupFilesDir, "memory/cache.bin")
    }
}

internal class HttpStatusException(val code: Int, message: String) : IllegalStateException(message)

/**
 * Device-token client for `GET /api/v1/memory/snapshot?since=` and
 * `POST /api/v1/memory/candidates` (S5). Same token handling as conversation turns: the
 * stored token is only sent to the origin it was saved for, as an Authorization header,
 * without redirects.
 */
class HttpMemoryBackend(private val context: Context) : MemoryBackend {
    private fun call(method: String, path: String, body: JSONObject?, idempotencyKey: String? = null): Result<JSONObject> {
        var conn: HttpURLConnection? = null
        return try {
            val base = AppConfig.getBridgeUrl(context).trim().trimEnd('/')
            val token = AppConfig.getBridgeAuthTokenForUrl(context, base)
            if (token.isEmpty()) return Result.failure(IllegalStateException(ErrorEnvelope.UNPAIRED_MESSAGE))
            conn = (URL("$base$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 5000
                readTimeout = 8000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                if (!idempotencyKey.isNullOrEmpty()) {
                    setRequestProperty("Idempotency-Key", idempotencyKey)
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            if (body != null) conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Result.failure(HttpStatusException(code, ErrorEnvelope.userMessage(code, text)))
            } else {
                Result.success(JSONObject(text))
            }
        } catch (e: HttpStatusException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.javaClass.simpleName))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun snapshot(since: String): Result<MemorySnapshot> {
        val encoded = if (since.isEmpty()) "" else URLEncoder.encode(since, "UTF-8")
        val path = if (encoded.isEmpty()) MemoryWire.SNAPSHOT_PATH else "${MemoryWire.SNAPSHOT_PATH}?since=$encoded"
        val first = call("GET", path, null)
        val json = first.getOrElse { err ->
            val code = (err as? HttpStatusException)?.code
            if ((code == 409 || code == 410) && since.isNotEmpty()) {
                return snapshot("")
            }
            return Result.failure(err)
        }
        return Result.success(MemoryWire.snapshot(json))
    }

    override fun submit(candidates: List<MemoryCandidate>): Result<List<SubmitResult>> =
        call(
            "POST",
            MemoryWire.CANDIDATES_PATH,
            MemoryWire.request(candidates),
            idempotencyKey = UUID.randomUUID().toString()
        ).map { MemoryWire.results(it) }
}

/** JSON shapes of the bridge memory endpoints (JVM-testable). */
object MemoryWire {
    const val SNAPSHOT_PATH = "/api/v1/memory/snapshot"
    const val CANDIDATES_PATH = "/api/v1/memory/candidates"

    fun request(candidates: List<MemoryCandidate>): JSONObject = JSONObject().put("candidates", JSONArray().apply {
        candidates.forEach { c ->
            put(JSONObject().put("id", c.id).put("content", c.fact.content).put("category", c.fact.category.wire)
                .put("origin", if (c.state == MemoryManager.STATE_CONFIRMED) "confirmed" else "auto")
                .apply { if (c.fact.key.isNotEmpty()) put("key", c.fact.key) })
        }
    })

    fun items(arr: JSONArray?): List<MemoryItem> = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
        val o = arr.optJSONObject(i) ?: return@mapNotNull null
        val content = o.optString("content", "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        MemoryItem(o.optLong("id"), o.optString("key", ""), content, o.optDouble("updated_at", 0.0))
    }

    fun deletedIds(arr: JSONArray?): List<Long> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            if (arr.isNull(i)) null else arr.optLong(i, -1L).takeIf { it >= 0L }
        }
    }

    fun snapshot(o: JSONObject): MemorySnapshot = MemorySnapshot(
        items = items(o.optJSONArray("items")),
        deletedIds = deletedIds(o.optJSONArray("deleted_ids")),
        cursor = o.optString("cursor", ""),
        reset = o.optBoolean("reset", false)
    )

    /**
     * Merge an incremental snapshot into the local cache. `reset: true` replaces the
     * live set (server sent the complete items list; [MemorySnapshot.deletedIds] is empty).
     */
    fun merge(existing: List<MemoryItem>, snap: MemorySnapshot): List<MemoryItem> {
        if (snap.reset) return snap.items
        val deleted = snap.deletedIds.toSet()
        val incomingIds = snap.items.map { it.id }.toSet()
        val incomingKeys = snap.items.map { it.key }.filter { it.isNotEmpty() }.toSet()
        val kept = existing.filter {
            it.id !in deleted && it.id !in incomingIds && (it.key.isEmpty() || it.key !in incomingKeys)
        }
        return snap.items + kept
    }

    fun results(o: JSONObject): List<SubmitResult> {
        val arr = o.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val r = arr.optJSONObject(i) ?: return@mapNotNull null
            val item = r.optJSONObject("item")?.let { MemoryItem(it.optLong("id"), it.optString("key", ""), it.optString("content", "")) }
            SubmitResult(r.optString("id"), r.optString("status"), item?.takeIf { it.content.isNotEmpty() })
        }
    }
}
