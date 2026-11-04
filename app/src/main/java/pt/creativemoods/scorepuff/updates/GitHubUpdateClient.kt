// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.updates

import org.json.JSONObject

/** The browser downloads this exact tagged asset, never a moving /latest URL. */
data class AppUpdate(val versionName: String, val versionCode: Long, val downloadUrl: String, val releaseUrl: String)

class GitHubUpdateClient(
    private val installedVersionCode: Long,
    private val certificateSha256: String,
    private val fetch: suspend (String) -> String? = ReleaseHttp()::get
) {
    suspend fun check(): AppUpdate? {
        val release = fetch(LATEST_URL)?.let(::JSONObject) ?: return null // No published release (404).
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(tag))
        val base = "$REPOSITORY/releases/download/$tag/"
        val assets = release.getJSONArray("assets")
        fun asset(name: String): JSONObject {
            val found = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.getString("name") == name }
            require(found.size == 1)
            return found.single().also {
                require(it.getString("state") == "uploaded")
                require(it.getString("browser_download_url") == base + name)
            }
        }
        val apk = asset("scorepuff.apk")
        val metadataAsset = asset("release.json")
        val metadata = JSONObject(requireNotNull(fetch(metadataAsset.getString("browser_download_url"))))
        require(metadata.getString("package") == PACKAGE)
        require(metadata.getString("tag") == tag)
        require("v" + metadata.getString("versionName") == tag)
        val code = integer(metadata, "versionCode")
        require(code in 1..2_100_000_000L)
        require(metadata.getString("certificateSha256").equals(certificateSha256, ignoreCase = true))
        val sha256 = metadata.getString("sha256")
        require(Regex("[a-fA-F0-9]{64}").matches(sha256))
        val size = integer(apk, "size")
        require(size in 1..200L * 1024 * 1024 && integer(metadata, "sizeBytes") == size)
        // GitHub includes a server-side digest on newer assets; cross-check it when present.
        if (!apk.isNull("digest")) require(apk.getString("digest").equals("sha256:$sha256", ignoreCase = true))
        if (code <= installedVersionCode) return null
        return AppUpdate(metadata.getString("versionName"), code, base + "scorepuff.apk", "$REPOSITORY/releases/tag/$tag")
    }

    private fun integer(json: JSONObject, field: String): Long {
        val value = json.get(field)
        require(value is Int || value is Long)
        return (value as Number).toLong()
    }

    companion object {
        const val REPOSITORY = "https://github.com/creativemoods/scorepuff"
        const val LATEST_URL = "https://api.github.com/repos/creativemoods/scorepuff/releases/latest"
        const val PACKAGE = "pt.creativemoods.scorepuff"
    }
}
