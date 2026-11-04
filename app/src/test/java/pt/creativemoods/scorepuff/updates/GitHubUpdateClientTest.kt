// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.updates

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubUpdateClientTest {
    private val fingerprint = "a".repeat(64)
    private val hash = "b".repeat(64)
    private val base = "${GitHubUpdateClient.REPOSITORY}/releases/download/v0.2.19/"
    private fun asset(name: String) = JSONObject().put("name", name).put("state", "uploaded")
        .put("size", 1234).put("browser_download_url", base + name)
    private fun release() = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", "v0.2.19")
        .put("assets", JSONArray().put(asset("scorepuff.apk").put("digest", "sha256:$hash")).put(asset("release.json")))
    private fun metadata(code: Long = 21) = JSONObject().put("package", GitHubUpdateClient.PACKAGE)
        .put("versionName", "0.2.19").put("tag", "v0.2.19").put("versionCode", code)
        .put("certificateSha256", fingerprint).put("sha256", hash).put("sizeBytes", 1234)
    private suspend fun check(release: JSONObject = release(), metadata: JSONObject = metadata(), installed: Long = 20): AppUpdate? =
        GitHubUpdateClient(installed, fingerprint) { url ->
            when (url) {
                GitHubUpdateClient.LATEST_URL -> release.toString()
                base + "release.json" -> metadata.toString()
                else -> error("APK download must require a user click")
            }
        }.check()

    @Test fun newerCodeReturnsExactTaggedApkWithoutDownloadingIt() = runTest {
        val result = check()!!
        assertEquals(21L, result.versionCode)
        assertEquals(base + "scorepuff.apk", result.downloadUrl)
        assertEquals("${GitHubUpdateClient.REPOSITORY}/releases/tag/v0.2.19", result.releaseUrl)
    }

    @Test fun equalOrOlderCodeNeverOffersAnUpdate() = runTest {
        assertNull(check(metadata = metadata(20)))
        assertNull(check(metadata = metadata(19)))
    }

    @Test fun comparisonUsesCodeRatherThanDisplayVersion() = runTest {
        assertNotNull(check(installed = 1))
        assertNull(check(installed = 999))
    }

    @Test fun missingReleaseMeansNothingToOffer() = runTest {
        assertNull(GitHubUpdateClient(20, fingerprint) { null }.check())
    }

    @Test fun draftsAndPrereleasesAreIgnored() = runTest {
        assertNull(check(release = release().put("draft", true)))
        assertNull(check(release = release().put("prerelease", true)))
    }

    @Test fun wrongIdentityCertificateTagAndHashAreRejected() = runTest {
        for ((field, value) in listOf("package" to "other.app", "tag" to "v0.2.20", "versionName" to "9.0.0",
            "certificateSha256" to "c".repeat(64), "sha256" to "bad", "versionCode" to -1)) {
            try { check(metadata = metadata().put(field, value)); fail("Accepted invalid $field") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unexpectedDownloadHostsOrPathsAreRejected() = runTest {
        val assets = release().getJSONArray("assets")
        assets.getJSONObject(0).put("browser_download_url", "https://evil.example/scorepuff.apk")
        try { check(release = release().put("assets", assets)); fail("Accepted external APK") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun duplicateOrMissingAssetsAreRejected() = runTest {
        for (assets in listOf(JSONArray().put(asset("release.json")),
            release().getJSONArray("assets").put(asset("scorepuff.apk")))) {
            try { check(release = release().put("assets", assets)); fail("Accepted ambiguous/missing APK") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun wrongSizeOrServerDigestIsRejected() = runTest {
        try { check(metadata = metadata().put("sizeBytes", 999)); fail("Accepted wrong size") }
        catch (_: IllegalArgumentException) { }
        val data = release()
        data.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:" + "c".repeat(64))
        try { check(release = data); fail("Accepted mismatched digest") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun releasesWithoutGitHubDigestStillUsePublishedMetadata() = runTest {
        val data = release()
        data.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNotNull(check(release = data))
    }

    @Test fun redirectsAllowOnlyHttpsGitHubHostsWithoutCredentials() {
        assertTrue(ReleaseHttp.allowed("https://release-assets.githubusercontent.com/file".toHttpUrl()))
        for (url in listOf("http://github.com/file", "https://github.com.evil.example/file", "https://evil.example/file",
            "https://user:pass@github.com/file", "https://github.com:444/file")) {
            assertFalse(url, ReleaseHttp.allowed(url.toHttpUrl()))
        }
    }

    @Test fun cancellationIsNotConvertedIntoNoUpdate() = runTest {
        try { GitHubUpdateClient(20, fingerprint) { throw CancellationException() }.check(); fail("Swallowed cancellation") }
        catch (_: CancellationException) { }
    }
}
