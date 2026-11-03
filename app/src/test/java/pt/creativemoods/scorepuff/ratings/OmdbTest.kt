// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import pt.creativemoods.scorepuff.data.cache.*
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.metadata.TitleResolver

@OptIn(ExperimentalCoroutinesApi::class)
class OmdbTest {
    private val movie = OmdbCandidate("tt1234567", "Example", ContentType.MOVIE, "2026")
    private val title = NetflixTitle("123", "Example", ContentType.MOVIE, ScreenRect(0, 0, 302, 423))

    @Test fun parsesBothScoresAndDoesNotUseMetacriticAsRottenTomatoes() {
        val details = OmdbJson.details(JSONObject("""{"Response":"True","imdbID":"tt1234567","Title":"Example","Type":"movie","Year":"2026","imdbRating":"8.3","Ratings":[{"Source":"Metacritic","Value":"76/100"},{"Source":"Rotten Tomatoes","Value":"92%"}]}"""))
        assertEquals(RatingsResult(8.3, 92), details.ratings)
    }
    @Test fun missingMalformedAndOutOfRangeScoresStayMissing() {
        for ((imdb, rt) in listOf("N/A" to "N/A", "11" to "101%", "NaN" to "82/100", "-1" to "-5%")) {
            val json = JSONObject("""{"Response":"True","imdbID":"tt1234567","Title":"Example","Type":"series","imdbRating":"$imdb","Ratings":[{"Source":"Rotten Tomatoes","Value":"$rt"}]}""")
            assertEquals(RatingsResult(), OmdbJson.details(json).ratings)
        }
    }
    @Test fun aRealZeroIsDifferentFromMissing() {
        val json = JSONObject("""{"Response":"True","imdbID":"tt1234567","Title":"Example","Type":"movie","imdbRating":"0.0","Ratings":[{"Source":"Rotten Tomatoes","Value":"0%"}]}""")
        assertEquals(RatingsResult(0.0, 0), OmdbJson.details(json).ratings)
    }
    @Test fun missingRottenTomatoesStillAllowsImdbOnlyBadge() {
        val json = JSONObject("""{"Response":"True","imdbID":"tt1234567","Title":"Example","Type":"movie","imdbRating":"7.1"}""")
        assertEquals(RatingsResult(7.1, null), OmdbJson.details(json).ratings)
    }
    @Test fun apiFailuresAreClassifiedWithoutRetainingErrorBody() {
        for ((error, problem) in listOf("Invalid API key!" to ProviderProblem.AUTH, "Request limit reached!" to ProviderProblem.QUOTA)) {
            val failure = runCatching { OmdbJson.check(JSONObject().put("Response", "False").put("Error", error)) }.exceptionOrNull()
            assertEquals(problem, (failure as ProviderException).problem)
            assertEquals(problem.name, failure.message)
        }
    }
    @Test fun unknownResponseIsNeverTreatedAsAnEmptySearch() {
        assertTrue(runCatching { OmdbJson.search(JSONObject("""{"Response":"True"}""")) }.exceptionOrNull() is ProviderException)
    }
    @Test fun uniqueExactTitleAndTypeResolvesWithoutInventingYear() = runTest {
        val source = FakeSource(listOf(movie, movie.copy(imdbId = "tt9999999", title = "Example Returns")))
        assertEquals(movie, TitleResolver(source).resolve(title.lookup()).candidate)
    }
    @Test fun remakesWithSameTitleAreAmbiguous() = runTest {
        val result = TitleResolver(FakeSource(listOf(movie, movie.copy(imdbId = "tt9999999", year = "1985")))).resolve(title.lookup())
        assertEquals(RatingStatus.AMBIGUOUS, result.status)
        assertNull(result.candidate)
    }
    @Test fun unknownContinueWatchingTypeDoesNotPickBetweenFilmAndSeries() = runTest {
        val source = FakeSource(listOf(movie, movie.copy(imdbId = "tt9999999", type = ContentType.SHOW)))
        assertEquals(RatingStatus.AMBIGUOUS, TitleResolver(source).resolve(TitleLookup(null, "Example", null)).status)
    }
    @Test fun nonexactOrWrongTypeSearchResultsAreRejected() = runTest {
        val source = FakeSource(listOf(movie.copy(type = ContentType.SHOW), movie.copy(title = "Example 2")))
        assertEquals(RatingStatus.NOT_FOUND, TitleResolver(source).resolve(title.lookup()).status)
    }
    @Test fun paginatedSearchCannotHideAnAmbiguousMatch() = runTest {
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?) = if (page == 1)
                OmdbSearch(listOf(movie), 11) else OmdbSearch(listOf(movie.copy(imdbId = "tt9999999")), 11)
        }
        assertEquals(RatingStatus.AMBIGUOUS, TitleResolver(source).resolve(title.lookup()).status)
    }
    @Test fun oversizedSearchIsNotTreatedAsAUniqueMatch() = runTest {
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?) = OmdbSearch(listOf(movie), 31)
        }
        assertEquals(RatingStatus.AMBIGUOUS, TitleResolver(source).resolve(title.lookup()).status)
    }
    @Test fun scrollingAndRepositoryRestartReusePersistentCaches() = runTest {
        val cache = FakeCache(); val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true, { 1_000_000L })
        repo.setVisible(listOf(title)); advanceUntilIdle()
        repeat(20) { repo.setVisible(emptyList()); repo.setVisible(listOf(title)) }
        advanceUntilIdle()
        val restarted = RatingsRepository(cache, TitleResolver(source), provider, this, true, { 1_000_001L })
        restarted.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(1, source.calls); assertEquals(1, provider.calls)
        assertEquals(RatingStatus.READY, restarted.states.value[title.lookup().key]?.status)
    }
    @Test fun ratingsExpireBeforeConfirmedMappings() = runTest {
        val cache = FakeCache(); val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        var time = 1_000_000L
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true, { time }, 100, 1000)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        time += 101; repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(1, source.calls); assertEquals(2, provider.calls)
        time += 1001; repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(2, source.calls); assertEquals(3, provider.calls)
    }
    @Test fun failedAndAmbiguousMatchesAreNegativelyCached() = runTest {
        for (candidates in listOf(emptyList(), listOf(movie, movie.copy(imdbId = "tt9999999")))) {
            val cache = FakeCache(); val source = FakeSource(candidates); val provider = FakeProvider(movie)
            repeat(2) {
                val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true, { 1_000_000L })
                repo.setVisible(listOf(title)); advanceUntilIdle()
            }
            assertEquals(1, source.calls); assertEquals(0, provider.calls)
        }
    }
    @Test fun providerFailureBacksOffAcrossRestartsAndPreservesLastSuccess() = runTest {
        val cache = FakeCache(); val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        var time = 1_000_000L
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true, { time }, 100)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        time += 101; provider.failure = ProviderException(ProviderProblem.TEMPORARY)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(RatingStatus.ERROR, repo.states.value[title.lookup().key]?.status)
        val restarted = RatingsRepository(cache, TitleResolver(source), provider, this, true, { time })
        restarted.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(2, provider.calls)
        assertEquals(1_000_000L, cache.ratings[movie.imdbId]?.lastSuccessAt)
    }
    @Test fun missingKeyNeverCallsNetwork() = runTest {
        val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        val repo = RatingsRepository(FakeCache(), TitleResolver(source), provider, this, false)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(0, source.calls); assertEquals(0, provider.calls)
        assertEquals(RatingStatus.KEY_MISSING, repo.states.value[title.lookup().key]?.status)
    }
    @Test fun staleResponseWithDifferentImdbIdCannotBecomeARating() = runTest {
        val provider = FakeProvider(movie.copy(imdbId = "tt9999999"))
        val repo = RatingsRepository(FakeCache(), TitleResolver(FakeSource(listOf(movie))), provider, this, true)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(RatingStatus.ERROR, repo.states.value[title.lookup().key]?.status)
    }
    @Test fun httpClientEncodesTitleAndClassifiesQuotaWithoutLeakingKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Response":"True","Search":[],"totalResults":"0"}"""))
            server.enqueue(MockResponse().setBody("""{"Response":"False","Error":"Request limit reached!"}"""))
            val client = OmdbClient("test-key", endpoint = server.url("/"), spacingMs = 0)
            client.search("Salt & Pepper", ContentType.MOVIE, 1)
            val request = server.takeRequest()
            assertEquals("Salt & Pepper", request.requestUrl?.queryParameter("s"))
            assertEquals("movie", request.requestUrl?.queryParameter("type"))
            val failure = runCatching { client.search("Other", null, 1) }.exceptionOrNull() as ProviderException
            assertEquals(ProviderProblem.QUOTA, failure.problem)
            assertFalse(failure.toString().contains("test-key"))
            runCatching { client.search("Again", null, 1) }
            assertEquals(2, server.requestCount) // Circuit prevents another network call.
        }
    }
    @Test fun primeMappingsIgnoreNetflixNegativeMatchesAndReuseImdbScores() = runTest {
        val cache = FakeCache()
        val netflix = title.copy(netflixId = null)
        val prime = netflix.copy(provider = StreamingProvider.PRIME_VIDEO)
        cache.putMapping(TitleMapping(netflix.lookup().key, null, netflix.title, netflix.type?.name,
            status = "AMBIGUOUS", lastAttemptAt = 1, retryAfter = Long.MAX_VALUE))
        val provider = FakeProvider(movie)
        val repo = RatingsRepository(cache, TitleResolver(FakeSource(listOf(movie))), provider, this, true)
        repo.setVisible(listOf(netflix, prime)); advanceUntilIdle()
        assertEquals(RatingStatus.AMBIGUOUS, repo.states.value[netflix.lookup().key]?.status)
        assertEquals(RatingStatus.READY, repo.states.value[prime.lookup().key]?.status)
        val dated = prime.copy(releaseYear = 2026)
        repo.setVisible(listOf(dated)); advanceUntilIdle()
        assertEquals(RatingStatus.READY, repo.states.value[dated.lookup().key]?.status)
        assertEquals(1, provider.calls)
        assertEquals("AMBIGUOUS", cache.mapping(netflix.lookup().key)?.status)
    }
    @Test fun twoNetflixLookupsForSameImdbIdShareOneInFlightRatingsFetch() = runTest {
        var calls = 0
        val provider = object : RatingsProvider {
            override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails {
                calls++; delay(100); return OmdbDetails(movie, RatingsResult(8.3, 92))
            }
        }
        val repo = RatingsRepository(FakeCache(), TitleResolver(FakeSource(listOf(movie))), provider, this, true)
        repo.setVisible(listOf(title, title.copy(netflixId = null, type = null)))
        advanceUntilIdle()
        assertEquals(1, calls)
        assertEquals(2, repo.states.value.values.count { it.status == RatingStatus.READY })
    }
    @Test fun queuedLookupsShowLoadingBeforeReceivingANetworkSlot() = runTest {
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
                delay(10_000)
                return OmdbSearch(listOf(movie), 1)
            }
        }
        val repo = RatingsRepository(FakeCache(), TitleResolver(source), FakeProvider(movie), this, true)
        val titles = (1..5).map { title.copy(netflixId = "$it") }
        repo.setVisible(titles); runCurrent()
        assertEquals(5, repo.states.value.values.count { it.status == RatingStatus.LOADING })
        repo.setVisible(emptyList()); advanceUntilIdle()
    }
    @Test fun leavingNetflixCancelsPendingTitleLookups() = runTest {
        var started = 0; var cancelled = 0
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
                started++
                try { delay(10_000) } finally { cancelled++ }
                return OmdbSearch(listOf(movie), 1)
            }
        }
        val provider = FakeProvider(movie)
        val repo = RatingsRepository(FakeCache(), TitleResolver(source), provider, this, true)
        repo.setVisible(listOf(title)); runCurrent()
        repo.setVisible(emptyList()); advanceUntilIdle()
        assertEquals(1, started); assertEquals(1, cancelled); assertEquals(0, provider.calls)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(RatingStatus.READY, repo.states.value[title.lookup().key]?.status)
    }

    @Test fun releaseYearDisambiguatesRemakesAndIsSentToMetadataSource() = runTest {
        var requestedYear: Int? = null
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
                requestedYear = year
                return OmdbSearch(listOf(movie.copy(year = "2025"), movie.copy(imdbId = "tt9999999", year = "2007")), 2)
            }
        }
        val result = TitleResolver(source).resolve(title.copy(releaseYear = 2025).lookup())
        assertEquals(2025, requestedYear)
        assertEquals("2025", result.candidate?.year)
    }
    @Test fun differentYearIsNotAcceptedEvenIfProviderIgnoresYearFilter() = runTest {
        val source = FakeSource(listOf(movie.copy(year = "2007")))
        assertEquals(RatingStatus.NOT_FOUND, TitleResolver(source).resolve(title.copy(releaseYear = 2025).lookup()).status)
    }
    @Test fun sameTitleYearAndTypeStillMustHaveUniqueImdbId() = runTest {
        val source = FakeSource(listOf(movie, movie.copy(imdbId = "tt9999999")))
        assertEquals(RatingStatus.AMBIGUOUS, TitleResolver(source).resolve(title.copy(releaseYear = 2026).lookup()).status)
    }
    @Test fun yearQualifiedLookupSupersedesNegativeCacheWhenNetflixIdIsKnown() = runTest {
        val cache = FakeCache()
        cache.putMapping(TitleMapping(title.lookup().key, title.netflixId, title.title, title.type?.name,
            status = "AMBIGUOUS", lastAttemptAt = 1, retryAfter = Long.MAX_VALUE))
        val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true)
        val dated = title.copy(releaseYear = 2026)
        repo.setVisible(listOf(dated)); advanceUntilIdle()
        assertEquals(RatingStatus.READY, repo.states.value[dated.lookup().key]?.status)
        assertEquals(2026, cache.mapping(dated.lookup().key)?.netflixReleaseYear)
        assertEquals("READY", cache.mapping(title.lookup().key)?.status)
        assertEquals("details_title_type_year", cache.mapping(title.lookup().key)?.matchConfidence)
    }
    @Test fun omdbClientIncludesExplicitYearParameter() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"Response":"True","Search":[],"totalResults":"0"}"""))
            OmdbClient("test-key", endpoint = server.url("/"), spacingMs = 0).search("Weapons", ContentType.MOVIE, 1, 2025)
            assertEquals("2025", server.takeRequest().requestUrl?.queryParameter("y"))
        }
    }

    @Test fun confirmedDetailsTeachYearlessListLookupAndSurviveRepositoryRestart() = runTest {
        val cache = FakeCache()
        val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        cache.putMapping(TitleMapping(title.lookup().key, title.netflixId, title.title, title.type?.name,
            status = "AMBIGUOUS", lastAttemptAt = 1, retryAfter = Long.MAX_VALUE))
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true)
        val detail = title.copy(releaseYear = 2026, presentation = TitlePresentation.DETAILS)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(RatingStatus.AMBIGUOUS, repo.states.value[title.lookup().key]?.status)
        repo.setVisible(listOf(detail)); advanceUntilIdle()
        assertEquals("details_title_type_year", cache.mapping(title.lookup().key)?.matchConfidence)
        assertEquals(movie.imdbId, cache.mapping(title.lookup().key)?.imdbId)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(RatingStatus.READY, repo.states.value[title.lookup().key]?.status)
        val restarted = RatingsRepository(cache, TitleResolver(source), provider, this, true)
        restarted.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(RatingStatus.READY, restarted.states.value[title.lookup().key]?.status)
        assertEquals(1, source.calls); assertEquals(1, provider.calls)
    }
    @Test fun sameTitleWithDifferentNetflixIdDoesNotInheritLearnedMapping() = runTest {
        val cache = FakeCache(); val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true)
        repo.setVisible(listOf(title.copy(releaseYear = 2026, presentation = TitlePresentation.DETAILS))); advanceUntilIdle()
        assertNull(cache.mapping(title.copy(netflixId = "999").lookup().key))
    }
    @Test fun idlessDetailsDoNotPromoteYearlessTitleOnlyMapping() = runTest {
        val cache = FakeCache(); val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true)
        val detail = title.copy(netflixId = null, releaseYear = 2026, presentation = TitlePresentation.DETAILS)
        repo.setVisible(listOf(detail)); advanceUntilIdle()
        assertNull(cache.mapping(detail.copy(releaseYear = null).lookup().key))
    }
    @Test fun learningPreservesExplicitManualListChoice() = runTest {
        val cache = FakeCache(); val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        cache.putMapping(TitleMapping(title.lookup().key, title.netflixId, title.title, title.type?.name,
            imdbId = "tt9999999", status = "READY", matchConfidence = "manual", lastAttemptAt = 1, retryAfter = Long.MAX_VALUE))
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true)
        repo.setVisible(listOf(title.copy(releaseYear = 2026, presentation = TitlePresentation.DETAILS))); advanceUntilIdle()
        assertEquals("manual", cache.mapping(title.lookup().key)?.matchConfidence)
        assertEquals("tt9999999", cache.mapping(title.lookup().key)?.imdbId)
    }

    @Test fun contradictoryConfirmedDetailsFailClosedForYearlessList() = runTest {
        val cache = FakeCache(); val source = FakeSource(listOf(movie)); val provider = FakeProvider(movie)
        cache.putMapping(TitleMapping(title.lookup().key, title.netflixId, title.title, title.type?.name,
            imdbId = "tt9999999", status = "READY", matchConfidence = "details_title_type_year",
            lastVerifiedAt = 1_000_000, lastAttemptAt = 1_000_000, retryAfter = Long.MAX_VALUE))
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true, { 1_000_001 })
        repo.setVisible(listOf(title.copy(releaseYear = 2026, presentation = TitlePresentation.DETAILS))); advanceUntilIdle()
        assertEquals("conflicting_details", cache.mapping(title.lookup().key)?.matchConfidence)
        assertNull(cache.mapping(title.lookup().key)?.imdbId)
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(RatingStatus.AMBIGUOUS, repo.states.value[title.lookup().key]?.status)
    }
    @Test fun learnedAssociationRetainsKnownYearWhenReverifiedAfterMappingExpiry() = runTest {
        val requestedYears = mutableListOf<Int?>()
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
                requestedYears += year; return OmdbSearch(listOf(movie), 1)
            }
        }
        var time = 1_000_000L
        val repo = RatingsRepository(FakeCache(), TitleResolver(source), FakeProvider(movie), this, true,
            { time }, ratingsTtlMs = 1000, mappingsTtlMs = 100)
        repo.setVisible(listOf(title.copy(releaseYear = 2026, presentation = TitlePresentation.DETAILS))); advanceUntilIdle()
        time += 101
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(listOf(2026, 2026), requestedYears)
        assertEquals(RatingStatus.READY, repo.states.value[title.lookup().key]?.status)
    }

    private class FakeSource(val results: List<OmdbCandidate>) : MetadataSource {
        var calls = 0
        override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
            calls++; return OmdbSearch(results, results.size)
        }
    }
    private class FakeProvider(val candidate: OmdbCandidate) : RatingsProvider {
        var calls = 0; var failure: ProviderException? = null
        override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails {
            calls++; failure?.let { throw it }; return OmdbDetails(candidate, RatingsResult(8.3, 92))
        }
    }
    private class FakeCache : RatingsCache {
        val mappings = mutableMapOf<String, TitleMapping>(); val ratings = mutableMapOf<String, CachedRating>()
        override suspend fun deleteMapping(key: String) { mappings.remove(key) }
        override suspend fun mapping(key: String) = mappings[key]
        override suspend fun putMapping(mapping: TitleMapping) { mappings[mapping.lookupKey] = mapping }
        override suspend fun rating(imdbId: String) = ratings[imdbId]
        override suspend fun putRating(rating: CachedRating) { ratings[rating.imdbId] = rating }
    }
}
