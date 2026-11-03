// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.metadata

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.creativemoods.scorepuff.data.cache.*
import pt.creativemoods.scorepuff.ratings.*

interface ImdbDetailsSource { suspend fun details(imdbId: String): OmdbDetails }

/** User-confirmed choices override automatic matching until explicitly removed. */
class ManualMatchRepository(
    private val cache: RatingsCache,
    private val source: ImdbDetailsSource,
    private val mappingWrites: Mutex,
    private val changes: MutableSharedFlow<Unit>,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun preview(target: TitleMapping, imdbId: String): OmdbDetails {
        require(imdbId.matches(Regex("tt[0-9]{7,10}"))) { "Enter an IMDb ID such as tt26581740" }
        val details = source.details(imdbId)
        require(details.candidate.imdbId == imdbId) { "OMDb returned a different IMDb ID" }
        require(target.contentType == null || target.contentType == details.candidate.type.name) {
            "The selected IMDb result has the wrong movie/show type"
        }
        require(target.netflixReleaseYear == null || details.candidate.year.take(4).toIntOrNull() == target.netflixReleaseYear) {
            "The selected IMDb result has a different release year"
        }
        require(target.netflixRuntimeMinutes == null || details.runtimeMinutes == null ||
            kotlin.math.abs(target.netflixRuntimeMinutes.toLong() - details.runtimeMinutes) <= 2) {
            "The selected IMDb result has a different movie runtime"
        }
        return details
    }

    suspend fun save(target: TitleMapping, preview: OmdbDetails): TitleMapping {
        require(target.contentType == null || target.contentType == preview.candidate.type.name)
        require(target.netflixReleaseYear == null || preview.candidate.year.take(4).toIntOrNull() == target.netflixReleaseYear)
        require(target.netflixRuntimeMinutes == null || preview.runtimeMinutes == null ||
            kotlin.math.abs(target.netflixRuntimeMinutes.toLong() - preview.runtimeMinutes) <= 2)
        val time = now()
        val match = preview.candidate
        val mapping = target.copy(imdbId = match.imdbId, canonicalTitle = match.title, releaseYear = match.year,
            resolvedType = match.type.name, status = RatingStatus.READY.name, matchConfidence = "manual",
            lastVerifiedAt = time, lastAttemptAt = time, retryAfter = Long.MAX_VALUE, failures = 0)
        mappingWrites.withLock {
            cache.putRating(CachedRating(match.imdbId, preview.ratings.imdb, preview.ratings.rottenTomatoes,
                time, time, time + 24 * 60 * 60_000L))
            cache.putMapping(mapping)
        }
        changes.emit(Unit)
        return mapping
    }

    suspend fun remove(target: TitleMapping) {
        mappingWrites.withLock { cache.deleteMapping(target.lookupKey) }
        changes.emit(Unit)
    }
}
