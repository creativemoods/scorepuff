// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.metadata

import pt.creativemoods.scorepuff.ratings.*

data class Resolution(val status: RatingStatus, val candidate: OmdbCandidate? = null, val runtimeDetails: OmdbDetails? = null)

/** OMDb is the metadata source too. Never trust its first title hit for remakes/namesakes. */
class TitleResolver(private val source: MetadataSource, private val detailsSource: ImdbDetailsSource? = null) {
    suspend fun resolve(lookup: TitleLookup): Resolution {
        val first = source.search(lookup.title, lookup.type, 1, lookup.releaseYear)
        if (first.total == 0) return Resolution(RatingStatus.NOT_FOUND)
        // A bounded query budget must never turn incomplete search results into a confident match.
        if (first.total > 30) return Resolution(RatingStatus.AMBIGUOUS)
        val candidates = first.candidates.toMutableList()
        for (page in 2..((first.total + 9) / 10)) {
            val next = source.search(lookup.title, lookup.type, page, lookup.releaseYear)
            if (next.total != first.total) throw ProviderException(ProviderProblem.INVALID_RESPONSE)
            candidates += next.candidates
        }
        if (candidates.size < first.total) return Resolution(RatingStatus.AMBIGUOUS)
        val exact = candidates.distinctBy { it.imdbId }.filter {
            normalizeTitle(it.title) == normalizeTitle(lookup.title) && (lookup.type == null || lookup.type == it.type) &&
                (lookup.releaseYear == null || it.year.take(4).toIntOrNull() == lookup.releaseYear)
        }
        if (exact.size > 1 && exact.size <= 5 && lookup.type == pt.creativemoods.scorepuff.data.models.ContentType.MOVIE &&
            lookup.releaseYear != null && lookup.runtimeMinutes != null && lookup.runtimeMinutes > 0 && detailsSource != null) {
            // Every competing entry must have usable, consistent details before any can be excluded.
            val details = exact.map { candidate ->
                val detail = detailsSource.details(candidate.imdbId)
                if (detail.candidate.imdbId != candidate.imdbId || detail.candidate.type != candidate.type ||
                    normalizeTitle(detail.candidate.title) != normalizeTitle(candidate.title) ||
                    detail.candidate.year.take(4) != candidate.year.take(4)) {
                    throw ProviderException(ProviderProblem.INVALID_RESPONSE)
                }
                detail
            }
            if (details.any { it.runtimeMinutes == null || it.runtimeMinutes <= 0 }) return Resolution(RatingStatus.AMBIGUOUS)
            val matching = details.filter { kotlin.math.abs(it.runtimeMinutes!!.toLong() - lookup.runtimeMinutes) <= 2 }
            return if (matching.size == 1) Resolution(RatingStatus.READY, matching.single().candidate, matching.single())
                else Resolution(RatingStatus.AMBIGUOUS)
        }
        return when (exact.size) {
            0 -> Resolution(RatingStatus.NOT_FOUND)
            1 -> Resolution(RatingStatus.READY, exact.single())
            else -> Resolution(RatingStatus.AMBIGUOUS)
        }
    }
}
