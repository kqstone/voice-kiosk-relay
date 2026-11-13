package com.voicekiosk.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure JVM-testable GitHub public release update checker for Voice Kiosk.
 *
 * Security & Consistency Guarantees:
 * 1. Pinned exclusively to official repository `kqstone/voice-kiosk-relay`:
 *    - API endpoint: `https://api.github.com/repos/kqstone/voice-kiosk-relay/releases/latest`
 *    - Browser release page prefix: `https://github.com/kqstone/voice-kiosk-relay/releases/`
 *    - Official APK/metadata asset prefix: `https://github.com/kqstone/voice-kiosk-relay/releases/download/<tag>/<asset>`
 *    - Controlled redirect domains for metadata download: only official GitHub release asset CDN domains
 *      (`objects.githubusercontent.com`, `release-assets.githubusercontent.com`, `github-releases.githubusercontent.com`).
 * 2. Strict URL validation:
 *    - HTTPS only, standard port (443 or unspecified), no userinfo (`user:pass@`), no query/fragment where forbidden,
 *      no path traversal (`..`, `.`, `\`, encoded `%2e`, `%2f`, `%5c`, null bytes).
 * 3. Unauthenticated public HTTP client:
 *    - NEVER sends `Authorization` header, never reads NAS bridge configuration, never shares tokens or credentials.
 *    - Enforces strict response byte size limits (`MAX_API_RESPONSE_BYTES`, `MAX_METADATA_RESPONSE_BYTES`) and bounded redirects.
 * 4. Strict validation of `release-metadata.json` (Phase 1 schemaVersion = 1):
 *    - Rejects `draft == true` or `prerelease == true`.
 *    - Validates `schemaVersion`, `repository`, `applicationId`, `versionCode` (Long > 0), `versionName`,
 *      `tagName` (`v<major>.<minor>.<patch>` == `v` + `versionName`), `minSdk`, `targetSdk`,
 *      `signingCertSha256` (64 lowercase hex), and `apks` (`abi`, `name`, `size`, `sha256`).
 *    - Cross-validates GitHub Release JSON `tag_name`, `html_url`, and `assets` (`name`, `size`, `browser_download_url`)
 *      against `release-metadata.json`.
 * 5. Integer `versionCode` comparison (`Long`), never string comparison on `versionName`.
 * 6. Device compatibility & signing certificate verification:
 *    - Checks device `sdkInt >= minSdk`, `supportedAbis` contains `arm64-v8a`, and local APK signing certificate SHA-256:
 *      - `MATCH`: safe to guide user to browser download for in-place upgrade.
 *      - `MISMATCH`: blocks in-place upgrade guidance and warns that certificates conflict (would fail or require uninstall).
 *      - `UNKNOWN`: explicitly distinguishes unknown local signature state (not a security guarantee) and warns user before browser navigation.
 * 7. Rate-limit, caching, and concurrency safety:
 *    - Handles HTTP 404 (no published release yet), HTTP 403/429 (`Retry-After` / `X-RateLimit-Reset`),
 *      HTTP 304 (`ETag` / `If-None-Match` cache hit), and offline/timeout errors with bounded retry and cooldown.
 *    - Expired cache is never treated as fresh without network revalidation.
 *    - Bounded single-thread background executor, single-flight deduplication, and lifecycle-safe listener registration/unregistration.
 */
object ReleaseUpdateChecker {

    const val EXPECTED_REPOSITORY = "kqstone/voice-kiosk-relay"
    const val EXPECTED_APPLICATION_ID = "com.voicekiosk.app"
    const val EXPECTED_ABI = "arm64-v8a"
    const val EXPECTED_SCHEMA_VERSION = 1

    const val METADATA_ASSET_NAME = "release-metadata.json"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/kqstone/voice-kiosk-relay/releases/latest"
    const val OFFICIAL_RELEASES_HTML_URL = "https://github.com/kqstone/voice-kiosk-relay/releases"

    const val MAX_API_RESPONSE_BYTES = 512 * 1024 // 512 KB
    const val MAX_METADATA_RESPONSE_BYTES = 128 * 1024 // 128 KB
    const val MAX_REDIRECTS = 3

    const val CONNECT_TIMEOUT_MS = 8_000
    const val READ_TIMEOUT_MS = 8_000

    const val CACHE_TTL_MS = 15 * 60 * 1000L // 15 minutes fresh cache TTL
    const val MAX_ETAG_304_TRUST_AGE_MS = 60 * 60 * 1000L // 1 hour max metadata age before re-fetching metadata asset (same tag asset replacement protection)
    const val MANUAL_CHECK_COOLDOWN_MS = 5_000L // 5 seconds cooldown for rapid manual button clicks (independent of UI button state)
    const val DEFAULT_RATE_LIMIT_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes fallback cooldown on 403/429
    const val MAX_RATE_LIMIT_COOLDOWN_MS = 60 * 60 * 1000L // Cap cooldown at 1 hour
    const val STARTUP_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours daily frequency cap

    /**
     * Explicit Phase 2 policy: GitHub Release body / release notes are NEVER parsed, rendered, or executed as HTML.
     * Release notes are intentionally not displayed in the native UI; users may view the official GitHub Release
     * page in the system browser after explicit confirmation.
     */
    const val RELEASE_NOTES_DISPLAY_SUPPORTED = false

    private val TAG_REGEX = Regex("^v(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z.-]+)?$")
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    private val SAFE_ASSET_NAME_REGEX = Regex("^[A-Za-z0-9._-]+$")

    private val ALLOWED_METADATA_REDIRECT_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com"
    )

    data class InstalledAppInfo(
        val applicationId: String,
        val versionCode: Long?, // null or <= 0 means local package version could not be read reliably
        val versionName: String?, // null or blank means local package version name could not be read reliably
        val sdkInt: Int,
        val supportedAbis: List<String>,
        val signingCertSha256: String?, // null or blank if local signature could not be determined (or multiple signers)
        val packageReadError: String? = null // non-null if PackageManager failed to read local package info
    ) {
        val hasValidVersionInfo: Boolean
            get() = packageReadError == null &&
                applicationId.isNotBlank() &&
                versionCode != null &&
                versionCode > 0L &&
                !versionName.isNullOrBlank()
    }

    data class ApkAssetMetadata(
        val abi: String,
        val name: String,
        val size: Long,
        val sha256: String,
        val downloadUrl: String
    )

    data class ValidatedReleaseInfo(
        val schemaVersion: Int,
        val repository: String,
        val applicationId: String,
        val versionCode: Long,
        val versionName: String,
        val tagName: String,
        val minSdk: Int,
        val targetSdk: Int,
        val signingCertSha256: String,
        val htmlUrl: String,
        val apk: ApkAssetMetadata
    )

    enum class SignatureTrustStatus {
        /** Local signing certificate SHA-256 matches `release-metadata.json` `signingCertSha256`. */
        MATCHED,
        /** Local signing certificate SHA-256 could not be read; cannot guarantee in-place signature match. */
        UNKNOWN_LOCAL_SIGNATURE,
        /** Local signing certificate SHA-256 differs from release APK; in-place overwrite install will fail. */
        MISMATCHED
    }

    enum class IncompatibilityReason {
        APPLICATION_ID_MISMATCH,
        SDK_TOO_LOW,
        ABI_UNSUPPORTED,
        SIGNATURE_MISMATCH
    }

    sealed class CheckResult {
        data class UpdateAvailable(
            val currentVersionName: String,
            val currentVersionCode: Long,
            val release: ValidatedReleaseInfo,
            val signatureStatus: SignatureTrustStatus,
            val statusMessageZh: String
        ) : CheckResult()

        data class UpToDate(
            val currentVersionName: String,
            val currentVersionCode: Long,
            val latestRelease: ValidatedReleaseInfo,
            val signatureStatus: SignatureTrustStatus,
            val statusMessageZh: String
        ) : CheckResult()

        data class IncompatibleUpgrade(
            val currentVersionName: String,
            val currentVersionCode: Long,
            val release: ValidatedReleaseInfo,
            val reasons: List<IncompatibilityReason>,
            val signatureStatus: SignatureTrustStatus,
            val statusMessageZh: String
        ) : CheckResult()

        data class NoPublishedRelease(
            val officialReleasesUrl: String,
            val statusMessageZh: String
        ) : CheckResult()

        data class RateLimited(
            val retryAfterEpochMs: Long,
            val remainingSeconds: Long,
            val statusMessageZh: String
        ) : CheckResult()

        data class NetworkFailure(
            val isTimeout: Boolean,
            val statusMessageZh: String
        ) : CheckResult()

        data class ValidationFailure(
            val reasonCode: String,
            val statusMessageZh: String
        ) : CheckResult()

        data class LocalVersionUnknown(
            val reasonCode: String,
            val statusMessageZh: String
        ) : CheckResult()
    }

    data class HttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String,
        val finalUrl: String
    ) {
        fun header(name: String): String? {
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }
    }

    interface HttpClient {
        @Throws(IOException::class)
        fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
            allowMetadataRedirects: Boolean
        ): HttpResponse
    }

    interface Clock {
        fun nowMs(): Long
    }

    object SystemClock : Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }

    data class CachedReleaseState(
        val etag: String?,
        val releaseJson: String,
        val metadataJson: String,
        val cachedAtEpochMs: Long,
        val rateLimitUntilEpochMs: Long = 0L,
        val lastStartupCheckEpochMs: Long = 0L,
        val metadataFetchedAtEpochMs: Long = cachedAtEpochMs
    )

    interface CacheStore {
        fun read(): CachedReleaseState?
        fun write(state: CachedReleaseState)
        fun readRateLimitUntilMs(): Long
        fun writeRateLimitUntilMs(untilEpochMs: Long)
        fun readLastStartupCheckMs(): Long
        fun writeLastStartupCheckMs(timestampEpochMs: Long)
    }

    class InMemoryCacheStore : CacheStore {
        @Volatile
        private var cached: CachedReleaseState? = null
        @Volatile
        private var rateLimitUntilMs: Long = 0L
        @Volatile
        private var lastStartupCheckMs: Long = 0L

        override fun read(): CachedReleaseState? = cached
        override fun write(state: CachedReleaseState) {
            cached = state
        }

        override fun readRateLimitUntilMs(): Long = rateLimitUntilMs
        override fun writeRateLimitUntilMs(untilEpochMs: Long) {
            rateLimitUntilMs = untilEpochMs
        }

        override fun readLastStartupCheckMs(): Long = lastStartupCheckMs
        override fun writeLastStartupCheckMs(timestampEpochMs: Long) {
            lastStartupCheckMs = timestampEpochMs
        }
    }

    class ValidationException(val code: String, val messageZh: String) : Exception("$code: $messageZh")
    class ResponseTooLargeException(message: String) : IOException(message)

    fun sanitizeReleaseNotesPlainText(rawBody: String?, maxLength: Int = 280): String {
        if (rawBody.isNullOrBlank() || maxLength <= 0) return ""
        val withoutTags = rawBody
            .replace(Regex("<[^>]*>"), " ")
            .replace("\u0000", "")
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .trim()
        if (withoutTags.isEmpty()) return ""
        return if (withoutTags.length <= maxLength) {
            withoutTags
        } else {
            withoutTags.substring(0, maxLength).trimEnd() + "..."
        }
    }

    /**
     * Pure JVM-testable helper for resolving the current single APK signing certificate SHA-256
     * from `apkContentsSigners`:
     * - Must ONLY inspect `apkContentsSigners` (never `signingCertificateHistory[0]`, as rotated historical
     *   certificates cannot impersonate the current active APK signer).
     * - If `hasMultipleSigners == true` or `apkContentsSigners.size != 1`, returns `null` (unknown) rather
     *   than picking the first signer and falsely claiming a match.
     */
    fun extractSingleCurrentSignerSha256(
        apkContentsSignersBytes: List<ByteArray?>?,
        hasMultipleSigners: Boolean = false
    ): String? {
        if (hasMultipleSigners || apkContentsSignersBytes == null || apkContentsSignersBytes.size != 1) {
            return null
        }
        val singleCertBytes = apkContentsSignersBytes[0] ?: return null
        if (singleCertBytes.isEmpty()) {
            return null
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(singleCertBytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b))
        }
        return sb.toString()
    }

    // --- URL & Security Validation ---

    fun validateOfficialApiUrl(urlStr: String): URI {
        val uri = parseStrictHttpsUri(urlStr)
        if (uri.host?.lowercase(Locale.ROOT) != "api.github.com") {
            throw ValidationException("INVALID_API_HOST", "非法的 GitHub API 域名")
        }
        if (uri.rawQuery != null) {
            throw ValidationException("INVALID_API_QUERY", "GitHub API 地址不允许包含查询参数")
        }
        val expectedPath = "/repos/$EXPECTED_REPOSITORY/releases/latest"
        if (uri.rawPath != expectedPath || uri.path != expectedPath) {
            throw ValidationException("INVALID_API_PATH", "GitHub API 路径不是官方仓库 releases/latest")
        }
        return uri
    }

    fun validateOfficialReleaseHtmlUrl(urlStr: String, expectedTag: String): String {
        validateTagName(expectedTag)
        val uri = parseStrictHttpsUri(urlStr)
        if (uri.host?.lowercase(Locale.ROOT) != "github.com") {
            throw ValidationException("INVALID_HTML_HOST", "Release 页面域名不是 github.com")
        }
        if (uri.rawQuery != null) {
            throw ValidationException("INVALID_HTML_QUERY", "Release 页面地址不允许包含查询参数")
        }
        val expectedPath = "/$EXPECTED_REPOSITORY/releases/tag/$expectedTag"
        if (uri.rawPath != expectedPath || uri.path != expectedPath) {
            throw ValidationException("INVALID_HTML_PATH", "Release 页面路径与仓库或版本标签不匹配")
        }
        return uri.toString()
    }

    fun validateOfficialAssetDownloadUrl(urlStr: String, expectedTag: String, expectedFileName: String): String {
        validateTagName(expectedTag)
        validateSafeAssetName(expectedFileName)
        val uri = parseStrictHttpsUri(urlStr)
        if (uri.host?.lowercase(Locale.ROOT) != "github.com") {
            throw ValidationException("INVALID_ASSET_HOST", "资产下载域名必须是 github.com")
        }
        if (uri.rawQuery != null) {
            throw ValidationException("INVALID_ASSET_QUERY", "资产下载地址不允许携带额外查询参数")
        }
        val expectedPath = "/$EXPECTED_REPOSITORY/releases/download/$expectedTag/$expectedFileName"
        if (uri.rawPath != expectedPath || uri.path != expectedPath) {
            throw ValidationException("INVALID_ASSET_PATH", "资产下载路径与官方仓库、标签或文件名不一致")
        }
        return uri.toString()
    }

    /**
     * Strict official GitHub Releases index URL validator (`https://github.com/kqstone/voice-kiosk-relay/releases`),
     * used when no formal release has been published yet (HTTP 404).
     */
    fun validateOfficialReleasesIndexUrl(urlStr: String): String {
        val uri = parseStrictHttpsUri(urlStr)
        if (uri.host?.lowercase(Locale.ROOT) != "github.com") {
            throw ValidationException("INVALID_RELEASES_INDEX_HOST", "Releases 页面域名不是 github.com")
        }
        if (uri.rawQuery != null) {
            throw ValidationException("INVALID_RELEASES_INDEX_QUERY", "Releases 页面地址不允许包含查询参数")
        }
        val expectedPath = "/$EXPECTED_REPOSITORY/releases"
        if (uri.rawPath != expectedPath || uri.path != expectedPath) {
            throw ValidationException("INVALID_RELEASES_INDEX_PATH", "Releases 页面路径不是官方仓库 releases 首页")
        }
        return uri.toString()
    }

    /**
     * Sealed target specification for browser navigation after explicit user confirmation:
     * ensures the URL is re-validated against the exact validated release (`tagName` and/or canonical `apk.name`)
     * using the full strict URI validator rather than a coarse prefix check.
     */
    sealed class OfficialBrowserTarget {
        data class ReleaseHtmlPage(val expectedTag: String) : OfficialBrowserTarget()
        data class ReleaseApkAsset(val expectedTag: String, val expectedAssetName: String) : OfficialBrowserTarget()
        object ReleasesIndexPage : OfficialBrowserTarget()
    }

    fun validateOfficialBrowserNavigationUrl(urlStr: String, target: OfficialBrowserTarget): String {
        return when (target) {
            is OfficialBrowserTarget.ReleaseHtmlPage ->
                validateOfficialReleaseHtmlUrl(urlStr, target.expectedTag)
            is OfficialBrowserTarget.ReleaseApkAsset ->
                validateOfficialAssetDownloadUrl(urlStr, target.expectedTag, target.expectedAssetName)
            is OfficialBrowserTarget.ReleasesIndexPage ->
                validateOfficialReleasesIndexUrl(urlStr)
        }
    }

    fun validateMetadataRedirectTargetUrl(urlStr: String): URI {
        val uri = parseStrictHttpsUri(urlStr, allowQuery = true)
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: throw ValidationException("INVALID_REDIRECT_HOST", "重定向地址缺少主机名")
        if (host !in ALLOWED_METADATA_REDIRECT_HOSTS) {
            throw ValidationException("UNTRUSTED_REDIRECT_HOST", "拒绝重定向到非官方域名: $host")
        }
        if (host == "github.com") {
            val rawPath = uri.rawPath ?: ""
            if (!rawPath.startsWith("/$EXPECTED_REPOSITORY/releases/download/")) {
                throw ValidationException("UNTRUSTED_GITHUB_REDIRECT_PATH", "拒绝重定向到非本仓库的 github.com 路径")
            }
        }
        return uri
    }

    private fun parseStrictHttpsUri(urlStr: String, allowQuery: Boolean = false): URI {
        val trimmed = urlStr.trim()
        if (trimmed.isEmpty() || trimmed != urlStr) {
            throw ValidationException("MALFORMED_URL", "URL 包含首尾空白或为空")
        }
        if (trimmed.contains("\\") || trimmed.contains("\u0000") || trimmed.contains("\r") || trimmed.contains("\n")) {
            throw ValidationException("MALFORMED_URL_CHARS", "URL 包含非法字符")
        }

        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw ValidationException("INVALID_URI_SYNTAX", "URL 语法无效")
        }

        if (uri.scheme?.lowercase(Locale.ROOT) != "https") {
            throw ValidationException("NON_HTTPS_URL", "仅允许 HTTPS 协议访问")
        }
        if (uri.rawUserInfo != null || uri.userInfo != null || trimmed.contains("@")) {
            throw ValidationException("URL_USERINFO_FORBIDDEN", "URL 不允许包含用户信息 (@)")
        }
        if (uri.rawFragment != null || uri.fragment != null || trimmed.contains("#")) {
            throw ValidationException("URL_FRAGMENT_FORBIDDEN", "URL 不允许包含锚点 (#)")
        }
        if (!allowQuery && (uri.rawQuery != null || uri.query != null || trimmed.contains("?"))) {
            throw ValidationException("URL_QUERY_FORBIDDEN", "URL 不允许包含查询参数 (?)")
        }
        if (uri.port != -1 && uri.port != 443) {
            throw ValidationException("URL_NON_STANDARD_PORT", "URL 不允许使用非标准 HTTPS 端口")
        }

        val rawAuthority = uri.rawAuthority ?: throw ValidationException("MISSING_AUTHORITY", "URL 缺少合法主机名")
        if (rawAuthority.contains("@")) {
            throw ValidationException("AUTHORITY_USERINFO_FORBIDDEN", "URL Authority 不允许包含 @")
        }
        val host = uri.host ?: throw ValidationException("MISSING_HOST", "URL 缺少合法域名")
        if (host.isBlank()) {
            throw ValidationException("EMPTY_HOST", "URL 域名为空")
        }

        val rawPath = uri.rawPath ?: throw ValidationException("MISSING_PATH", "URL 缺少路径")
        if (!rawPath.startsWith("/") || rawPath.contains("//")) {
            throw ValidationException("INVALID_RAW_PATH", "URL 路径格式非法")
        }

        val lowerRawPath = rawPath.lowercase(Locale.ROOT)
        if (
            lowerRawPath.contains("%2e") ||
            lowerRawPath.contains("%2f") ||
            lowerRawPath.contains("%5c") ||
            lowerRawPath.contains("%00")
        ) {
            throw ValidationException("ENCODED_PATH_TRAVERSAL", "检测到编码路径欺骗字符")
        }

        val decodedPath = try {
            URLDecoder.decode(rawPath, StandardCharsets.UTF_8.name())
        } catch (e: Exception) {
            throw ValidationException("PATH_DECODE_ERROR", "URL 路径解码失败")
        }
        if (decodedPath.contains("\\") || decodedPath.contains("\u0000") || decodedPath.contains("..")) {
            throw ValidationException("DECODED_PATH_TRAVERSAL", "检测到路径穿越字符")
        }

        val segments = rawPath.split("/").filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) {
            throw ValidationException("DOT_SEGMENT_IN_PATH", "URL 路径不允许包含 . 或 ..")
        }

        val normalized = uri.normalize()
        if (normalized.rawPath != rawPath) {
            throw ValidationException("UNNORMALIZED_URL_PATH", "URL 路径未规范化")
        }

        return uri
    }

    fun validateTagName(tag: String): String {
        if (!TAG_REGEX.matches(tag) || tag.contains("..")) {
            throw ValidationException("INVALID_TAG_NAME", "非法版本标签格式: $tag")
        }
        return tag
    }

    fun validateSafeAssetName(name: String): String {
        if (
            name.isEmpty() ||
            name == "." ||
            name == ".." ||
            name.contains("/") ||
            name.contains("\\") ||
            name.contains("..") ||
            !SAFE_ASSET_NAME_REGEX.matches(name)
        ) {
            throw ValidationException("UNSAFE_ASSET_NAME", "非法资产文件名: $name")
        }
        return name
    }

    fun canonicalApkName(tagName: String, abi: String = EXPECTED_ABI): String {
        validateTagName(tagName)
        if (abi != EXPECTED_ABI) {
            throw ValidationException("UNSUPPORTED_METADATA_ABI", "不支持的 APK ABI: $abi")
        }
        return validateSafeAssetName("voice-kiosk-$tagName-$abi.apk")
    }

    // --- Default Independent Unauthenticated HttpURLConnection Client ---

    class DefaultHttpClient(
        private val connectionFactory: (URL) -> HttpURLConnection = { url ->
            url.openConnection() as HttpURLConnection
        }
    ) : HttpClient {

        companion object {
            fun sanitizeOutboundHeaders(headers: Map<String, String>): Map<String, String> {
                val clean = LinkedHashMap<String, String>()
                for ((k, v) in headers) {
                    val lower = k.lowercase(Locale.ROOT).trim()
                    if (
                        lower == "authorization" ||
                        lower == "proxy-authorization" ||
                        lower == "cookie" ||
                        lower.contains("token") ||
                        lower.contains("secret")
                    ) {
                        throw SecurityException("Update HTTP client strictly forbids authentication/credential header: $k")
                    }
                    clean[k] = v
                }
                return clean
            }
        }

        override fun get(
            url: String,
            headers: Map<String, String>,
            maxBytes: Int,
            allowMetadataRedirects: Boolean
        ): HttpResponse {
            val safeHeaders = sanitizeOutboundHeaders(headers)
            var currentUrl = url
            var redirectCount = 0

            while (true) {
                if (redirectCount == 0) {
                    if (allowMetadataRedirects) {
                        // Initial metadata asset URL must be on official github.com download path
                        val uri = parseStrictHttpsUri(currentUrl, allowQuery = false)
                        if (uri.host?.lowercase(Locale.ROOT) != "github.com" ||
                            !(uri.rawPath ?: "").startsWith("/$EXPECTED_REPOSITORY/releases/download/")
                        ) {
                            throw ValidationException("UNTRUSTED_INITIAL_ASSET_URL", "初始元数据下载 URL 非本仓库官方路径")
                        }
                    } else {
                        validateOfficialApiUrl(currentUrl)
                    }
                }

                var conn: HttpURLConnection? = null
                try {
                    val parsedUrl = URL(currentUrl)
                    conn = connectionFactory(parsedUrl).apply {
                        requestMethod = "GET"
                        connectTimeout = CONNECT_TIMEOUT_MS
                        readTimeout = READ_TIMEOUT_MS
                        instanceFollowRedirects = false // Always validate redirects manually
                        useCaches = false
                        doInput = true
                        setRequestProperty("User-Agent", "VoiceKiosk-UpdateChecker/1.0")
                        for ((k, v) in safeHeaders) {
                            setRequestProperty(k, v)
                        }
                    }

                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        if (!allowMetadataRedirects) {
                            throw ValidationException("API_REDIRECT_FORBIDDEN", "GitHub Release API 响应不允许重定向")
                        }
                        if (redirectCount >= MAX_REDIRECTS) {
                            throw ValidationException("TOO_MANY_REDIRECTS", "元数据下载重定向次数超过上限 ($MAX_REDIRECTS)")
                        }
                        val location = conn.getHeaderField("Location")?.trim()
                            ?: throw ValidationException("MISSING_REDIRECT_LOCATION", "重定向响应缺少 Location 头")
                        val resolvedUri = try {
                            URI(currentUrl).resolve(location)
                        } catch (e: Exception) {
                            throw ValidationException("INVALID_REDIRECT_URI", "重定向地址解析失败")
                        }
                        val validatedTarget = validateMetadataRedirectTargetUrl(resolvedUri.toString())
                        currentUrl = validatedTarget.toString()
                        redirectCount++
                        continue
                    }

                    val responseHeaders = LinkedHashMap<String, String>()
                    val headerFields = conn.headerFields
                    if (headerFields != null) {
                        for ((k, values) in headerFields) {
                            if (k != null && !values.isNullOrEmpty()) {
                                responseHeaders[k] = values.joinToString(", ")
                            }
                        }
                    }

                    val declaredLength = conn.contentLengthLong
                    if (declaredLength > maxBytes) {
                        throw ResponseTooLargeException("响应大小 ($declaredLength 字节) 超过安全上限 ($maxBytes 字节)")
                    }

                    val bodyText = if (code == HttpURLConnection.HTTP_NOT_MODIFIED || code == HttpURLConnection.HTTP_NO_CONTENT) {
                        ""
                    } else if (code in 200..299) {
                        // Do NOT swallow IOException / SocketTimeoutException on 2xx inputStream!
                        // Allow it to propagate so executeWithSingleRetry can perform bounded retry.
                        val stream = conn.inputStream
                            ?: throw IOException("HTTP $code 响应未提供可读数据流 (inputStream == null)")
                        readBoundedUtf8String(stream, maxBytes)
                    } else {
                        // For non-2xx responses (404, 403, 429, 500, etc.), errorStream may be null or unavailable
                        val errStream: InputStream? = try {
                            conn.errorStream
                        } catch (_: Exception) {
                            null
                        }
                        if (errStream != null) {
                            try {
                                readBoundedUtf8String(errStream, maxBytes)
                            } catch (e: ResponseTooLargeException) {
                                throw e
                            } catch (_: IOException) {
                                ""
                            }
                        } else {
                            ""
                        }
                    }

                    return HttpResponse(
                        statusCode = code,
                        headers = responseHeaders,
                        body = bodyText,
                        finalUrl = currentUrl
                    )
                } finally {
                    try {
                        conn?.disconnect()
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun readBoundedUtf8String(stream: InputStream, maxBytes: Int): String {
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    total += read
                    if (total > maxBytes) {
                        throw ResponseTooLargeException("响应数据流超过安全上限 ($maxBytes 字节)")
                    }
                    out.write(buffer, 0, read)
                }
                return out.toString(StandardCharsets.UTF_8.name())
            }
        }
    }

    // --- Release & Metadata Parsing and Cross-Validation ---

    data class ParsedGitHubAsset(
        val name: String,
        val size: Long,
        val browserDownloadUrl: String
    )

    data class ParsedGitHubRelease(
        val tagName: String,
        val htmlUrl: String,
        val draft: Boolean,
        val prerelease: Boolean,
        val metadataAsset: ParsedGitHubAsset,
        val apkAsset: ParsedGitHubAsset
    )

    fun parseAndValidateGitHubReleaseJson(releaseJsonStr: String): ParsedGitHubRelease {
        if (releaseJsonStr.isBlank()) {
            throw ValidationException("EMPTY_RELEASE_JSON", "GitHub Release 响应为空")
        }
        val root = try {
            JSONObject(releaseJsonStr)
        } catch (e: JSONException) {
            throw ValidationException("INVALID_RELEASE_JSON", "GitHub Release 响应不是合法的 JSON")
        }

        if (!root.has("draft") || root.opt("draft") !is Boolean) {
            throw ValidationException("MISSING_DRAFT_FLAG", "Release 响应缺少布尔型 draft 字段")
        }
        if (!root.has("prerelease") || root.opt("prerelease") !is Boolean) {
            throw ValidationException("MISSING_PRERELEASE_FLAG", "Release 响应缺少布尔型 prerelease 字段")
        }
        val isDraft = root.getBoolean("draft")
        val isPrerelease = root.getBoolean("prerelease")
        if (isDraft) {
            throw ValidationException("RELEASE_IS_DRAFT", "拒绝使用草稿 (draft) 版本")
        }
        if (isPrerelease) {
            throw ValidationException("RELEASE_IS_PRERELEASE", "拒绝使用预发布 (prerelease) 版本")
        }

        val tagName = readStrictString(root, "tag_name", "INVALID_RELEASE_TAG")
        validateTagName(tagName)

        val rawHtmlUrl = readStrictString(root, "html_url", "INVALID_RELEASE_HTML_URL")
        val validatedHtmlUrl = validateOfficialReleaseHtmlUrl(rawHtmlUrl, tagName)

        val assetsArr = root.opt("assets")
        if (assetsArr !is JSONArray || assetsArr.length() == 0) {
            throw ValidationException("NO_RELEASE_ASSETS", "Release 不包含任何可下载资产")
        }

        val parsedAssets = LinkedHashMap<String, ParsedGitHubAsset>()
        for (i in 0 until assetsArr.length()) {
            val item = assetsArr.opt(i)
            if (item !is JSONObject) {
                throw ValidationException("INVALID_ASSET_ENTRY", "Release assets 条目格式错误")
            }
            val name = validateSafeAssetName(readStrictString(item, "name", "INVALID_ASSET_NAME"))
            val size = readStrictPositiveLong(item, "size", "INVALID_ASSET_SIZE")
            val downloadUrlRaw = readStrictString(item, "browser_download_url", "INVALID_ASSET_URL")
            val validatedDownloadUrl = validateOfficialAssetDownloadUrl(downloadUrlRaw, tagName, name)

            if (parsedAssets.containsKey(name)) {
                throw ValidationException("DUPLICATE_RELEASE_ASSET", "Release 中存在重复资产文件名: $name")
            }
            parsedAssets[name] = ParsedGitHubAsset(
                name = name,
                size = size,
                browserDownloadUrl = validatedDownloadUrl
            )
        }

        val metadataAsset = parsedAssets[METADATA_ASSET_NAME]
            ?: throw ValidationException("MISSING_METADATA_ASSET", "Release 缺少 $METADATA_ASSET_NAME 资产文件")
        if (metadataAsset.size > MAX_METADATA_RESPONSE_BYTES) {
            throw ValidationException("METADATA_ASSET_TOO_LARGE", "元数据文件大小超出限制")
        }

        val expectedApkFilename = canonicalApkName(tagName, EXPECTED_ABI)
        val apkAsset = parsedAssets[expectedApkFilename]
            ?: throw ValidationException("MISSING_CANONICAL_APK_ASSET", "Release 缺少标准 APK 资产 ($expectedApkFilename)")

        return ParsedGitHubRelease(
            tagName = tagName,
            htmlUrl = validatedHtmlUrl,
            draft = false,
            prerelease = false,
            metadataAsset = metadataAsset,
            apkAsset = apkAsset
        )
    }

    fun parseAndCrossValidateMetadata(
        metadataJsonStr: String,
        ghRelease: ParsedGitHubRelease
    ): ValidatedReleaseInfo {
        if (metadataJsonStr.isBlank()) {
            throw ValidationException("EMPTY_METADATA_JSON", "release-metadata.json 内容为空")
        }
        val meta = try {
            JSONObject(metadataJsonStr)
        } catch (e: JSONException) {
            throw ValidationException("INVALID_METADATA_JSON", "release-metadata.json 不是合法的 JSON")
        }

        val requiredFields = listOf(
            "schemaVersion",
            "repository",
            "applicationId",
            "versionCode",
            "versionName",
            "tagName",
            "minSdk",
            "targetSdk",
            "signingCertSha256",
            "apks"
        )
        for (field in requiredFields) {
            if (!meta.has(field) || meta.isNull(field)) {
                throw ValidationException("MISSING_METADATA_FIELD", "release-metadata.json 缺少必填字段: $field")
            }
        }

        val schemaVersion = readStrictPositiveLong(meta, "schemaVersion", "INVALID_SCHEMA_VERSION").toInt()
        if (schemaVersion != EXPECTED_SCHEMA_VERSION) {
            throw ValidationException("UNSUPPORTED_SCHEMA_VERSION", "不支持的元数据 schemaVersion: $schemaVersion")
        }

        val repository = readStrictString(meta, "repository", "INVALID_METADATA_REPOSITORY")
        if (repository != EXPECTED_REPOSITORY) {
            throw ValidationException("REPOSITORY_MISMATCH", "元数据仓库名 ($repository) 与官方仓库 ($EXPECTED_REPOSITORY) 不一致")
        }

        val applicationId = readStrictString(meta, "applicationId", "INVALID_METADATA_APP_ID")
        if (applicationId != EXPECTED_APPLICATION_ID) {
            throw ValidationException("METADATA_APP_ID_MISMATCH", "元数据 applicationId ($applicationId) 非法")
        }

        val versionCode = readStrictPositiveLong(meta, "versionCode", "INVALID_VERSION_CODE")
        val versionName = readStrictString(meta, "versionName", "INVALID_VERSION_NAME")
        val tagName = readStrictString(meta, "tagName", "INVALID_METADATA_TAG")
        validateTagName(tagName)

        if (tagName != "v$versionName") {
            throw ValidationException("TAG_VERSION_NAME_MISMATCH", "元数据 tagName ($tagName) 与 versionName ($versionName) 不匹配")
        }
        if (tagName != ghRelease.tagName) {
            throw ValidationException("RELEASE_TAG_MISMATCH", "元数据 tagName ($tagName) 与 GitHub Release 标签 (${ghRelease.tagName}) 不一致")
        }

        val minSdkLong = readStrictPositiveLong(meta, "minSdk", "INVALID_MIN_SDK")
        val targetSdkLong = readStrictPositiveLong(meta, "targetSdk", "INVALID_TARGET_SDK")
        if (minSdkLong > Int.MAX_VALUE || targetSdkLong > Int.MAX_VALUE) {
            throw ValidationException("SDK_OUT_OF_RANGE", "元数据 SDK 版本数值超出范围")
        }
        val minSdk = minSdkLong.toInt()
        val targetSdk = targetSdkLong.toInt()

        val signingCertSha256 = readStrictString(meta, "signingCertSha256", "INVALID_SIGNING_CERT")
        if (!SHA256_HEX_REGEX.matches(signingCertSha256)) {
            throw ValidationException("MALFORMED_SIGNING_CERT_SHA256", "元数据 signingCertSha256 必须为 64 位小写十六进制字符串")
        }

        val apksObj = meta.opt("apks")
        if (apksObj !is JSONArray || apksObj.length() != 1) {
            throw ValidationException("INVALID_APKS_ARRAY", "元数据 apks 必须是仅包含 1 个条目的数组")
        }

        val apkEntry = apksObj.opt(0)
        if (apkEntry !is JSONObject) {
            throw ValidationException("INVALID_APK_OBJECT", "元数据 apks[0] 必须是 JSON 对象")
        }
        for (k in listOf("abi", "name", "size", "sha256")) {
            if (!apkEntry.has(k) || apkEntry.isNull(k)) {
                throw ValidationException("MISSING_APK_ENTRY_FIELD", "元数据 apks[0] 缺少字段: $k")
            }
        }

        val abi = readStrictString(apkEntry, "abi", "INVALID_APK_ABI")
        if (abi != EXPECTED_ABI) {
            throw ValidationException("UNSUPPORTED_APK_ABI", "元数据 APK ABI ($abi) 与要求的 $EXPECTED_ABI 不一致")
        }

        val apkName = validateSafeAssetName(readStrictString(apkEntry, "name", "INVALID_APK_NAME"))
        val expectedCanonicalName = canonicalApkName(tagName, EXPECTED_ABI)
        if (apkName != expectedCanonicalName) {
            throw ValidationException("NON_CANONICAL_APK_NAME", "元数据 APK 文件名 ($apkName) 不符合标准命名 ($expectedCanonicalName)")
        }
        if (apkName != ghRelease.apkAsset.name) {
            throw ValidationException("APK_ASSET_NAME_MISMATCH", "元数据 APK 文件名与 GitHub Release 资产不一致")
        }

        val apkSize = readStrictPositiveLong(apkEntry, "size", "INVALID_APK_SIZE")
        if (apkSize != ghRelease.apkAsset.size) {
            throw ValidationException(
                "APK_ASSET_SIZE_MISMATCH",
                "元数据 APK 大小 ($apkSize) 与 GitHub Release 资产大小 (${ghRelease.apkAsset.size}) 不一致"
            )
        }

        val apkSha256 = readStrictString(apkEntry, "sha256", "INVALID_APK_SHA256")
        if (!SHA256_HEX_REGEX.matches(apkSha256)) {
            throw ValidationException("MALFORMED_APK_SHA256", "元数据 APK sha256 必须为 64 位小写十六进制字符串")
        }

        return ValidatedReleaseInfo(
            schemaVersion = schemaVersion,
            repository = repository,
            applicationId = applicationId,
            versionCode = versionCode,
            versionName = versionName,
            tagName = tagName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            signingCertSha256 = signingCertSha256,
            htmlUrl = ghRelease.htmlUrl,
            apk = ApkAssetMetadata(
                abi = abi,
                name = apkName,
                size = apkSize,
                sha256 = apkSha256,
                downloadUrl = ghRelease.apkAsset.browserDownloadUrl
            )
        )
    }

    private fun readStrictString(obj: JSONObject, key: String, errorCode: String): String {
        if (!obj.has(key) || obj.isNull(key)) {
            throw ValidationException(errorCode, "缺少字符串字段: $key")
        }
        val raw = obj.opt(key)
        if (raw !is String || raw.isBlank() || raw.trim() != raw) {
            throw ValidationException(errorCode, "字段 $key 必须是非空且无首尾空白的字符串")
        }
        return raw
    }

    private fun readStrictPositiveLong(obj: JSONObject, key: String, errorCode: String): Long {
        if (!obj.has(key) || obj.isNull(key)) {
            throw ValidationException(errorCode, "缺少整数字段: $key")
        }
        val raw = obj.opt(key)
        val value: Long = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            is Number -> {
                // Reject floating-point numbers or non-exact integer representations
                if (raw is Double || raw is Float) {
                    throw ValidationException(errorCode, "字段 $key 必须是整数，不能为浮点数")
                }
                raw.toLong()
            }
            else -> throw ValidationException(errorCode, "字段 $key 必须是正整数")
        }
        if (value <= 0L) {
            throw ValidationException(errorCode, "字段 $key 必须大于 0 (当前值: $value)")
        }
        return value
    }

    // --- Device Compatibility & Integer Version Evaluation ---

    fun evaluateReleaseAgainstDevice(
        installed: InstalledAppInfo,
        release: ValidatedReleaseInfo
    ): CheckResult {
        val localVersionCode = installed.versionCode
        val localVersionName = installed.versionName?.trim()?.takeIf { it.isNotEmpty() }
        if (!installed.hasValidVersionInfo || localVersionCode == null || localVersionCode <= 0L || localVersionName == null) {
            val errDetail = installed.packageReadError?.takeIf { it.isNotBlank() } ?: "PackageInfo 版本字段缺失或非法"
            return CheckResult.LocalVersionUnknown(
                reasonCode = "LOCAL_PACKAGE_VERSION_UNKNOWN",
                statusMessageZh = "无法读取本机版本（$errDetail），已中止更新比对，不提供覆盖升级引导"
            )
        }

        val localCertNormalized = installed.signingCertSha256
            ?.replace(":", "")
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }

        val signatureStatus = when {
            localCertNormalized == null -> SignatureTrustStatus.UNKNOWN_LOCAL_SIGNATURE
            localCertNormalized == release.signingCertSha256 -> SignatureTrustStatus.MATCHED
            else -> SignatureTrustStatus.MISMATCHED
        }

        // Compare strictly using Long versionCode integers, NEVER versionName strings
        val isNewerVersion = release.versionCode > localVersionCode

        if (!isNewerVersion) {
            val statusMsg = buildString {
                append("当前已是最新版本（当前 $localVersionName / versionCode $localVersionCode，")
                append("最新 Release ${release.tagName} / versionCode ${release.versionCode}）")
                if (signatureStatus == SignatureTrustStatus.MISMATCHED) {
                    append("。注意：当前设备安装包的签名证书与官方 Release 不一致。")
                } else if (signatureStatus == SignatureTrustStatus.UNKNOWN_LOCAL_SIGNATURE) {
                    append("。提示：未能读取当前安装包单一签名证书（未做本地证书比对）。")
                }
            }
            return CheckResult.UpToDate(
                currentVersionName = localVersionName,
                currentVersionCode = localVersionCode,
                latestRelease = release,
                signatureStatus = signatureStatus,
                statusMessageZh = statusMsg
            )
        }

        val incompatibilities = mutableListOf<IncompatibilityReason>()
        if (installed.applicationId != release.applicationId) {
            incompatibilities.add(IncompatibilityReason.APPLICATION_ID_MISMATCH)
        }
        if (installed.sdkInt < release.minSdk) {
            incompatibilities.add(IncompatibilityReason.SDK_TOO_LOW)
        }
        val normalizedAbis = installed.supportedAbis.map { it.trim() }
        if (release.apk.abi !in normalizedAbis) {
            incompatibilities.add(IncompatibilityReason.ABI_UNSUPPORTED)
        }
        if (signatureStatus == SignatureTrustStatus.MISMATCHED) {
            incompatibilities.add(IncompatibilityReason.SIGNATURE_MISMATCH)
        }

        if (incompatibilities.isNotEmpty()) {
            val reasonDescriptions = incompatibilities.map { reason ->
                when (reason) {
                    IncompatibilityReason.APPLICATION_ID_MISMATCH ->
                        "应用包名不一致（当前 ${installed.applicationId}，新版本 ${release.applicationId}）"
                    IncompatibilityReason.SDK_TOO_LOW ->
                        "系统 Android SDK 版本过低（当前 API ${installed.sdkInt}，新版本要求最低 API ${release.minSdk}）"
                    IncompatibilityReason.ABI_UNSUPPORTED ->
                        "设备 CPU 架构不兼容（新版本要求 ${release.apk.abi}，当前设备支持 ${normalizedAbis.joinToString("/")}）"
                    IncompatibilityReason.SIGNATURE_MISMATCH ->
                        "签名证书不一致（当前应用可能为未签名/调试构建或不同密钥签名，无法直接覆盖安装官方正式版，需先卸载旧版或核对签名）"
                }
            }
            val msg = "发现新版本 ${release.tagName}（versionCode ${release.versionCode}），但无法直接覆盖升级：" +
                reasonDescriptions.joinToString("；")
            return CheckResult.IncompatibleUpgrade(
                currentVersionName = localVersionName,
                currentVersionCode = localVersionCode,
                release = release,
                reasons = incompatibilities,
                signatureStatus = signatureStatus,
                statusMessageZh = msg
            )
        }

        val msg = when (signatureStatus) {
            SignatureTrustStatus.MATCHED ->
                "发现可用更新 ${release.tagName}（versionCode ${release.versionCode}，签名证书校验一致，支持浏览器下载覆盖安装）"
            SignatureTrustStatus.UNKNOWN_LOCAL_SIGNATURE ->
                "发现新版本 ${release.tagName}（versionCode ${release.versionCode}）。提示：当前设备未能读取本地应用单一签名证书（非安全匹配保证，若当前为未签名、多签或不同签名构建，覆盖安装可能失败）"
            SignatureTrustStatus.MISMATCHED ->
                "签名冲突，禁止覆盖安装" // Handled above in incompatibilities
        }

        return CheckResult.UpdateAvailable(
            currentVersionName = localVersionName,
            currentVersionCode = localVersionCode,
            release = release,
            signatureStatus = signatureStatus,
            statusMessageZh = msg
        )
    }

    // --- Rate Limit Header Parsing ---

    fun computeRateLimitCooldownUntilMs(
        headers: Map<String, String>,
        nowEpochMs: Long
    ): Long {
        val retryAfterRaw = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.trim()
        if (!retryAfterRaw.isNullOrEmpty()) {
            val seconds = retryAfterRaw.toLongOrNull()
            if (seconds != null && seconds > 0L) {
                val deltaMs = (seconds * 1000L).coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MS)
                return nowEpochMs + deltaMs
            }
        }

        val resetRaw = headers.entries.firstOrNull { it.key.equals("X-RateLimit-Reset", ignoreCase = true) }?.value?.trim()
        if (!resetRaw.isNullOrEmpty()) {
            val resetEpochSec = resetRaw.toLongOrNull()
            if (resetEpochSec != null && resetEpochSec > 0L) {
                val resetEpochMs = resetEpochSec * 1000L
                if (resetEpochMs > nowEpochMs) {
                    val deltaMs = (resetEpochMs - nowEpochMs).coerceAtMost(MAX_RATE_LIMIT_COOLDOWN_MS)
                    return nowEpochMs + deltaMs
                }
            }
        }

        return nowEpochMs + DEFAULT_RATE_LIMIT_COOLDOWN_MS
    }

    // --- Coordinator: Caching, Retry, Single-Flight, and Lifecycle-Safe Callbacks ---

    interface Listener {
        fun onUpdateCheckStateChanged(inFlight: Boolean, lastResult: CheckResult?)
    }

    class Manager(
        private val httpClient: HttpClient = DefaultHttpClient(),
        private val clock: Clock = SystemClock,
        private val cacheStore: CacheStore = InMemoryCacheStore(),
        private val installedAppProvider: () -> InstalledAppInfo,
        private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "VoiceKiosk-UpdateCheck").apply { isDaemon = true }
        },
        private val mainThreadPoster: (Runnable) -> Unit
    ) {
        private val listeners = CopyOnWriteArraySet<Listener>()
        private val inFlight = AtomicBoolean(false)

        @Volatile
        var lastResult: CheckResult? = null
            private set

        @Volatile
        private var lastManualCheckStartEpochMs: Long = 0L

        fun isCheckInFlight(): Boolean = inFlight.get()

        fun registerListener(listener: Listener) {
            listeners.add(listener)
            val currentInFlight = inFlight.get()
            val currentRes = lastResult
            mainThreadPoster(Runnable {
                if (listeners.contains(listener)) {
                    listener.onUpdateCheckStateChanged(currentInFlight, currentRes)
                }
            })
        }

        fun unregisterListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun listenerCount(): Int = listeners.size

        /**
         * Trigger an asynchronous update check on the bounded single-thread background executor.
         * - Enforces single-flight deduplication (`inFlight`).
         * - Enforces `MANUAL_CHECK_COOLDOWN_MS` (unless `bypassManualCooldown = true`) so rapid manual clicks
         *   are throttled at the Manager layer rather than relying solely on transient UI button disabling.
         * - If `backgroundExecutor.execute` throws (e.g. `RejectedExecutionException`), immediately resets `inFlight`,
         *   records a failure `lastResult`, and notifies listeners so the UI never stays stuck in checking state.
         */
        fun triggerCheck(forceNetwork: Boolean = false, bypassManualCooldown: Boolean = false): Boolean {
            val now = clock.nowMs()
            if (!bypassManualCooldown) {
                val lastManual = lastManualCheckStartEpochMs
                if (lastManual > 0L && (now - lastManual) in 0 until MANUAL_CHECK_COOLDOWN_MS) {
                    return false
                }
            }
            if (!inFlight.compareAndSet(false, true)) {
                return false
            }
            if (!bypassManualCooldown) {
                lastManualCheckStartEpochMs = now
            }
            notifyListeners(inFlightState = true, result = lastResult)

            val task = Runnable {
                val result = try {
                    performCheckBlocking(forceNetwork = forceNetwork)
                } catch (t: Throwable) {
                    CheckResult.ValidationFailure(
                        reasonCode = "UNEXPECTED_ERROR",
                        statusMessageZh = "检查更新出错：${t.message ?: "未知异常"}"
                    )
                } finally {
                    inFlight.set(false)
                }
                lastResult = result
                notifyListeners(inFlightState = false, result = result)
            }

            return try {
                backgroundExecutor.execute(task)
                true
            } catch (t: Throwable) {
                inFlight.set(false)
                val rejectedResult = CheckResult.ValidationFailure(
                    reasonCode = "EXECUTOR_REJECTED",
                    statusMessageZh = "后台检查任务启动失败（线程池拒绝执行），请稍后重试"
                )
                lastResult = rejectedResult
                notifyListeners(inFlightState = false, result = rejectedResult)
                false
            }
        }

        /**
         * Trigger an optional startup background check ONLY IF:
         * 1. `startupCheckEnabled == true` (default is false)
         * 2. At least 24 hours (`STARTUP_CHECK_INTERVAL_MS`) have elapsed since the last startup check.
         */
        fun maybeTriggerStartupCheck(startupCheckEnabled: Boolean): Boolean {
            if (!startupCheckEnabled) {
                return false
            }
            val now = clock.nowMs()
            val lastStartupMs = cacheStore.readLastStartupCheckMs()
            if (lastStartupMs > 0L && (now - lastStartupMs) < STARTUP_CHECK_INTERVAL_MS) {
                return false
            }
            cacheStore.writeLastStartupCheckMs(now)
            return triggerCheck(forceNetwork = false, bypassManualCooldown = true)
        }

        /**
         * Synchronous update check logic (executed on background executor or directly in unit tests).
         */
        fun performCheckBlocking(forceNetwork: Boolean = false): CheckResult {
            val now = clock.nowMs()
            val installed = try {
                installedAppProvider()
            } catch (t: Throwable) {
                return CheckResult.LocalVersionUnknown(
                    reasonCode = "LOCAL_PACKAGE_READ_EXCEPTION",
                    statusMessageZh = "无法读取本机版本（${t.message ?: "PackageManager 异常"}），已停止检查更新且不提供升级引导"
                )
            }
            if (!installed.hasValidVersionInfo) {
                val errDetail = installed.packageReadError?.takeIf { it.isNotBlank() } ?: "无法获取有效的 versionCode 或 versionName"
                return CheckResult.LocalVersionUnknown(
                    reasonCode = "LOCAL_PACKAGE_VERSION_UNKNOWN",
                    statusMessageZh = "无法读取本机版本（$errDetail），已停止检查更新且不提供升级引导"
                )
            }

            val cachedState = cacheStore.read()

            // 1. Check active rate-limit cooldown window
            val rateLimitUntil = maxOf(
                cacheStore.readRateLimitUntilMs(),
                cachedState?.rateLimitUntilEpochMs ?: 0L
            )
            if (rateLimitUntil > now) {
                val remainingSec = ((rateLimitUntil - now + 999L) / 1000L).coerceAtLeast(1L)
                return CheckResult.RateLimited(
                    retryAfterEpochMs = rateLimitUntil,
                    remainingSeconds = remainingSec,
                    statusMessageZh = "GitHub API 访问受限（频控保护中），请在约 ${remainingSec} 秒后再试"
                )
            }

            // 2. If not forcing network and cache is fresh within TTL (and metadata itself is within MAX_ETAG_304_TRUST_AGE_MS),
            //    evaluate cached validated data
            if (!forceNetwork && cachedState != null) {
                val ageMs = now - cachedState.cachedAtEpochMs
                val metadataAgeMs = now - cachedState.metadataFetchedAtEpochMs
                if (ageMs in 0..CACHE_TTL_MS &&
                    metadataAgeMs in 0..MAX_ETAG_304_TRUST_AGE_MS &&
                    cachedState.releaseJson.isNotBlank() &&
                    cachedState.metadataJson.isNotBlank()
                ) {
                    try {
                        val ghRelease = parseAndValidateGitHubReleaseJson(cachedState.releaseJson)
                        val validated = parseAndCrossValidateMetadata(cachedState.metadataJson, ghRelease)
                        return evaluateReleaseAgainstDevice(installed, validated)
                    } catch (_: ValidationException) {
                        // Corrupted cache entry; fall through to network fetch
                    }
                }
            }

            // 3. Build unauthenticated request headers.
            //    IMPORTANT: A 304 on /releases/latest does NOT guarantee that mutable release assets
            //    (such as release-metadata.json replaced under the same tag) are unchanged forever.
            //    Therefore, only send If-None-Match when cached metadata is still within MAX_ETAG_304_TRUST_AGE_MS
            //    and forceNetwork is false.
            val canUseConditionalEtag = !forceNetwork &&
                cachedState != null &&
                !cachedState.etag.isNullOrBlank() &&
                cachedState.releaseJson.isNotBlank() &&
                cachedState.metadataJson.isNotBlank() &&
                (now - cachedState.metadataFetchedAtEpochMs) in 0..MAX_ETAG_304_TRUST_AGE_MS

            val requestHeaders = LinkedHashMap<String, String>()
            requestHeaders["Accept"] = "application/vnd.github+json"
            if (canUseConditionalEtag && cachedState != null && !cachedState.etag.isNullOrBlank()) {
                requestHeaders["If-None-Match"] = cachedState.etag
            }

            // 4. Execute GET to official GitHub releases/latest API with bounded retry (at most 1 retry on transient IO/timeout)
            val apiResponse = try {
                executeWithSingleRetry {
                    httpClient.get(
                        url = LATEST_RELEASE_API_URL,
                        headers = requestHeaders,
                        maxBytes = MAX_API_RESPONSE_BYTES,
                        allowMetadataRedirects = false
                    )
                }
            } catch (e: ValidationException) {
                return CheckResult.ValidationFailure(e.code, "更新校验失败：${e.messageZh}")
            } catch (e: ResponseTooLargeException) {
                return CheckResult.ValidationFailure("API_RESPONSE_TOO_LARGE", "更新校验失败：${e.message}")
            } catch (e: SocketTimeoutException) {
                return CheckResult.NetworkFailure(
                    isTimeout = true,
                    statusMessageZh = "检查更新超时，请检查网络连接后重试"
                )
            } catch (e: IOException) {
                return CheckResult.NetworkFailure(
                    isTimeout = false,
                    statusMessageZh = "当前网络不可用，无法连接 GitHub 检查更新"
                )
            }

            val currentNow = clock.nowMs()

            when (apiResponse.statusCode) {
                304 -> {
                    // HTTP 304 Not Modified:
                    // Even on 304, never infinitely extend trust in old metadataJson if metadataFetchedAtEpochMs
                    // has exceeded MAX_ETAG_304_TRUST_AGE_MS (protects against same-tag asset replacement).
                    if (cachedState == null ||
                        cachedState.releaseJson.isBlank() ||
                        cachedState.metadataJson.isBlank()
                    ) {
                        return CheckResult.ValidationFailure(
                            reasonCode = "HTTP_304_WITHOUT_CACHE",
                            statusMessageZh = "服务器返回 304 但本地无有效缓存，请重试"
                        )
                    }
                    val ghRelease = try {
                        parseAndValidateGitHubReleaseJson(cachedState.releaseJson)
                    } catch (e: ValidationException) {
                        return CheckResult.ValidationFailure(e.code, "缓存 Release 校验失败：${e.messageZh}")
                    }

                    val metadataAgeMs = currentNow - cachedState.metadataFetchedAtEpochMs
                    if (metadataAgeMs < 0L || metadataAgeMs > MAX_ETAG_304_TRUST_AGE_MS) {
                        // Metadata asset trust window expired: re-fetch release-metadata.json from network
                        return fetchAndValidateMetadataForRelease(
                            ghRelease = ghRelease,
                            releaseJsonRaw = cachedState.releaseJson,
                            etag = apiResponse.header("ETag")?.trim()?.takeIf { it.isNotEmpty() } ?: cachedState.etag,
                            installed = installed,
                            nowEpochMs = currentNow
                        )
                    }

                    return try {
                        val validated = parseAndCrossValidateMetadata(cachedState.metadataJson, ghRelease)
                        // Refresh cachedAtEpochMs for the 15-min API TTL, but preserve original metadataFetchedAtEpochMs
                        // so 304 responses cannot indefinitely postpone metadata asset re-verification!
                        val refreshed = cachedState.copy(
                            cachedAtEpochMs = currentNow,
                            metadataFetchedAtEpochMs = cachedState.metadataFetchedAtEpochMs
                        )
                        cacheStore.write(refreshed)
                        evaluateReleaseAgainstDevice(installed, validated)
                    } catch (e: ValidationException) {
                        CheckResult.ValidationFailure(e.code, "缓存元数据校验失败：${e.messageZh}")
                    }
                }

                404 -> {
                    return CheckResult.NoPublishedRelease(
                        officialReleasesUrl = OFFICIAL_RELEASES_HTML_URL,
                        statusMessageZh = "官方仓库暂未发布正式 Release 版本（可前往 GitHub Releases 页面查看）"
                    )
                }

                403, 429 -> {
                    val cooldownUntil = computeRateLimitCooldownUntilMs(apiResponse.headers, currentNow)
                    cacheStore.writeRateLimitUntilMs(cooldownUntil)
                    if (cachedState != null) {
                        cacheStore.write(cachedState.copy(rateLimitUntilEpochMs = cooldownUntil))
                    }
                    val remainingSec = ((cooldownUntil - currentNow + 999L) / 1000L).coerceAtLeast(1L)
                    return CheckResult.RateLimited(
                        retryAfterEpochMs = cooldownUntil,
                        remainingSeconds = remainingSec,
                        statusMessageZh = "GitHub API 访问频率受限（HTTP ${apiResponse.statusCode}），请在约 ${remainingSec} 秒后再试"
                    )
                }

                200 -> {
                    return handleHttp200ReleaseResponse(
                        apiResponse = apiResponse,
                        installed = installed,
                        nowEpochMs = currentNow
                    )
                }

                else -> {
                    return CheckResult.NetworkFailure(
                        isTimeout = false,
                        statusMessageZh = "GitHub API 返回异常状态码（HTTP ${apiResponse.statusCode}），请稍后重试"
                    )
                }
            }
        }

        private fun handleHttp200ReleaseResponse(
            apiResponse: HttpResponse,
            installed: InstalledAppInfo,
            nowEpochMs: Long
        ): CheckResult {
            val ghRelease = try {
                parseAndValidateGitHubReleaseJson(apiResponse.body)
            } catch (e: ValidationException) {
                return CheckResult.ValidationFailure(e.code, "Release 信息校验未通过：${e.messageZh}")
            }

            val etag = apiResponse.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
            return fetchAndValidateMetadataForRelease(
                ghRelease = ghRelease,
                releaseJsonRaw = apiResponse.body,
                etag = etag,
                installed = installed,
                nowEpochMs = nowEpochMs
            )
        }

        private fun fetchAndValidateMetadataForRelease(
            ghRelease: ParsedGitHubRelease,
            releaseJsonRaw: String,
            etag: String?,
            installed: InstalledAppInfo,
            nowEpochMs: Long
        ): CheckResult {
            // Download release-metadata.json from the validated official asset URL
            val metadataHeaders = mapOf("Accept" to "application/octet-stream")
            val metadataResponse = try {
                executeWithSingleRetry {
                    httpClient.get(
                        url = ghRelease.metadataAsset.browserDownloadUrl,
                        headers = metadataHeaders,
                        maxBytes = MAX_METADATA_RESPONSE_BYTES,
                        allowMetadataRedirects = true
                    )
                }
            } catch (e: ValidationException) {
                return CheckResult.ValidationFailure(e.code, "元数据下载安全校验未通过：${e.messageZh}")
            } catch (e: ResponseTooLargeException) {
                return CheckResult.ValidationFailure("METADATA_RESPONSE_TOO_LARGE", "元数据文件超过安全大小上限")
            } catch (e: SocketTimeoutException) {
                return CheckResult.NetworkFailure(
                    isTimeout = true,
                    statusMessageZh = "下载版本元数据超时，请稍后重试"
                )
            } catch (e: IOException) {
                return CheckResult.NetworkFailure(
                    isTimeout = false,
                    statusMessageZh = "下载版本元数据失败，请检查网络连接"
                )
            }

            if (metadataResponse.statusCode == 403 || metadataResponse.statusCode == 429) {
                val cooldownUntil = computeRateLimitCooldownUntilMs(metadataResponse.headers, nowEpochMs)
                cacheStore.writeRateLimitUntilMs(cooldownUntil)
                val remainingSec = ((cooldownUntil - nowEpochMs + 999L) / 1000L).coerceAtLeast(1L)
                return CheckResult.RateLimited(
                    retryAfterEpochMs = cooldownUntil,
                    remainingSeconds = remainingSec,
                    statusMessageZh = "下载元数据时触发 GitHub 频控（HTTP ${metadataResponse.statusCode}），请稍后再试"
                )
            }

            if (metadataResponse.statusCode != 200) {
                return CheckResult.NetworkFailure(
                    isTimeout = false,
                    statusMessageZh = "获取 release-metadata.json 失败（HTTP ${metadataResponse.statusCode}）"
                )
            }

            val actualMetaBytes = metadataResponse.body.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (actualMetaBytes != ghRelease.metadataAsset.size) {
                return CheckResult.ValidationFailure(
                    reasonCode = "METADATA_SIZE_MISMATCH",
                    statusMessageZh = "release-metadata.json 实际字节数 ($actualMetaBytes) 与 Release 声明大小 (${ghRelease.metadataAsset.size}) 不一致"
                )
            }

            val validatedRelease = try {
                parseAndCrossValidateMetadata(metadataResponse.body, ghRelease)
            } catch (e: ValidationException) {
                return CheckResult.ValidationFailure(e.code, "版本元数据校验未通过：${e.messageZh}")
            }

            cacheStore.write(
                CachedReleaseState(
                    etag = etag,
                    releaseJson = releaseJsonRaw,
                    metadataJson = metadataResponse.body,
                    cachedAtEpochMs = nowEpochMs,
                    rateLimitUntilEpochMs = 0L,
                    lastStartupCheckEpochMs = cacheStore.readLastStartupCheckMs(),
                    metadataFetchedAtEpochMs = nowEpochMs
                )
            )

            return evaluateReleaseAgainstDevice(installed, validatedRelease)
        }

        private fun executeWithSingleRetry(block: () -> HttpResponse): HttpResponse {
            var firstException: IOException? = null
            for (attempt in 0..1) {
                try {
                    return block()
                } catch (e: ValidationException) {
                    throw e
                } catch (e: ResponseTooLargeException) {
                    throw e
                } catch (e: IOException) {
                    if (attempt == 0) {
                        firstException = e
                    } else {
                        throw e
                    }
                }
            }
            throw firstException ?: IOException("Network request failed")
        }

        private fun notifyListeners(inFlightState: Boolean, result: CheckResult?) {
            val snapshot = listeners.toList()
            if (snapshot.isEmpty()) return
            mainThreadPoster(Runnable {
                for (listener in snapshot) {
                    if (listeners.contains(listener)) {
                        listener.onUpdateCheckStateChanged(inFlightState, result)
                    }
                }
            })
        }
    }
}
