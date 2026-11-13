package com.voicekiosk.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReleaseUpdateCheckerTest {

    companion object {
        private const val SAMPLE_CERT_SHA256 =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0"
        private const val OTHER_CERT_SHA256 =
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        private const val SAMPLE_APK_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        fun buildValidMetadataJson(
            tagName: String = "v0.2.0",
            versionName: String = "0.2.0",
            versionCode: Long = 3L,
            repository: String = "kqstone/voice-kiosk-relay",
            applicationId: String = "com.voicekiosk.app",
            minSdk: Int = 28,
            targetSdk: Int = 31,
            signingCertSha256: String = SAMPLE_CERT_SHA256,
            abi: String = "arm64-v8a",
            apkName: String = "voice-kiosk-$tagName-$abi.apk",
            apkSize: Long = 10_485_760L,
            apkSha256: String = SAMPLE_APK_SHA256,
            schemaVersion: Int = 1
        ): String {
            return JSONObject().apply {
                put("schemaVersion", schemaVersion)
                put("repository", repository)
                put("applicationId", applicationId)
                put("versionCode", versionCode)
                put("versionName", versionName)
                put("tagName", tagName)
                put("minSdk", minSdk)
                put("targetSdk", targetSdk)
                put("signingCertSha256", signingCertSha256)
                put(
                    "apks",
                    JSONArray().put(
                        JSONObject().apply {
                            put("abi", abi)
                            put("name", apkName)
                            put("size", apkSize)
                            put("sha256", apkSha256)
                        }
                    )
                )
            }.toString()
        }

        fun buildValidGitHubReleaseJson(
            tagName: String = "v0.2.0",
            draft: Boolean = false,
            prerelease: Boolean = false,
            htmlUrl: String = "https://github.com/kqstone/voice-kiosk-relay/releases/tag/$tagName",
            apkName: String = "voice-kiosk-$tagName-arm64-v8a.apk",
            apkSize: Long = 10_485_760L,
            apkUrl: String = "https://github.com/kqstone/voice-kiosk-relay/releases/download/$tagName/$apkName",
            metadataContent: String = buildValidMetadataJson(tagName = tagName, apkName = apkName, apkSize = apkSize),
            metadataUrl: String = "https://github.com/kqstone/voice-kiosk-relay/releases/download/$tagName/release-metadata.json",
            includeApkAsset: Boolean = true,
            includeMetadataAsset: Boolean = true
        ): String {
            val metadataBytes = metadataContent.toByteArray(StandardCharsets.UTF_8).size.toLong()
            val assets = JSONArray()
            if (includeApkAsset) {
                assets.put(
                    JSONObject().apply {
                        put("name", apkName)
                        put("size", apkSize)
                        put("browser_download_url", apkUrl)
                    }
                )
            }
            if (includeMetadataAsset) {
                assets.put(
                    JSONObject().apply {
                        put("name", "release-metadata.json")
                        put("size", metadataBytes)
                        put("browser_download_url", metadataUrl)
                    }
                )
            }
            return JSONObject().apply {
                put("tag_name", tagName)
                put("html_url", htmlUrl)
                put("draft", draft)
                put("prerelease", prerelease)
                put("assets", assets)
            }.toString()
        }

        fun defaultInstalledDevice(
            versionCode: Long? = 2L,
            versionName: String? = "0.1.0",
            sdkInt: Int = 29,
            supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
            signingCertSha256: String? = SAMPLE_CERT_SHA256,
            applicationId: String = "com.voicekiosk.app",
            packageReadError: String? = null
        ): ReleaseUpdateChecker.InstalledAppInfo {
            return ReleaseUpdateChecker.InstalledAppInfo(
                applicationId = applicationId,
                versionCode = versionCode,
                versionName = versionName,
                sdkInt = sdkInt,
                supportedAbis = supportedAbis,
                signingCertSha256 = signingCertSha256,
                packageReadError = packageReadError
            )
        }
    }

    private class FakeClock(var currentMs: Long = 1_700_000_000_000L) : ReleaseUpdateChecker.Clock {
        override fun nowMs(): Long = currentMs
    }

    // 1. Integer versionCode comparison (never string comparison on versionName)
    @Test
    fun testVersionComparisonUsesLongVersionCodeNotVersionNameString() {
        // Case A: Installed versionName="0.10.0" (code 2), remote versionName="0.2.0" (code 10)
        // Lexicographical string compare on "0.2.0" vs "0.10.0" or "2" vs "10" could be tricky,
        // so verify Long comparison strictly governs UpdateAvailable vs UpToDate.
        val metaJsonHigherCode = buildValidMetadataJson(
            tagName = "v0.2.0",
            versionName = "0.2.0",
            versionCode = 10L
        )
        val ghJsonHigherCode = buildValidGitHubReleaseJson(
            tagName = "v0.2.0",
            metadataContent = metaJsonHigherCode
        )
        val parsedHigher = ReleaseUpdateChecker.parseAndCrossValidateMetadata(
            metaJsonHigherCode,
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(ghJsonHigherCode)
        )

        val installedLowerCode = defaultInstalledDevice(versionCode = 9L, versionName = "9.9.9")
        val resultUpgrade = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(installedLowerCode, parsedHigher)
        assertTrue(resultUpgrade is ReleaseUpdateChecker.CheckResult.UpdateAvailable)

        // Case B: Remote versionName looks "higher" alphabetically ("0.9.0") than installed ("0.10.0"),
        // but remote versionCode (5) <= installed versionCode (12) -> must be UpToDate!
        val metaJsonLowerCode = buildValidMetadataJson(
            tagName = "v0.9.0",
            versionName = "0.9.0",
            versionCode = 5L
        )
        val ghJsonLowerCode = buildValidGitHubReleaseJson(
            tagName = "v0.9.0",
            metadataContent = metaJsonLowerCode
        )
        val parsedLower = ReleaseUpdateChecker.parseAndCrossValidateMetadata(
            metaJsonLowerCode,
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(ghJsonLowerCode)
        )
        val installedHigherCode = defaultInstalledDevice(versionCode = 12L, versionName = "0.10.0")
        val resultUpToDate = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(installedHigherCode, parsedLower)
        assertTrue(resultUpToDate is ReleaseUpdateChecker.CheckResult.UpToDate)

        // Case C: Large 64-bit Long versionCode values (> Int.MAX_VALUE)
        val largeRemoteCode = Int.MAX_VALUE.toLong() + 500L
        val largeInstalledCode = Int.MAX_VALUE.toLong() + 100L
        val metaLarge = buildValidMetadataJson(versionCode = largeRemoteCode)
        val ghLarge = buildValidGitHubReleaseJson(metadataContent = metaLarge)
        val parsedLarge = ReleaseUpdateChecker.parseAndCrossValidateMetadata(
            metaLarge,
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(ghLarge)
        )
        val resLarge = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(
            defaultInstalledDevice(versionCode = largeInstalledCode),
            parsedLarge
        )
        assertTrue(resLarge is ReleaseUpdateChecker.CheckResult.UpdateAvailable)
        assertEquals(largeRemoteCode, (resLarge as ReleaseUpdateChecker.CheckResult.UpdateAvailable).release.versionCode)
    }

    // 2. Reject draft, prerelease, missing APK, or missing/corrupt metadata
    @Test
    fun testRejectsDraftPrereleaseMissingAssetsAndCorruptMetadata() {
        // Draft release rejected
        val draftJson = buildValidGitHubReleaseJson(draft = true)
        try {
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(draftJson)
            fail("Expected draft release to be rejected")
        } catch (e: ReleaseUpdateChecker.ValidationException) {
            assertEquals("RELEASE_IS_DRAFT", e.code)
        }

        // Prerelease rejected
        val prereleaseJson = buildValidGitHubReleaseJson(prerelease = true)
        try {
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(prereleaseJson)
            fail("Expected prerelease to be rejected")
        } catch (e: ReleaseUpdateChecker.ValidationException) {
            assertEquals("RELEASE_IS_PRERELEASE", e.code)
        }

        // Missing APK asset rejected
        val noApkJson = buildValidGitHubReleaseJson(includeApkAsset = false)
        try {
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(noApkJson)
            fail("Expected release without APK asset to be rejected")
        } catch (e: ReleaseUpdateChecker.ValidationException) {
            assertEquals("MISSING_CANONICAL_APK_ASSET", e.code)
        }

        // Missing release-metadata.json asset rejected
        val noMetaJson = buildValidGitHubReleaseJson(includeMetadataAsset = false)
        try {
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(noMetaJson)
            fail("Expected release without metadata asset to be rejected")
        } catch (e: ReleaseUpdateChecker.ValidationException) {
            assertEquals("MISSING_METADATA_ASSET", e.code)
        }

        // Metadata tag mismatch against GitHub release tag
        val metaWrongTag = buildValidMetadataJson(tagName = "v0.3.0", versionName = "0.3.0")
        val ghReleaseV020 = ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(
            buildValidGitHubReleaseJson(tagName = "v0.2.0", metadataContent = metaWrongTag)
        )
        try {
            ReleaseUpdateChecker.parseAndCrossValidateMetadata(metaWrongTag, ghReleaseV020)
            fail("Expected tag mismatch between metadata and GitHub release to fail")
        } catch (e: ReleaseUpdateChecker.ValidationException) {
            assertEquals("RELEASE_TAG_MISMATCH", e.code)
        }

        // Metadata APK size mismatch against GitHub release asset size
        val metaWrongSize = buildValidMetadataJson(apkSize = 999_999L)
        val ghReleaseSizeMismatch = ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(
            buildValidGitHubReleaseJson(apkSize = 10_485_760L, metadataContent = metaWrongSize)
        )
        try {
            ReleaseUpdateChecker.parseAndCrossValidateMetadata(metaWrongSize, ghReleaseSizeMismatch)
            fail("Expected APK size mismatch to fail")
        } catch (e: ReleaseUpdateChecker.ValidationException) {
            assertEquals("APK_ASSET_SIZE_MISMATCH", e.code)
        }

        // Metadata wrong repository (arbitrary fork and old repository kqstone/voice-kiosk)
        for (wrongRepo in listOf("evil/fork-kiosk", "kqstone/voice-kiosk")) {
            val metaWrongRepo = buildValidMetadataJson(repository = wrongRepo)
            val ghReleaseRepo = ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(
                buildValidGitHubReleaseJson(metadataContent = metaWrongRepo)
            )
            try {
                ReleaseUpdateChecker.parseAndCrossValidateMetadata(metaWrongRepo, ghReleaseRepo)
                fail("Expected wrong repository in metadata to fail: $wrongRepo")
            } catch (e: ReleaseUpdateChecker.ValidationException) {
                assertEquals("REPOSITORY_MISMATCH", e.code)
            }
        }
    }

    // 3. SDK, ABI, and Signing Certificate Compatibility (Matched, Unknown, Mismatched)
    @Test
    fun testSdkAbiAndSignatureCompatibilityHandling() {
        val metaJson = buildValidMetadataJson(minSdk = 28, abi = "arm64-v8a", signingCertSha256 = SAMPLE_CERT_SHA256)
        val ghJson = buildValidGitHubReleaseJson(metadataContent = metaJson)
        val release = ReleaseUpdateChecker.parseAndCrossValidateMetadata(
            metaJson,
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(ghJson)
        )

        // 1. SDK too low (API 26 < minSdk 28) -> IncompatibleUpgrade
        val lowSdkDevice = defaultInstalledDevice(sdkInt = 26)
        val sdkResult = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(lowSdkDevice, release)
        assertTrue(sdkResult is ReleaseUpdateChecker.CheckResult.IncompatibleUpgrade)
        assertTrue(
            (sdkResult as ReleaseUpdateChecker.CheckResult.IncompatibleUpgrade)
                .reasons.contains(ReleaseUpdateChecker.IncompatibilityReason.SDK_TOO_LOW)
        )

        // 2. ABI unsupported (only x86_64) -> IncompatibleUpgrade
        val x86Device = defaultInstalledDevice(supportedAbis = listOf("x86_64"))
        val abiResult = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(x86Device, release)
        assertTrue(abiResult is ReleaseUpdateChecker.CheckResult.IncompatibleUpgrade)
        assertTrue(
            (abiResult as ReleaseUpdateChecker.CheckResult.IncompatibleUpgrade)
                .reasons.contains(ReleaseUpdateChecker.IncompatibilityReason.ABI_UNSUPPORTED)
        )

        // 3. Signature MISMATCH -> MUST return IncompatibleUpgrade (blocks in-place overwrite install guidance)
        val mismatchedSigDevice = defaultInstalledDevice(signingCertSha256 = OTHER_CERT_SHA256)
        val sigMismatchResult = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(mismatchedSigDevice, release)
        assertTrue(sigMismatchResult is ReleaseUpdateChecker.CheckResult.IncompatibleUpgrade)
        val incompatible = sigMismatchResult as ReleaseUpdateChecker.CheckResult.IncompatibleUpgrade
        assertEquals(ReleaseUpdateChecker.SignatureTrustStatus.MISMATCHED, incompatible.signatureStatus)
        assertTrue(incompatible.reasons.contains(ReleaseUpdateChecker.IncompatibilityReason.SIGNATURE_MISMATCH))
        assertTrue(incompatible.statusMessageZh.contains("签名证书不一致"))

        // 4. Signature UNKNOWN (null or empty) -> Explicitly distinguished as UNKNOWN_LOCAL_SIGNATURE, non-security guarantee
        val unknownSigDevice = defaultInstalledDevice(signingCertSha256 = null)
        val unknownSigResult = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(unknownSigDevice, release)
        assertTrue(unknownSigResult is ReleaseUpdateChecker.CheckResult.UpdateAvailable)
        val updateWithUnknownSig = unknownSigResult as ReleaseUpdateChecker.CheckResult.UpdateAvailable
        assertEquals(ReleaseUpdateChecker.SignatureTrustStatus.UNKNOWN_LOCAL_SIGNATURE, updateWithUnknownSig.signatureStatus)
        assertTrue(updateWithUnknownSig.statusMessageZh.contains("非安全匹配保证"))

        // 5. Signature MATCHED -> UpdateAvailable with MATCHED status
        val matchedSigDevice = defaultInstalledDevice(signingCertSha256 = SAMPLE_CERT_SHA256.uppercase())
        val matchedSigResult = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(matchedSigDevice, release)
        assertTrue(matchedSigResult is ReleaseUpdateChecker.CheckResult.UpdateAvailable)
        val updateMatched = matchedSigResult as ReleaseUpdateChecker.CheckResult.UpdateAvailable
        assertEquals(ReleaseUpdateChecker.SignatureTrustStatus.MATCHED, updateMatched.signatureStatus)
    }

    // 4. URL Spoofing, Userinfo, Encoded Path Traversal, Redirect Domain Whitelist, and Asset Names
    @Test
    fun testUrlValidationAndRedirectDomainWhitelist() {
        // Valid URLs pass
        ReleaseUpdateChecker.validateOfficialApiUrl(ReleaseUpdateChecker.LATEST_RELEASE_API_URL)
        ReleaseUpdateChecker.validateOfficialReleaseHtmlUrl(
            "https://github.com/kqstone/voice-kiosk-relay/releases/tag/v0.2.0",
            "v0.2.0"
        )
        ReleaseUpdateChecker.validateOfficialAssetDownloadUrl(
            "https://github.com/kqstone/voice-kiosk-relay/releases/download/v0.2.0/release-metadata.json",
            "v0.2.0",
            "release-metadata.json"
        )
        ReleaseUpdateChecker.validateOfficialAssetDownloadUrl(
            "https://github.com/kqstone/voice-kiosk-relay/releases/download/v0.2.0/voice-kiosk-v0.2.0-arm64-v8a.apk",
            "v0.2.0",
            "voice-kiosk-v0.2.0-arm64-v8a.apk"
        )
        ReleaseUpdateChecker.validateOfficialReleasesIndexUrl(ReleaseUpdateChecker.OFFICIAL_RELEASES_HTML_URL)
        ReleaseUpdateChecker.validateMetadataRedirectTargetUrl(
            "https://objects.githubusercontent.com/github-production-release-asset-2e65be/12345?X-Amz-Signature=abc"
        )

        // Spoofed URLs must fail
        val spoofedApiUrls = listOf(
            "http://api.github.com/repos/kqstone/voice-kiosk-relay/releases/latest", // non-HTTPS
            "https://api.github.com@evil.com/repos/kqstone/voice-kiosk-relay/releases/latest", // userinfo spoofing
            "https://user:pass@api.github.com/repos/kqstone/voice-kiosk-relay/releases/latest", // credentials
            "https://api.github.com:8443/repos/kqstone/voice-kiosk-relay/releases/latest", // non-standard port
            "https://api.github.com/repos/kqstone/voice-kiosk-relay/releases/latest?foo=bar", // query not allowed
            "https://api.github.com/repos/kqstone/voice-kiosk-relay/releases/latest#frag", // fragment
            "https://api.github.com/repos/kqstone/voice-kiosk-relay/../other/releases/latest", // dot traversal
            "https://api.github.com/repos/kqstone%2f..%2fevil/voice-kiosk-relay/releases/latest", // encoded slash/dot
            "https://api.github.com/repos/kqstone/voice-kiosk-relay/releases/%2e%2e/latest", // encoded ..
            "https://api.github.com/repos/kqstone/voice-kiosk/releases/latest" // old repository URL
        )
        for (badUrl in spoofedApiUrls) {
            try {
                ReleaseUpdateChecker.validateOfficialApiUrl(badUrl)
                fail("Should have rejected spoofed API URL: $badUrl")
            } catch (_: ReleaseUpdateChecker.ValidationException) {
                // Expected
            }
        }

        // Old repository URLs must fail (latest-release API, release page, asset downloads, and releases index)
        try {
            ReleaseUpdateChecker.validateOfficialReleaseHtmlUrl(
                "https://github.com/kqstone/voice-kiosk/releases/tag/v0.2.0",
                "v0.2.0"
            )
            fail("Should have rejected old repository release HTML URL")
        } catch (_: ReleaseUpdateChecker.ValidationException) {
            // Expected
        }

        try {
            ReleaseUpdateChecker.validateOfficialAssetDownloadUrl(
                "https://github.com/kqstone/voice-kiosk/releases/download/v0.2.0/voice-kiosk-v0.2.0-arm64-v8a.apk",
                "v0.2.0",
                "voice-kiosk-v0.2.0-arm64-v8a.apk"
            )
            fail("Should have rejected old repository APK download URL")
        } catch (_: ReleaseUpdateChecker.ValidationException) {
            // Expected
        }

        try {
            ReleaseUpdateChecker.validateOfficialAssetDownloadUrl(
                "https://github.com/kqstone/voice-kiosk/releases/download/v0.2.0/release-metadata.json",
                "v0.2.0",
                "release-metadata.json"
            )
            fail("Should have rejected old repository metadata download URL")
        } catch (_: ReleaseUpdateChecker.ValidationException) {
            // Expected
        }

        try {
            ReleaseUpdateChecker.validateOfficialReleasesIndexUrl(
                "https://github.com/kqstone/voice-kiosk/releases"
            )
            fail("Should have rejected old repository releases index URL")
        } catch (_: ReleaseUpdateChecker.ValidationException) {
            // Expected
        }

        // Untrusted redirect targets must fail
        val badRedirects = listOf(
            "https://evil.example.com/release-metadata.json",
            "https://raw.githubusercontent.com/kqstone/voice-kiosk/main/release-metadata.json",
            "https://github.com/kqstone/voice-kiosk/releases/download/v0.2.0/release-metadata.json",
            "https://github.com/other-owner/other-repo/releases/download/v0.2.0/release-metadata.json",
            "http://objects.githubusercontent.com/asset",
            "https://objects.githubusercontent.com@attacker.com/asset"
        )
        for (badRedirect in badRedirects) {
            try {
                ReleaseUpdateChecker.validateMetadataRedirectTargetUrl(badRedirect)
                fail("Should have rejected redirect URL: $badRedirect")
            } catch (_: ReleaseUpdateChecker.ValidationException) {
                // Expected
            }
        }

        // Unsafe asset filenames must fail
        for (badName in listOf("../evil.apk", "sub/file.apk", "file\\name.apk", "file%20name.apk", "..")) {
            try {
                ReleaseUpdateChecker.validateSafeAssetName(badName)
                fail("Should have rejected asset name: $badName")
            } catch (_: ReleaseUpdateChecker.ValidationException) {
                // Expected
            }
        }
    }

    // 5. Timeout, Offline, Bounded Retry, Rate-Limit (403/429), 404 No Release, 304 Not Modified, and Expired Cache
    @Test
    fun testNetworkStatesRetryRateLimit304AndCacheExpiry() {
        val clock = FakeClock()
        val cache = ReleaseUpdateChecker.InMemoryCacheStore()
        val metaJson = buildValidMetadataJson(versionCode = 5L)
        val ghJson = buildValidGitHubReleaseJson(metadataContent = metaJson)

        // A. HTTP 404 -> NoPublishedRelease
        val manager404 = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    return ReleaseUpdateChecker.HttpResponse(404, emptyMap(), "{}", url)
                }
            },
            clock = clock,
            cacheStore = ReleaseUpdateChecker.InMemoryCacheStore(),
            installedAppProvider = { defaultInstalledDevice() },
            mainThreadPoster = { it.run() }
        )
        val res404 = manager404.performCheckBlocking()
        assertTrue(res404 is ReleaseUpdateChecker.CheckResult.NoPublishedRelease)

        // B. Transient timeout on first attempt succeeds on single retry (2 attempts total)
        val attempts = AtomicInteger(0)
        val managerRetry = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    if (!allowMetadataRedirects && attempts.incrementAndGet() == 1) {
                        throw SocketTimeoutException("Simulated transient timeout")
                    }
                    return if (!allowMetadataRedirects) {
                        ReleaseUpdateChecker.HttpResponse(200, mapOf("ETag" to "\"etag-v020\""), ghJson, url)
                    } else {
                        ReleaseUpdateChecker.HttpResponse(200, emptyMap(), metaJson, url)
                    }
                }
            },
            clock = clock,
            cacheStore = cache,
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            mainThreadPoster = { it.run() }
        )
        val resRetryOk = managerRetry.performCheckBlocking()
        assertEquals(2, attempts.get())
        assertTrue(resRetryOk is ReleaseUpdateChecker.CheckResult.UpdateAvailable)

        // C. Fresh cache within TTL does not hit network
        val networkCallsDuringFreshCache = AtomicInteger(0)
        val managerCached = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    networkCallsDuringFreshCache.incrementAndGet()
                    throw IOException("Should not be called while cache is fresh")
                }
            },
            clock = clock,
            cacheStore = cache,
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            mainThreadPoster = { it.run() }
        )
        clock.currentMs += 60_000L // +1 min (< 15 min TTL)
        val resFromFreshCache = managerCached.performCheckBlocking(forceNetwork = false)
        assertEquals(0, networkCallsDuringFreshCache.get())
        assertTrue(resFromFreshCache is ReleaseUpdateChecker.CheckResult.UpdateAvailable)

        // D. Expired cache (> TTL) MUST NOT be treated as fresh data if network is offline;
        //    and when network returns 304 Not Modified with If-None-Match, it revalidates cache.
        clock.currentMs += ReleaseUpdateChecker.CACHE_TTL_MS + 10_000L
        val managerExpiredOffline = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    throw IOException("Offline")
                }
            },
            clock = clock,
            cacheStore = cache,
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            mainThreadPoster = { it.run() }
        )
        val resExpiredOffline = managerExpiredOffline.performCheckBlocking(forceNetwork = false)
        assertTrue(resExpiredOffline is ReleaseUpdateChecker.CheckResult.NetworkFailure)

        // Now test 304 Not Modified with ETag
        var observedIfNoneMatch: String? = null
        val manager304 = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    observedIfNoneMatch = headers["If-None-Match"]
                    return ReleaseUpdateChecker.HttpResponse(304, emptyMap(), "", url)
                }
            },
            clock = clock,
            cacheStore = cache,
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            mainThreadPoster = { it.run() }
        )
        val res304 = manager304.performCheckBlocking(forceNetwork = false)
        assertEquals("\"etag-v020\"", observedIfNoneMatch)
        assertTrue(res304 is ReleaseUpdateChecker.CheckResult.UpdateAvailable)

        // E. HTTP 429 / 403 Rate-Limit with Retry-After and X-RateLimit-Reset
        val rateLimitCache = ReleaseUpdateChecker.InMemoryCacheStore()
        val apiCalls = AtomicInteger(0)
        val managerRateLimit = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    apiCalls.incrementAndGet()
                    return ReleaseUpdateChecker.HttpResponse(
                        429,
                        mapOf("Retry-After" to "120"),
                        "{}",
                        url
                    )
                }
            },
            clock = clock,
            cacheStore = rateLimitCache,
            installedAppProvider = { defaultInstalledDevice() },
            mainThreadPoster = { it.run() }
        )
        val resRateLimited1 = managerRateLimit.performCheckBlocking(forceNetwork = true)
        assertTrue(resRateLimited1 is ReleaseUpdateChecker.CheckResult.RateLimited)
        assertEquals(120L, (resRateLimited1 as ReleaseUpdateChecker.CheckResult.RateLimited).remainingSeconds)
        assertEquals(1, apiCalls.get())

        // Subsequent call during cooldown window must short-circuit without hitting network
        clock.currentMs += 30_000L
        val resRateLimited2 = managerRateLimit.performCheckBlocking(forceNetwork = true)
        assertTrue(resRateLimited2 is ReleaseUpdateChecker.CheckResult.RateLimited)
        assertEquals(90L, (resRateLimited2 as ReleaseUpdateChecker.CheckResult.RateLimited).remainingSeconds)
        assertEquals(1, apiCalls.get())
    }

    // 6. Single-Flight Concurrency, Startup Daily Frequency Cap, and Lifecycle Listener Unregistration
    @Test
    fun testSingleFlightStartupDailyCapAndLifecycleUnregistration() {
        val clock = FakeClock()
        val cache = ReleaseUpdateChecker.InMemoryCacheStore()
        val latchAllowFinish = CountDownLatch(1)
        val latchStarted = CountDownLatch(1)
        val networkCallCount = AtomicInteger(0)

        val metaJson = buildValidMetadataJson(versionCode = 4L)
        val ghJson = buildValidGitHubReleaseJson(metadataContent = metaJson)

        val manager = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    if (!allowMetadataRedirects) {
                        networkCallCount.incrementAndGet()
                        latchStarted.countDown()
                        latchAllowFinish.await(5, TimeUnit.SECONDS)
                        return ReleaseUpdateChecker.HttpResponse(200, emptyMap(), ghJson, url)
                    }
                    return ReleaseUpdateChecker.HttpResponse(200, emptyMap(), metaJson, url)
                }
            },
            clock = clock,
            cacheStore = cache,
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            mainThreadPoster = { it.run() }
        )

        val callbackCountActive = AtomicInteger(0)
        val callbackCountUnregistered = AtomicInteger(0)

        val activeListener = object : ReleaseUpdateChecker.Listener {
            override fun onUpdateCheckStateChanged(inFlight: Boolean, lastResult: ReleaseUpdateChecker.CheckResult?) {
                if (!inFlight && lastResult != null) {
                    callbackCountActive.incrementAndGet()
                }
            }
        }
        val unregisteredListener = object : ReleaseUpdateChecker.Listener {
            override fun onUpdateCheckStateChanged(inFlight: Boolean, lastResult: ReleaseUpdateChecker.CheckResult?) {
                if (!inFlight && lastResult != null) {
                    callbackCountUnregistered.incrementAndGet()
                }
            }
        }

        manager.registerListener(activeListener)
        manager.registerListener(unregisteredListener)
        assertEquals(2, manager.listenerCount())

        // Unregister second listener before check completes (simulating Activity onDestroy)
        manager.unregisterListener(unregisteredListener)
        assertEquals(1, manager.listenerCount())

        // First trigger starts background work
        assertTrue(manager.triggerCheck(forceNetwork = true))
        assertTrue(latchStarted.await(5, TimeUnit.SECONDS))

        // Second concurrent trigger while first is in-flight MUST return false (single-flight)
        assertFalse(manager.triggerCheck(forceNetwork = true, bypassManualCooldown = true))

        // Let first check complete
        latchAllowFinish.countDown()
        val deadline = System.currentTimeMillis() + 5000L
        while (manager.isCheckInFlight() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        assertEquals(1, networkCallCount.get())
        assertEquals(1, callbackCountActive.get())
        assertEquals(0, callbackCountUnregistered.get())

        // Rapid manual click within MANUAL_CHECK_COOLDOWN_MS (5s) after completion MUST be rejected by Manager cooldown
        clock.currentMs += 1_000L
        assertFalse(manager.triggerCheck(forceNetwork = false))

        // After MANUAL_CHECK_COOLDOWN_MS elapses, manual trigger is allowed again
        clock.currentMs += ReleaseUpdateChecker.MANUAL_CHECK_COOLDOWN_MS + 100L
        assertTrue(manager.triggerCheck(forceNetwork = false))
        val deadline2 = System.currentTimeMillis() + 5000L
        while (manager.isCheckInFlight() && System.currentTimeMillis() < deadline2) {
            Thread.sleep(10)
        }

        // Startup check test: disabled by default -> returns false
        val syncManager = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    return if (!allowMetadataRedirects) {
                        ReleaseUpdateChecker.HttpResponse(200, emptyMap(), ghJson, url)
                    } else {
                        ReleaseUpdateChecker.HttpResponse(200, emptyMap(), metaJson, url)
                    }
                }
            },
            clock = clock,
            cacheStore = ReleaseUpdateChecker.InMemoryCacheStore(),
            installedAppProvider = { defaultInstalledDevice() },
            backgroundExecutor = Executor { it.run() },
            mainThreadPoster = { it.run() }
        )
        assertFalse(syncManager.maybeTriggerStartupCheck(startupCheckEnabled = false))

        // When enabled: runs once, then suppressed for 24h, then allowed after 24h
        assertTrue(syncManager.maybeTriggerStartupCheck(startupCheckEnabled = true))
        clock.currentMs += 12 * 60 * 60 * 1000L // +12h
        assertFalse(syncManager.maybeTriggerStartupCheck(startupCheckEnabled = true))
        clock.currentMs += 13 * 60 * 60 * 1000L // +25h total
        assertTrue(syncManager.maybeTriggerStartupCheck(startupCheckEnabled = true))
    }

    // 7. Manifest has NO REQUEST_INSTALL_PACKAGES permission, and HTTP client forbids Authorization/Credentials
    @Test
    fun testManifestHasNoInstallPackagesPermissionAndHttpForbidsAuthorization() {
        // Verify outbound header sanitizer blocks Authorization, Token, Cookie, Secret headers
        for (forbiddenHeader in listOf("Authorization", "authorization", "X-GitHub-Token", "Cookie", "Proxy-Authorization")) {
            try {
                ReleaseUpdateChecker.DefaultHttpClient.sanitizeOutboundHeaders(
                    mapOf(forbiddenHeader to "Bearer ghp_12345")
                )
                fail("Expected header $forbiddenHeader to be rejected by sanitizeOutboundHeaders")
            } catch (_: SecurityException) {
                // Expected
            }
        }

        // Locate AndroidManifest.xml and verify REQUEST_INSTALL_PACKAGES is absent
        val candidatePaths = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("android/app/src/main/AndroidManifest.xml"),
            File("../android/app/src/main/AndroidManifest.xml")
        )
        val manifestFile = candidatePaths.firstOrNull { it.isFile }
        assertNotNull("AndroidManifest.xml should be locatable from test working directory", manifestFile)
        val manifestXml = manifestFile!!.readText(StandardCharsets.UTF_8)
        assertFalse(
            "AndroidManifest.xml must NOT request REQUEST_INSTALL_PACKAGES",
            manifestXml.contains("REQUEST_INSTALL_PACKAGES")
        )
        assertFalse(
            "AndroidManifest.xml must NOT request INSTALL_PACKAGES",
            manifestXml.contains("android.permission.INSTALL_PACKAGES")
        )
    }

    // 8. Unknown Local Package Version & Current APK Signer Extraction (apkContentsSigners vs History & Multi-Signer)
    @Test
    fun testLocalVersionUnknownAndApkContentsSignersContract() {
        val metaJson = buildValidMetadataJson(versionCode = 5L)
        val ghJson = buildValidGitHubReleaseJson(metadataContent = metaJson)
        val release = ReleaseUpdateChecker.parseAndCrossValidateMetadata(
            metaJson,
            ReleaseUpdateChecker.parseAndValidateGitHubReleaseJson(ghJson)
        )

        // A. Null versionCode or null/blank versionName or packageReadError -> LocalVersionUnknown (never UpdateAvailable)
        val unknownCodeDevice = defaultInstalledDevice(versionCode = null, versionName = null, packageReadError = "NameNotFoundException")
        val resUnknown = ReleaseUpdateChecker.evaluateReleaseAgainstDevice(unknownCodeDevice, release)
        assertTrue(resUnknown is ReleaseUpdateChecker.CheckResult.LocalVersionUnknown)
        assertTrue((resUnknown as ReleaseUpdateChecker.CheckResult.LocalVersionUnknown).statusMessageZh.contains("无法读取本机版本"))

        val invalidZeroCodeDevice = defaultInstalledDevice(versionCode = 0L, versionName = "0.1.0")
        assertTrue(ReleaseUpdateChecker.evaluateReleaseAgainstDevice(invalidZeroCodeDevice, release) is ReleaseUpdateChecker.CheckResult.LocalVersionUnknown)

        // Manager.performCheckBlocking short-circuits before hitting network if local version is unknown or throws
        val networkCalls = AtomicInteger(0)
        val managerUnknownLocal = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    networkCalls.incrementAndGet()
                    return ReleaseUpdateChecker.HttpResponse(200, emptyMap(), ghJson, url)
                }
            },
            clock = FakeClock(),
            cacheStore = ReleaseUpdateChecker.InMemoryCacheStore(),
            installedAppProvider = { unknownCodeDevice },
            mainThreadPoster = { it.run() }
        )
        val blockingRes = managerUnknownLocal.performCheckBlocking(forceNetwork = true)
        assertTrue(blockingRes is ReleaseUpdateChecker.CheckResult.LocalVersionUnknown)
        assertEquals(0, networkCalls.get())

        // B. extractSingleCurrentSignerSha256: single signer returns valid SHA-256; multiple signers or empty returns null (unknown)
        val cert1 = "signer-cert-1".toByteArray(StandardCharsets.UTF_8)
        val cert2 = "signer-cert-2".toByteArray(StandardCharsets.UTF_8)
        val singleHash = ReleaseUpdateChecker.extractSingleCurrentSignerSha256(listOf(cert1), hasMultipleSigners = false)
        assertNotNull(singleHash)
        assertEquals(64, singleHash!!.length)

        // Multiple signers flag or >1 array length must return null (unknown) rather than taking [0]
        assertNull(ReleaseUpdateChecker.extractSingleCurrentSignerSha256(listOf(cert1), hasMultipleSigners = true))
        assertNull(ReleaseUpdateChecker.extractSingleCurrentSignerSha256(listOf(cert1, cert2), hasMultipleSigners = false))
        assertNull(ReleaseUpdateChecker.extractSingleCurrentSignerSha256(emptyList(), hasMultipleSigners = false))
        assertNull(ReleaseUpdateChecker.extractSingleCurrentSignerSha256(null, hasMultipleSigners = false))

        // C. Static source contract check on AndroidReleaseUpdatePlatform.kt:
        //    - No hardcoded versionCode = 2L or versionName = "0.1.0" fallback
        //    - Uses signingInfo.apkContentsSigners and NEVER signingInfo.signingCertificateHistory
        val platformCandidatePaths = listOf(
            File("src/main/java/com/voicekiosk/app/AndroidReleaseUpdatePlatform.kt"),
            File("app/src/main/java/com/voicekiosk/app/AndroidReleaseUpdatePlatform.kt"),
            File("android/app/src/main/java/com/voicekiosk/app/AndroidReleaseUpdatePlatform.kt"),
            File("../android/app/src/main/java/com/voicekiosk/app/AndroidReleaseUpdatePlatform.kt")
        )
        val platformFile = platformCandidatePaths.firstOrNull { it.isFile }
        assertNotNull("AndroidReleaseUpdatePlatform.kt should be locatable", platformFile)
        val platformSrc = platformFile!!.readText(StandardCharsets.UTF_8)
        assertTrue(platformSrc.contains("signingInfo.apkContentsSigners"))
        assertFalse(
            "AndroidReleaseUpdatePlatform.kt must NOT read signingCertificateHistory",
            platformSrc.contains("signingInfo.signingCertificateHistory")
        )
        assertFalse(
            "AndroidReleaseUpdatePlatform.kt must NOT hardcode fallback versionName 0.1.0",
            platformSrc.contains("\"0.1.0\"")
        )
    }

    // 9. DefaultHttpClient does NOT swallow 2xx inputStream IOException/SocketTimeoutException, and Manager handles rejected Executor
    @Test
    fun testDefaultHttpClientPropagatesInputStreamTimeoutAndManagerHandlesRejectedExecutor() {
        // A. DefaultHttpClient: when HTTP 200 conn.inputStream throws SocketTimeoutException, it MUST propagate (not become empty body)
        val clientTimeoutOnInputStream = ReleaseUpdateChecker.DefaultHttpClient { url: URL ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = 200
                override fun getInputStream(): InputStream {
                    throw SocketTimeoutException("Simulated timeout acquiring 200 inputStream")
                }
            }
        }
        try {
            clientTimeoutOnInputStream.get(
                url = ReleaseUpdateChecker.LATEST_RELEASE_API_URL,
                headers = emptyMap(),
                maxBytes = 1024,
                allowMetadataRedirects = false
            )
            fail("Expected SocketTimeoutException from 200 inputStream to propagate")
        } catch (_: SocketTimeoutException) {
            // Expected: not swallowed into empty body!
        }

        // B. DefaultHttpClient: non-2xx (e.g. 404) with null errorStream returns empty body without throwing
        val client404NoErrorStream = ReleaseUpdateChecker.DefaultHttpClient { url: URL ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = 404
                override fun getErrorStream(): InputStream? = null
            }
        }
        val resp404 = client404NoErrorStream.get(
            url = ReleaseUpdateChecker.LATEST_RELEASE_API_URL,
            headers = emptyMap(),
            maxBytes = 1024,
            allowMetadataRedirects = false
        )
        assertEquals(404, resp404.statusCode)
        assertEquals("", resp404.body)

        // C. Manager with DefaultHttpClient retries once when first 200 inputStream times out and succeeds on 2nd attempt
        val metaJson = buildValidMetadataJson(versionCode = 6L)
        val ghJson = buildValidGitHubReleaseJson(metadataContent = metaJson)
        val apiConnAttempts = AtomicInteger(0)
        val flakyDefaultClient = ReleaseUpdateChecker.DefaultHttpClient { url: URL ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy(): Boolean = false
                override fun getResponseCode(): Int = 200
                override fun getInputStream(): InputStream {
                    return if (url.host == "api.github.com") {
                        if (apiConnAttempts.incrementAndGet() == 1) {
                            throw SocketTimeoutException("First attempt inputStream timeout")
                        }
                        ByteArrayInputStream(ghJson.toByteArray(StandardCharsets.UTF_8))
                    } else {
                        ByteArrayInputStream(metaJson.toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }
        }
        val managerWithFlakyStream = ReleaseUpdateChecker.Manager(
            httpClient = flakyDefaultClient,
            clock = FakeClock(),
            cacheStore = ReleaseUpdateChecker.InMemoryCacheStore(),
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            mainThreadPoster = { it.run() }
        )
        val retryRes = managerWithFlakyStream.performCheckBlocking(forceNetwork = true)
        assertEquals(2, apiConnAttempts.get())
        assertTrue(retryRes is ReleaseUpdateChecker.CheckResult.UpdateAvailable)

        // D. Manager when backgroundExecutor.execute throws RejectedExecutionException:
        //    must clear inFlight=false, set lastResult, notify active listeners, and not notify unregistered listeners
        val rejectedEvents = mutableListOf<Pair<Boolean, ReleaseUpdateChecker.CheckResult?>>()
        val unregisteredEvents = AtomicInteger(0)
        val managerRejected = ReleaseUpdateChecker.Manager(
            httpClient = flakyDefaultClient,
            clock = FakeClock(),
            cacheStore = ReleaseUpdateChecker.InMemoryCacheStore(),
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            backgroundExecutor = Executor {
                throw RejectedExecutionException("Simulated shut down executor")
            },
            mainThreadPoster = { it.run() }
        )
        val activeListener = object : ReleaseUpdateChecker.Listener {
            override fun onUpdateCheckStateChanged(inFlight: Boolean, lastResult: ReleaseUpdateChecker.CheckResult?) {
                rejectedEvents.add(inFlight to lastResult)
            }
        }
        val deadListener = object : ReleaseUpdateChecker.Listener {
            override fun onUpdateCheckStateChanged(inFlight: Boolean, lastResult: ReleaseUpdateChecker.CheckResult?) {
                unregisteredEvents.incrementAndGet()
            }
        }
        managerRejected.registerListener(activeListener)
        managerRejected.registerListener(deadListener)
        managerRejected.unregisterListener(deadListener)
        val initialDeadCount = unregisteredEvents.get()

        val started = managerRejected.triggerCheck(forceNetwork = true, bypassManualCooldown = true)
        assertFalse(started)
        assertFalse("inFlight must be reset to false when executor rejects", managerRejected.isCheckInFlight())
        assertTrue(managerRejected.lastResult is ReleaseUpdateChecker.CheckResult.ValidationFailure)
        assertEquals(
            "EXECUTOR_REJECTED",
            (managerRejected.lastResult as ReleaseUpdateChecker.CheckResult.ValidationFailure).reasonCode
        )
        assertFalse(rejectedEvents.last().first)
        assertEquals(initialDeadCount, unregisteredEvents.get())
    }

    // 10. HTTP 304 ETag Cache Bounded Metadata Trust TTL & Release Notes Plain-Text Sanitizer
    @Test
    fun testEtag304DoesNotExtendMetadataTrustIndefinitelyAndSanitizesReleaseNotes() {
        val clock = FakeClock()
        val cache = ReleaseUpdateChecker.InMemoryCacheStore()

        val initialMeta = buildValidMetadataJson(versionCode = 3L)
        val ghJson = buildValidGitHubReleaseJson(metadataContent = initialMeta)
        val metadataFetchCount = AtomicInteger(0)
        val observedIfNoneMatchHeaders = mutableListOf<String?>()

        val manager = ReleaseUpdateChecker.Manager(
            httpClient = object : ReleaseUpdateChecker.HttpClient {
                override fun get(
                    url: String,
                    headers: Map<String, String>,
                    maxBytes: Int,
                    allowMetadataRedirects: Boolean
                ): ReleaseUpdateChecker.HttpResponse {
                    return if (!allowMetadataRedirects) {
                        val inm = headers["If-None-Match"]
                        observedIfNoneMatchHeaders.add(inm)
                        if (inm != null) {
                            ReleaseUpdateChecker.HttpResponse(304, mapOf("ETag" to "\"etag-1\""), "", url)
                        } else {
                            ReleaseUpdateChecker.HttpResponse(200, mapOf("ETag" to "\"etag-1\""), ghJson, url)
                        }
                    } else {
                        metadataFetchCount.incrementAndGet()
                        ReleaseUpdateChecker.HttpResponse(200, emptyMap(), initialMeta, url)
                    }
                }
            },
            clock = clock,
            cacheStore = cache,
            installedAppProvider = { defaultInstalledDevice(versionCode = 2L) },
            mainThreadPoster = { it.run() }
        )

        // 1. Initial fetch at T=0 -> HTTP 200 API + fetches metadata (metadataFetchCount = 1)
        assertTrue(manager.performCheckBlocking() is ReleaseUpdateChecker.CheckResult.UpdateAvailable)
        assertEquals(1, metadataFetchCount.get())
        val initialMetadataFetchedAt = cache.read()!!.metadataFetchedAtEpochMs

        // 2. At T = 20 min (> 15m CACHE_TTL_MS, but < 60m MAX_ETAG_304_TRUST_AGE_MS):
        //    Sends If-None-Match, gets 304, reuses metadata without re-downloading metadata,
        //    AND preserves original metadataFetchedAtEpochMs (does not reset it to T=20m!)
        clock.currentMs += 20 * 60 * 1000L
        assertTrue(manager.performCheckBlocking() is ReleaseUpdateChecker.CheckResult.UpdateAvailable)
        assertEquals("\"etag-1\"", observedIfNoneMatchHeaders.last())
        assertEquals(1, metadataFetchCount.get())
        assertEquals(initialMetadataFetchedAt, cache.read()!!.metadataFetchedAtEpochMs)

        // 3. At T = 65 min (> 60m MAX_ETAG_304_TRUST_AGE_MS from initial metadata fetch):
        //    Even though 304 was received at T=20m, metadata trust TTL has now expired!
        //    Must omit If-None-Match and re-fetch both Release and release-metadata.json (metadataFetchCount = 2).
        clock.currentMs += 45 * 60 * 1000L
        assertTrue(manager.performCheckBlocking() is ReleaseUpdateChecker.CheckResult.UpdateAvailable)
        assertNull("If-None-Match must be omitted once metadata trust age expires", observedIfNoneMatchHeaders.last())
        assertEquals(2, metadataFetchCount.get())

        // 4. Verify Release Notes policy: disabled in UI, and sanitizer strips HTML tags & truncates plain text
        assertFalse(ReleaseUpdateChecker.RELEASE_NOTES_DISPLAY_SUPPORTED)
        val dirtyNotes = "<script>alert(1)</script><b>修复唤醒稳定性</b> &amp; 提升性能 " + "A".repeat(100)
        val cleanNotes = ReleaseUpdateChecker.sanitizeReleaseNotesPlainText(dirtyNotes, maxLength = 30)
        assertFalse(cleanNotes.contains("<script>"))
        assertFalse(cleanNotes.contains("<b>"))
        assertTrue(cleanNotes.endsWith("..."))
    }

    // 11. UI Lifecycle Safety (onStart/onStop/onDestroy, isFinishing/isDestroyed) & Strict Browser URL Validator Contract
    @Test
    fun testUiLifecycleRegistrationAndStrictBrowserUrlValidationContract() {
        // A. Strict browser navigation validator accepts exact official URLs matching validated tag/asset
        assertEquals(
            "https://github.com/kqstone/voice-kiosk-relay/releases/tag/v0.2.0",
            ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl(
                "https://github.com/kqstone/voice-kiosk-relay/releases/tag/v0.2.0",
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage(expectedTag = "v0.2.0")
            )
        )
        assertEquals(
            "https://github.com/kqstone/voice-kiosk-relay/releases/download/v0.2.0/voice-kiosk-v0.2.0-arm64-v8a.apk",
            ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl(
                "https://github.com/kqstone/voice-kiosk-relay/releases/download/v0.2.0/voice-kiosk-v0.2.0-arm64-v8a.apk",
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseApkAsset(
                    expectedTag = "v0.2.0",
                    expectedAssetName = "voice-kiosk-v0.2.0-arm64-v8a.apk"
                )
            )
        )
        assertEquals(
            "https://github.com/kqstone/voice-kiosk-relay/releases",
            ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl(
                ReleaseUpdateChecker.OFFICIAL_RELEASES_HTML_URL,
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleasesIndexPage
            )
        )

        // B. Rejects URLs that would have bypassed a naive startsWith("/kqstone/voice-kiosk-relay/releases") check
        val rejectedBrowserNavigations = listOf<Pair<String, ReleaseUpdateChecker.OfficialBrowserTarget>>(
            // Old repository URLs must be rejected by browser navigation validator
            "https://github.com/kqstone/voice-kiosk/releases/tag/v0.2.0" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage("v0.2.0"),
            "https://github.com/kqstone/voice-kiosk/releases/download/v0.2.0/voice-kiosk-v0.2.0-arm64-v8a.apk" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseApkAsset("v0.2.0", "voice-kiosk-v0.2.0-arm64-v8a.apk"),
            "https://github.com/kqstone/voice-kiosk/releases" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleasesIndexPage,
            // Wrong tag vs validated release
            "https://github.com/kqstone/voice-kiosk-relay/releases/tag/v0.1.9" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage("v0.2.0"),
            // Malicious query parameter on release page
            "https://github.com/kqstone/voice-kiosk-relay/releases/tag/v0.2.0?redirect=https://evil.example" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage("v0.2.0"),
            // Malicious userinfo with github.com host
            "https://attacker:secret@github.com/kqstone/voice-kiosk-relay/releases/tag/v0.2.0" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage("v0.2.0"),
            // Path prefix spoofing: /kqstone/voice-kiosk-relay/releases-evil
            "https://github.com/kqstone/voice-kiosk-relay/releases-evil/tag/v0.2.0" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseHtmlPage("v0.2.0"),
            // Wrong asset filename or tag mismatch on APK download URL
            "https://github.com/kqstone/voice-kiosk-relay/releases/download/v0.2.0/evil.apk" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseApkAsset("v0.2.0", "voice-kiosk-v0.2.0-arm64-v8a.apk"),
            "https://github.com/kqstone/voice-kiosk-relay/releases/download/v0.1.0/voice-kiosk-v0.2.0-arm64-v8a.apk" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseApkAsset("v0.2.0", "voice-kiosk-v0.2.0-arm64-v8a.apk"),
            // Encoded path traversal under /releases
            "https://github.com/kqstone/voice-kiosk-relay/releases/download/v0.2.0/..%2f..%2fevil.apk" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleaseApkAsset("v0.2.0", "voice-kiosk-v0.2.0-arm64-v8a.apk"),
            // Trailing path or query on releases index page
            "https://github.com/kqstone/voice-kiosk-relay/releases?page=2" to
                ReleaseUpdateChecker.OfficialBrowserTarget.ReleasesIndexPage
        )
        for ((badUrl, target) in rejectedBrowserNavigations) {
            try {
                ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl(badUrl, target)
                fail("Should have rejected browser navigation URL: $badUrl for target $target")
            } catch (_: ReleaseUpdateChecker.ValidationException) {
                // Expected
            }
        }

        // C. Static source contract check on SettingsActivity.kt and MainActivity.kt:
        //    - SettingsActivity registers listener in onStart, unregisters + dismisses dialog in onStop and onDestroy
        //    - SettingsActivity checks !isFinishing && !isDestroyed && visible state before callback UI updates, dialog.show(), and startActivity()
        //    - SettingsActivity uses ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl (not coarse startsWith)
        //    - MainActivity checks isFinishing / isDestroyed before updating settingsButton in onUpdateCheckStateChanged
        val settingsCandidatePaths = listOf(
            File("src/main/java/com/voicekiosk/app/SettingsActivity.kt"),
            File("app/src/main/java/com/voicekiosk/app/SettingsActivity.kt"),
            File("android/app/src/main/java/com/voicekiosk/app/SettingsActivity.kt"),
            File("../android/app/src/main/java/com/voicekiosk/app/SettingsActivity.kt")
        )
        val settingsFile = settingsCandidatePaths.firstOrNull { it.isFile }
        assertNotNull("SettingsActivity.kt should be locatable", settingsFile)
        val settingsSrc = settingsFile!!.readText(StandardCharsets.UTF_8)
        assertTrue("SettingsActivity must override onStart", settingsSrc.contains("override fun onStart()"))
        assertTrue("SettingsActivity must override onStop", settingsSrc.contains("override fun onStop()"))
        assertTrue("SettingsActivity must check isFinishing and isDestroyed", settingsSrc.contains("!isFinishing && !isDestroyed"))
        assertTrue(
            "SettingsActivity must validate browser URL via ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl",
            settingsSrc.contains("ReleaseUpdateChecker.validateOfficialBrowserNavigationUrl")
        )
        assertFalse(
            "SettingsActivity must NOT use coarse path.startsWith(\"/\${ReleaseUpdateChecker.EXPECTED_REPOSITORY}/releases\") check",
            settingsSrc.contains("path.startsWith(\"/\${ReleaseUpdateChecker.EXPECTED_REPOSITORY}/releases\")")
        )

        val mainCandidatePaths = listOf(
            File("src/main/java/com/voicekiosk/app/MainActivity.kt"),
            File("app/src/main/java/com/voicekiosk/app/MainActivity.kt"),
            File("android/app/src/main/java/com/voicekiosk/app/MainActivity.kt"),
            File("../android/app/src/main/java/com/voicekiosk/app/MainActivity.kt")
        )
        val mainFile = mainCandidatePaths.firstOrNull { it.isFile }
        assertNotNull("MainActivity.kt should be locatable", mainFile)
        val mainSrc = mainFile!!.readText(StandardCharsets.UTF_8)
        assertTrue(
            "MainActivity onUpdateCheckStateChanged must guard against isFinishing || isDestroyed",
            mainSrc.contains("if (isFinishing || isDestroyed")
        )
    }
}
