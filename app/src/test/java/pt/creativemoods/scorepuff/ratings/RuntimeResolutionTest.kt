// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pt.creativemoods.scorepuff.accessibility.NetflixDetailsParser
import pt.creativemoods.scorepuff.data.cache.*
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.metadata.*

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeResolutionTest {
    private val film = OmdbCandidate("tt0120784", "Payback", ContentType.MOVIE, "1999")
    private val short = film.copy(imdbId = "tt0212419")
    private val lookup = TitleLookup(null, "Payback", ContentType.MOVIE, 1999, runtimeMinutes = 101)
    private val scores = RatingsResult(7.0, 56)
    private class Source(var candidates: List<OmdbCandidate>) : MetadataSource {
        var calls = 0
        var lastYear: Int? = null
        override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
            calls++; lastYear = year; return OmdbSearch(candidates, candidates.size)
        }
    }
    private class Details(val values: Map<String, OmdbDetails>) : ImdbDetailsSource {
        var calls = 0
        override suspend fun details(imdbId: String): OmdbDetails { calls++; return values.getValue(imdbId) }
    }
    private fun details(first: Int? = 100, second: Int? = 14) = Details(mapOf(
        film.imdbId to OmdbDetails(film, scores, first), short.imdbId to OmdbDetails(short, RatingsResult(5.9, 56), second)))
    private class Cache : RatingsCache {
        val mappings = mutableMapOf<String, TitleMapping>(); val ratings = mutableMapOf<String, CachedRating>()
        override suspend fun mapping(key: String) = mappings[key]
        override suspend fun putMapping(mapping: TitleMapping) { mappings[mapping.lookupKey] = mapping }
        override suspend fun deleteMapping(key: String) { mappings.remove(key) }
        override suspend fun rating(imdbId: String) = ratings[imdbId]
        override suspend fun putRating(rating: CachedRating) { ratings[rating.imdbId] = rating }
    }
    private val noFetch = object : RatingsProvider {
        override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails = error("Verified runtime response must supply the scores too")
    }
    @Test fun paybackRuntimeSelectsFeatureAndReusesItsVerifiedDetails() = runTest {
        val d = details(); val result = TitleResolver(Source(listOf(short, film)), d).resolve(lookup)
        assertEquals(RatingStatus.READY, result.status); assertEquals(film, result.candidate)
        assertEquals("6.3", result.runtimeDetails!!.ratings.scoreLabel); assertEquals(2, d.calls)
    }
    @Test fun twoMinuteToleranceIsInclusiveAndLargerDifferencesStayAmbiguous() = runTest {
        for (runtime in listOf(98, 99, 100, 101, 102)) {
            assertEquals(RatingStatus.READY, TitleResolver(Source(listOf(film, short)), details()).resolve(lookup.copy(runtimeMinutes = runtime)).status)
        }
        assertEquals(RatingStatus.AMBIGUOUS, TitleResolver(Source(listOf(film, short)), details()).resolve(lookup.copy(runtimeMinutes = 103)).status)
    }
    @Test fun missingCompetingRuntimeOrTwoCloseRuntimesCannotProduceAChoice() = runTest {
        for (d in listOf(details(second = null), details(second = 102), details(first = null), details(second = 0))) {
            assertEquals(RatingStatus.AMBIGUOUS, TitleResolver(Source(listOf(film, short)), d).resolve(lookup).status)
        }
    }
    @Test fun noRuntimeNoYearShowsAndTooManyCompetitorsDoNotTriggerDetailsRequests() = runTest {
        val d = details()
        for (l in listOf(lookup.copy(runtimeMinutes = null), lookup.copy(releaseYear = null), lookup.copy(type = ContentType.SHOW))) {
            TitleResolver(Source(listOf(film, short)), d).resolve(l)
        }
        TitleResolver(Source((0..5).map { film.copy(imdbId = "tt100000$it") }), d).resolve(lookup)
        assertEquals(0, d.calls)
    }
    @Test fun runtimeCannotRescueWrongYearOrTitleAndReturnedIdentityMustAgree() = runTest {
        val d = details()
        assertEquals(RatingStatus.NOT_FOUND, TitleResolver(Source(listOf(film.copy(year = "2000"), short.copy(title = "Other"))), d).resolve(lookup).status)
        assertEquals(0, d.calls)
        val bad = Details(mapOf(film.imdbId to OmdbDetails(film.copy(year = "2000"), scores, 100), short.imdbId to OmdbDetails(short, scores, 14)))
        val failure = runCatching { TitleResolver(Source(listOf(film, short)), bad).resolve(lookup) }.exceptionOrNull()
        assertTrue(failure is ProviderException)
    }
    @Test fun uniqueExactMatchNeedsNoRuntimeDetailRequests() = runTest {
        val d = details()
        assertEquals(film, TitleResolver(Source(listOf(film)), d).resolve(lookup).candidate)
        assertEquals(0, d.calls)
    }
    @Test fun parsesOnlyValidProviderAndNetflixDurations() {
        for ((text, expected) in listOf("1h 41m" to 101, "2h" to 120, "90m" to 90, "0h 5m" to 5)) {
            assertEquals(expected, NetflixDetailsParser.runtimeMinutes(text))
        }
        for (text in listOf("N/A", "1h 80m", "0m", "999999999999999999h", "4 Parts", "1 hour 41 minutes")) {
            assertNull(NetflixDetailsParser.runtimeMinutes(text))
        }
        fun json(runtime: String) = JSONObject().put("imdbID", film.imdbId).put("Title", film.title)
            .put("Type", "movie").put("Year", "1999").put("Runtime", runtime)
        assertEquals(100, OmdbJson.details(json("100 min")).runtimeMinutes)
        for (text in listOf("N/A", "0 min", "-1 min", "100", "99999999999999999 min")) assertNull(OmdbJson.details(json(text)).runtimeMinutes)
    }
    @Test fun newRuntimeEvidenceRetriesOldCachedAmbiguityAndPersistsAcrossRestart() = runTest {
        val cache = Cache(); val source = Source(listOf(film, short)); val d = details()
        cache.putMapping(TitleMapping(lookup.key, null, "Payback", "MOVIE", status = "AMBIGUOUS",
            lastAttemptAt = 0, retryAfter = Long.MAX_VALUE, netflixReleaseYear = 1999))
        val title = NetflixTitle(null, "Payback", ContentType.MOVIE, ScreenRect(0, 0, 400, 130), 1999,
            presentation = TitlePresentation.DETAILS, runtimeMinutes = 101)
        var repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { 1_000_000 })
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(scores, repo.states.value[lookup.key]?.ratings)
        assertEquals(101, cache.mapping(lookup.key)?.netflixRuntimeMinutes)
        assertEquals("exact_unique_title_type_year_runtime", cache.mapping(lookup.key)?.matchConfidence)
        repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { 1_000_001 })
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(scores, repo.states.value[lookup.key]?.ratings); assertEquals(1, source.calls); assertEquals(2, d.calls)
    }
    @Test fun unresolvedRuntimeCachesItsAttemptAndManualChoiceStillWins() = runTest {
        val cache = Cache(); val source = Source(listOf(film, short)); val d = details(second = null)
        val title = NetflixTitle(null, "Payback", ContentType.MOVIE, ScreenRect(0, 0, 400, 130), 1999, runtimeMinutes = 101)
        var repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { 1_000_000 })
        repo.setVisible(listOf(title)); advanceUntilIdle()
        repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { 1_000_001 })
        repo.setVisible(listOf(title)); advanceUntilIdle(); assertEquals(1, source.calls)
        cache.putMapping(cache.mapping(lookup.key)!!.copy(imdbId = film.imdbId, canonicalTitle = film.title,
            resolvedType = "MOVIE", status = "READY", matchConfidence = "manual"))
        cache.putRating(CachedRating(film.imdbId, 7.0, 56, 1_000_000, 1_000_000, Long.MAX_VALUE))
        repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { 1_000_002 })
        repo.setVisible(listOf(title.copy(runtimeMinutes = 150))); advanceUntilIdle()
        assertEquals(scores, repo.states.value[lookup.key]?.ratings); assertEquals(1, source.calls)
    }
    @Test fun learnedNetflixIdKeepsRuntimeForYearlessRevalidation() = runTest {
        val cache = Cache(); val source = Source(listOf(film, short)); val d = details(); var time = 1_000_000L
        val title = NetflixTitle("18957710", "Payback", ContentType.MOVIE, ScreenRect(0, 0, 400, 130), 1999,
            presentation = TitlePresentation.DETAILS, runtimeMinutes = 101)
        var repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { time }, mappingsTtlMs = 100)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals("details_title_type_year_runtime", cache.mapping("netflix:18957710")?.matchConfidence)
        assertEquals(101, cache.mapping("netflix:18957710")?.netflixRuntimeMinutes)
        time += 200
        source.candidates = listOf(film)
        repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { time }, mappingsTtlMs = 100)
        repo.setVisible(listOf(title.copy(releaseYear = null, runtimeMinutes = null, presentation = TitlePresentation.POSTER)))
        advanceUntilIdle()
        assertEquals(scores, repo.states.value["netflix:18957710"]?.ratings); assertEquals(2, source.calls); assertEquals(1999, source.lastYear)
        // A temporarily unique provider result must not discard runtime evidence for later refreshes.
        time += 200; source.candidates = listOf(film, short)
        repo = RatingsRepository(cache, TitleResolver(source, d), noFetch, this, true, { time }, mappingsTtlMs = 100)
        repo.setVisible(listOf(title.copy(releaseYear = null, runtimeMinutes = null, presentation = TitlePresentation.POSTER)))
        advanceUntilIdle()
        assertEquals(scores, repo.states.value["netflix:18957710"]?.ratings); assertEquals(3, source.calls)
    }
}
