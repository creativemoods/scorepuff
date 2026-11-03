// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import pt.creativemoods.scorepuff.data.models.ContentType
import pt.creativemoods.scorepuff.metadata.ImdbDetailsSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OmdbClient(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build(),
    private val endpoint: HttpUrl = "https://www.omdbapi.com/".toHttpUrl(),
    private val spacingMs: Long = 400
) : RatingsProvider, MetadataSource, ImdbDetailsSource {
    private val rateMutex = Mutex()
    private var lastRequestAt = 0L
    private var blockedUntil = 0L
    private var blockedProblem = ProviderProblem.AUTH

    override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch =
        OmdbJson.search(request(buildMap {
            put("s", title); put("page", page.toString())
            year?.let { put("y", it.toString()) }
            type?.let { put("type", if (it == ContentType.MOVIE) "movie" else "series") }
        }))

    override suspend fun details(imdbId: String): OmdbDetails = OmdbJson.details(request(mapOf("i" to imdbId)))

    override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails =
        OmdbJson.details(request(mapOf("i" to imdbId, "type" to if (type == ContentType.MOVIE) "movie" else "series")))

    private suspend fun request(parameters: Map<String, String>): JSONObject {
        if (apiKey.isBlank()) throw ProviderException(ProviderProblem.AUTH)
        repeat(3) { attempt ->
            try {
                rateMutex.withLock {
                    if (System.currentTimeMillis() < blockedUntil) throw ProviderException(blockedProblem)
                    delay((spacingMs - (System.currentTimeMillis() - lastRequestAt)).coerceAtLeast(0))
                    lastRequestAt = System.currentTimeMillis()
                }
                val url = endpoint.newBuilder().addQueryParameter("apikey", apiKey)
                    .apply { parameters.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
                val json = withContext(Dispatchers.IO) { http.newCall(Request.Builder().url(url).build()).await().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> throw ProviderException(ProviderProblem.AUTH)
                        response.code == 429 -> throw ProviderException(ProviderProblem.QUOTA)
                        response.code >= 500 -> throw ProviderException(ProviderProblem.TEMPORARY)
                        !response.isSuccessful -> throw ProviderException(ProviderProblem.INVALID_RESPONSE)
                    }
                    val body = response.body ?: throw ProviderException(ProviderProblem.INVALID_RESPONSE)
                    // Never log request URLs, response bodies or exception messages (the key is in the URL).
                    JSONObject(body.string()).also { OmdbJson.check(it) }
                } }
                return json
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                val problem = (failure as? ProviderException)?.problem ?: if (failure is IOException)
                    ProviderProblem.TEMPORARY else ProviderProblem.INVALID_RESPONSE
                if (problem == ProviderProblem.AUTH || problem == ProviderProblem.QUOTA) {
                    rateMutex.withLock { blockedProblem = problem; blockedUntil = System.currentTimeMillis() + 60 * 60_000 }
                }
                if (problem != ProviderProblem.TEMPORARY || attempt == 2) throw ProviderException(problem)
                delay(500L * (1 shl attempt))
            }
        }
        throw ProviderException(ProviderProblem.TEMPORARY)
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(ProviderException(ProviderProblem.TEMPORARY))
            }
            override fun onResponse(call: Call, response: Response) {
                if (continuation.isCancelled) response.close()
                else continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}
