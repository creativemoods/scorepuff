// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import pt.creativemoods.scorepuff.data.cache.*
import pt.creativemoods.scorepuff.data.models.ContentType
import pt.creativemoods.scorepuff.data.models.NetflixTitle
import pt.creativemoods.scorepuff.data.models.StreamingProvider
import pt.creativemoods.scorepuff.metadata.TitleResolver

class RatingsRepository(
    private val cache: RatingsCache,
    private val resolver: TitleResolver,
    private val provider: RatingsProvider,
    private val scope: CoroutineScope,
    private val configured: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val ratingsTtlMs: Long = 24 * 60 * 60_000L,
    private val mappingsTtlMs: Long = 30 * 24 * 60 * 60_000L,
    private val mappingWrites: Mutex = Mutex()
) {
    private val mutable = MutableStateFlow<Map<String, RatingDisplay>>(emptyMap())
    val states = mutable.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val nextRefresh = mutableMapOf<String, Long>()
    private val lastLookup = mutableMapOf<String, TitleLookup>()
    private val concurrency = Semaphore(2)
    private val ratingFetch = Mutex()

    // Invoked on the main thread by the service. Only current visible titles consume request budget.
    fun setVisible(titles: List<NetflixTitle>) {
        val lookups = titles.map { it.lookup() }.distinctBy { it.key }.take(40)
        val keys = lookups.map { it.key }.toSet()
        jobs.filterKeys { it !in keys }.values.toList().forEach { it.cancel() }
        for (lookup in lookups) {
            val key = lookup.key
            if (lastLookup[key]?.let { it != lookup } == true) {
                jobs.remove(key)?.cancel()
                nextRefresh.remove(key)
            }
            lastLookup[key] = lookup
            if (jobs[key]?.isActive == true || now() < (nextRefresh[key] ?: 0)) continue
            if (!configured) { publish(key, RatingDisplay(RatingStatus.KEY_MISSING, detail = "OMDb key missing")); continue }
            // Queued lookups also need a loading badge while waiting for a request slot.
            publish(key, RatingDisplay(RatingStatus.LOADING))
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    concurrency.withPermit {
                        val (display, refreshAt) = load(lookup)
                        nextRefresh[key] = refreshAt
                        publish(key, display)
                    }
                } catch (failure: CancellationException) { throw failure }
                catch (failure: Exception) {
                    nextRefresh[key] = now() + 60_000
                    publish(key, RatingDisplay(RatingStatus.ERROR, detail = "Local cache error · retry later"))
                } finally {
                    if (jobs[key] === currentCoroutineContext()[Job]) jobs.remove(key)
                }
            }
            jobs[key] = job
            job.start()
        }
        // Bound in-memory diagnostics, preserving visible items and persistent Room entries.
        if (mutable.value.size > 250) {
            mutable.update { it.filterKeys { key -> key in keys } }
            nextRefresh.keys.retainAll(keys)
            lastLookup.keys.retainAll(keys)
        }
    }

    fun invalidateMatches() {
        jobs.values.toList().forEach { it.cancel() }
        jobs.clear()
        nextRefresh.clear()
        mutable.value = emptyMap()
    }

    private suspend fun storeAutomatic(mapping: TitleMapping): TitleMapping = mappingWrites.withLock {
        cache.mapping(mapping.lookupKey)?.takeIf { it.matchConfidence == "manual" ||
            (it.matchConfidence in setOf("details_title_type_year", "details_title_type_year_runtime", "conflicting_details") && now() < it.retryAfter) }
            ?: mapping.also { cache.putMapping(it) }
    }

    private suspend fun rememberNetflixId(lookup: TitleLookup, confirmed: TitleMapping) {
        if (lookup.provider != StreamingProvider.NETFLIX || lookup.netflixId == null || lookup.releaseYear == null || confirmed.imdbId == null ||
            confirmed.status != RatingStatus.READY.name) return
        val baseKey = lookup.copy(releaseYear = null, runtimeMinutes = null).key
        val time = now()
        val learned = confirmed.copy(lookupKey = baseKey, netflixReleaseYear = null,
            matchConfidence = if (confirmed.matchConfidence?.contains("runtime") == true) "details_title_type_year_runtime" else "details_title_type_year",
            retryAfter = time + mappingsTtlMs,
            lastVerifiedAt = time, lastAttemptAt = time)
        mappingWrites.withLock {
            val existing = cache.mapping(baseKey)
            when {
                existing?.matchConfidence == "manual" -> Unit
                existing?.matchConfidence == "conflicting_details" && time < existing.retryAfter -> Unit
                existing?.matchConfidence in setOf("details_title_type_year", "details_title_type_year_runtime") && existing?.imdbId != confirmed.imdbId ->
                    cache.putMapping(learned.copy(imdbId = null, status = RatingStatus.AMBIGUOUS.name,
                        matchConfidence = "conflicting_details", retryAfter = time + ratingsTtlMs))
                else -> cache.putMapping(learned)
            }
        }
        // A previously ambiguous list lookup must reload the stronger ID association on return.
        jobs.remove(baseKey)?.cancel()
        nextRefresh.remove(baseKey)
        mutable.update { it - baseKey }
    }

    private fun publish(key: String, value: RatingDisplay) { mutable.update { it + (key to value) } }

    private suspend fun load(lookup: TitleLookup): Pair<RatingDisplay, Long> {
        val time = now()
        var mapping = cache.mapping(lookup.key)?.takeIf {
            normalizeTitle(it.netflixTitle) == normalizeTitle(lookup.title) && it.contentType == lookup.type?.name && it.netflixReleaseYear == lookup.releaseYear
        }
        // Extra runtime evidence may retry an older negative cache entry once; manual choices retain priority.
        if (mapping?.matchConfidence != "manual" && lookup.runtimeMinutes != null &&
            mapping?.netflixRuntimeMinutes != lookup.runtimeMinutes &&
            (mapping?.status != RatingStatus.READY.name || mapping.netflixRuntimeMinutes != null)) mapping = null
        var runtimeDetails: OmdbDetails? = null
        if (mapping != null && time < mapping.retryAfter && mapping.status != RatingStatus.READY.name) {
            return mappingDisplay(mapping) to mapping.retryAfter
        }
        if (mapping?.matchConfidence != "manual" &&
            (mapping?.imdbId == null || mapping.lastVerifiedAt == null || time - mapping.lastVerifiedAt >= mappingsTtlMs)) {
            try {
                val knownYear = lookup.releaseYear ?: mapping?.takeIf { it.matchConfidence in setOf("details_title_type_year", "details_title_type_year_runtime") }
                    ?.releaseYear?.take(4)?.toIntOrNull()
                // Retain observed runtime even if an intervening refresh needed only title/year.
                val knownRuntime = lookup.runtimeMinutes ?: mapping?.netflixRuntimeMinutes
                val resolution = resolver.resolve(lookup.copy(releaseYear = knownYear, runtimeMinutes = knownRuntime))
                runtimeDetails = resolution.runtimeDetails
                val match = resolution.candidate
                mapping = TitleMapping(lookup.key, lookup.netflixId, lookup.title, lookup.type?.name,
                    match?.imdbId, match?.title, match?.year, match?.type?.name, resolution.status.name,
                    if (match != null) if (resolution.runtimeDetails != null) {
                        if (lookup.releaseYear != null) "exact_unique_title_type_year_runtime" else "details_title_type_year_runtime"
                    } else if (lookup.releaseYear != null) "exact_unique_title_type_year" else if (knownYear != null) "details_title_type_year" else "exact_unique_title_and_available_type_no_year" else null,
                    if (match != null) time else null, time,
                    time + if (match != null) mappingsTtlMs else ratingsTtlMs, netflixReleaseYear = lookup.releaseYear, netflixRuntimeMinutes = knownRuntime)
                mapping = storeAutomatic(mapping)
                if (mapping.imdbId == null) return mappingDisplay(mapping) to mapping.retryAfter
            } catch (failure: ProviderException) {
                val retry = time + backoff(mapping?.failures ?: 0, failure.problem)
                val failed = TitleMapping(lookup.key, lookup.netflixId, lookup.title, lookup.type?.name,
                    status = RatingStatus.ERROR.name, lastAttemptAt = time, retryAfter = retry,
                    failures = (mapping?.failures ?: 0) + 1, netflixReleaseYear = lookup.releaseYear, netflixRuntimeMinutes = lookup.runtimeMinutes)
                mapping = storeAutomatic(failed)
                if (mapping.matchConfidence != "manual") return providerError(failure.problem) to retry
            }
        }
        val confirmed = requireNotNull(mapping)
        rememberNetflixId(lookup, confirmed)
        return ratingFetch.withLock {
            runtimeDetails?.takeIf { it.candidate.imdbId == confirmed.imdbId }?.let { details ->
                val fetchedAt = now()
                cache.putRating(CachedRating(details.candidate.imdbId, details.ratings.imdb, details.ratings.rottenTomatoes,
                    fetchedAt, fetchedAt, fetchedAt + ratingsTtlMs))
            }
            loadRating(confirmed)
        }
    }

    private suspend fun loadRating(confirmed: TitleMapping): Pair<RatingDisplay, Long> {
        val time = now()
        val imdbId = requireNotNull(confirmed.imdbId)
        val cached = cache.rating(imdbId)
        if (cached != null && time < cached.retryAfter) {
            return if (cached.failures > 0) RatingDisplay(RatingStatus.ERROR, detail = "Provider backoff · retry later") to cached.retryAfter
            else scoreDisplay(RatingsResult(cached.imdb, cached.rottenTomatoes)) to cached.retryAfter
        }
        try {
            val type = ContentType.valueOf(requireNotNull(confirmed.resolvedType))
            val details = provider.getRatings(imdbId, type)
            if (details.candidate.imdbId != imdbId || details.candidate.type != type ||
                normalizeTitle(details.candidate.title) != normalizeTitle(requireNotNull(confirmed.canonicalTitle)) ||
                (confirmed.netflixReleaseYear != null && details.candidate.year.take(4).toIntOrNull() != confirmed.netflixReleaseYear) ||
                (confirmed.matchConfidence?.contains("runtime") == true &&
                    (confirmed.netflixRuntimeMinutes == null || details.runtimeMinutes == null ||
                        kotlin.math.abs(details.runtimeMinutes.toLong() - confirmed.netflixRuntimeMinutes) > 2))) {
                throw ProviderException(ProviderProblem.INVALID_RESPONSE)
            }
            val retry = now() + ratingsTtlMs
            cache.putRating(CachedRating(imdbId, details.ratings.imdb, details.ratings.rottenTomatoes, now(), now(), retry))
            return scoreDisplay(details.ratings) to retry
        } catch (failure: ProviderException) {
            val retry = time + backoff(cached?.failures ?: 0, failure.problem)
            cache.putRating(CachedRating(imdbId, cached?.imdb, cached?.rottenTomatoes, cached?.lastSuccessAt,
                time, retry, (cached?.failures ?: 0) + 1))
            return providerError(failure.problem) to retry
        }
    }

    private fun scoreDisplay(scores: RatingsResult) = if (scores.hasScores) RatingDisplay(RatingStatus.READY, scores)
        else RatingDisplay(RatingStatus.UNAVAILABLE, detail = "OMDb has no scores")
    private fun mappingDisplay(mapping: TitleMapping): RatingDisplay {
        val status = runCatching { RatingStatus.valueOf(mapping.status) }.getOrDefault(RatingStatus.ERROR)
        return RatingDisplay(status, detail = when (status) {
            RatingStatus.AMBIGUOUS -> "Ambiguous title · needs year or manual match"
            RatingStatus.NOT_FOUND -> "No unique exact title/type match"
            else -> "Provider backoff · retry later"
        })
    }
    private fun providerError(problem: ProviderProblem) = RatingDisplay(RatingStatus.ERROR, detail = when (problem) {
        ProviderProblem.AUTH -> "OMDb key invalid or not activated"
        ProviderProblem.QUOTA -> "OMDb request quota reached"
        ProviderProblem.TEMPORARY -> "OMDb unavailable · retry later"
        ProviderProblem.INVALID_RESPONSE -> "OMDb response could not be verified"
    })
    private fun backoff(failures: Int, problem: ProviderProblem): Long = when (problem) {
        ProviderProblem.AUTH, ProviderProblem.QUOTA -> 60 * 60_000L
        else -> (60_000L * (1L shl failures.coerceIn(0, 8))).coerceAtMost(6 * 60 * 60_000L)
    }
}
