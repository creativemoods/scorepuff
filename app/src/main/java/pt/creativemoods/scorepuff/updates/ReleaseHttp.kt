// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.updates

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Separate from OMDb: no API key, cookies, account information or title data is sent. */
internal class ReleaseHttp {
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    suspend fun get(value: String): String? {
        var url = value.toHttpUrl()
        repeat(5) {
            require(allowed(url))
            val response = request(url)
            response.use {
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    url = requireNotNull(url.resolve(requireNotNull(response.header("Location"))))
                } else {
                    if (response.code == 404 && value == GitHubUpdateClient.LATEST_URL) return null
                    if (!response.isSuccessful) throw IOException("Update service unavailable")
                    val body = response.body ?: throw IOException("Missing update metadata")
                    // Header validation plus a bounded read also handles chunked/misreported lengths.
                    require(body.contentLength() <= MAX_BYTES)
                    val source = body.source()
                    source.request(MAX_BYTES + 1)
                    val bytes = source.readByteArray(minOf(source.buffer.size, MAX_BYTES + 1))
                    require(bytes.size <= MAX_BYTES)
                    return bytes.toString(Charsets.UTF_8)
                }
            }
        }
        throw IOException("Too many update redirects")
    }

    private suspend fun request(url: HttpUrl): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "ScorePuff-Android")
            .header("Accept", if (url.host == "api.github.com") "application/vnd.github+json" else "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(IOException("Update service unavailable"))
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) response.close()
                else continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    companion object {
        private const val MAX_BYTES = 256L * 1024
        internal fun allowed(url: HttpUrl) = url.isHttps && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.host in setOf(
                "api.github.com", "github.com", "release-assets.githubusercontent.com",
                "objects.githubusercontent.com", "github-releases.githubusercontent.com")
    }
}
