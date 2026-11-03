// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import java.text.Normalizer
import java.util.Locale
import pt.creativemoods.scorepuff.data.models.ContentType
import pt.creativemoods.scorepuff.data.models.StreamingProvider
import pt.creativemoods.scorepuff.data.models.NetflixTitle

fun normalizeTitle(title: String): String = Normalizer.normalize(title, Normalizer.Form.NFKC)
    .lowercase(Locale.ROOT).replace('&', ' ').filter { it.isLetterOrDigit() }

data class TitleLookup(val netflixId: String?, val title: String, val type: ContentType?, val releaseYear: Int? = null, val associationEvidence: String? = null, val runtimeMinutes: Int? = null, val provider: StreamingProvider = StreamingProvider.NETFLIX) {
    val key: String get() = (if (provider == StreamingProvider.PRIME_VIDEO) "prime:" else "") +
        (netflixId?.let { if (provider == StreamingProvider.PRIME_VIDEO) "video:$it" else "netflix:$it" }
            ?: "title:${type?.name ?: "unknown"}:${normalizeTitle(title)}") +
        (releaseYear?.let { ":year:$it" } ?: "")
}
fun NetflixTitle.lookup() = TitleLookup(netflixId, title, type, releaseYear, associationEvidence, runtimeMinutes, provider)

data class RatingsResult(val imdb: Double? = null, val rottenTomatoes: Int? = null) {
    // Keep source scores intact for caching; normalize only when combining for display.
    // A future TMDb score can join this list on its native 0–10 scale.
    val scoreOutOfTen: Double? get() = listOfNotNull(
        imdb?.takeIf { it.isFinite() && it in 0.0..10.0 },
        rottenTomatoes?.takeIf { it in 0..100 }?.div(10.0)
    ).takeIf { it.isNotEmpty() }?.average()
    val scoreLabel: String? get() = scoreOutOfTen?.let { String.format(Locale.ROOT, "%.1f", it) }
    val hasScores get() = scoreOutOfTen != null
}

enum class RatingStatus { LOADING, READY, UNAVAILABLE, AMBIGUOUS, NOT_FOUND, ERROR, KEY_MISSING }
data class RatingDisplay(val status: RatingStatus, val ratings: RatingsResult? = null, val detail: String = "")

data class OmdbCandidate(val imdbId: String, val title: String, val type: ContentType, val year: String)
data class OmdbSearch(val candidates: List<OmdbCandidate>, val total: Int)
data class OmdbDetails(val candidate: OmdbCandidate, val ratings: RatingsResult, val runtimeMinutes: Int? = null)

interface RatingsProvider {
    suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails
}

interface MetadataSource {
    suspend fun search(title: String, type: ContentType?, page: Int, year: Int? = null): OmdbSearch
}

enum class ProviderProblem { AUTH, QUOTA, TEMPORARY, INVALID_RESPONSE }
class ProviderException(val problem: ProviderProblem) : java.io.IOException(problem.name)
