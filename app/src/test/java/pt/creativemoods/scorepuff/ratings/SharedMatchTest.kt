// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import pt.creativemoods.scorepuff.data.cache.*
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.metadata.*

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMatchTest {
    private val observed = NetflixTitle(null, "Heist", ContentType.MOVIE, ScreenRect(0, 0, 300, 100), 2001, TitlePresentation.DETAILS)
    private val details = OmdbDetails(OmdbCandidate("tt0252503", "Heist", ContentType.MOVIE, "2001"), RatingsResult(6.5, 67))
    private class Cache : RatingsCache {
        val mappings = mutableMapOf<String, TitleMapping>()
        val ratings = mutableMapOf<String, CachedRating>()
        override suspend fun mapping(key: String) = mappings[key]
        override suspend fun putMapping(mapping: TitleMapping) { mappings[mapping.lookupKey] = mapping }
        override suspend fun deleteMapping(key: String) { mappings.remove(key) }
        override suspend fun rating(imdbId: String) = ratings[imdbId]
        override suspend fun putRating(rating: CachedRating) { ratings[rating.imdbId] = rating }
    }
    private fun repository(cache: Cache, returned: OmdbDetails = details, candidates: List<OmdbCandidate> = listOf(details.candidate)): SharedMatchRepository {
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
                assertEquals(2001, year)
                return OmdbSearch(candidates, candidates.size)
            }
        }
        val imdb = object : ImdbDetailsSource { override suspend fun details(imdbId: String) = returned }
        return SharedMatchRepository(cache, TitleResolver(source), imdb,
            ManualMatchRepository(cache, imdb, Mutex(), MutableSharedFlow(extraBufferCapacity = 1), { 1_000_000 }))
    }
    private val primeLink = SharedTitleLink(StreamingProvider.PRIME_VIDEO, "amzn1.dv.gti.8b9d82d8-33c1-4e89-ba53-04f03cdc5647", "Heist")

    @Test fun primeSharePreviewWritesNothingAndChecksProviderTitleAndYear() = runTest {
        val cache = Cache(); val repo = repository(cache)
        val prime = observed.copy(provider = StreamingProvider.PRIME_VIDEO)
        assertEquals(details, repo.preview(primeLink, prime).details)
        assertTrue(cache.mappings.isEmpty())
        assertTrue(runCatching { repo.preview(primeLink, observed) }.isFailure)
        assertTrue(runCatching { repo.preview(primeLink.copy(sharedTitle = "Other"), prime) }.isFailure)
        assertTrue(runCatching { repo.preview(primeLink, prime.copy(releaseYear = null)) }.isFailure)
    }
    @Test fun primeShareExplicitlySavesIdAndYearlessTitleChoicesWithoutChangingNetflix() = runTest {
        val cache = Cache(); val shares = repository(cache)
        val prime = observed.copy(provider = StreamingProvider.PRIME_VIDEO)
        val preview = shares.preview(primeLink, prime)
        assertEquals(2, preview.listTargets.size)
        shares.save(preview)
        assertNull(cache.mapping("netflix:60020557"))
        assertNull(cache.mapping(observed.copy(releaseYear = null).lookup().key))
        assertEquals("manual", cache.mapping("prime:video:${primeLink.id}")?.matchConfidence)
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch = error("Confirmed list choice must bypass search")
        }
        val provider = object : RatingsProvider {
            override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails = error("Use cached scores")
        }
        val restarted = RatingsRepository(cache, TitleResolver(source), provider, this, true, { 1_000_000 })
        val posters = listOf(ContentType.MOVIE, null).map { prime.copy(type = it, releaseYear = null, presentation = TitlePresentation.POSTER) }
        restarted.setVisible(posters); advanceUntilIdle()
        for (poster in posters) assertEquals(details.ratings, restarted.states.value[poster.lookup().key]?.ratings)
    }
    @Test fun primeShareCannotOverwriteAConflictingManualListChoice() = runTest {
        val cache = Cache()
        val prime = observed.copy(provider = StreamingProvider.PRIME_VIDEO)
        val key = prime.copy(type = null, releaseYear = null).lookup().key
        cache.putMapping(TitleMapping(key, null, "Heist", null, imdbId = "tt1234567", status = "READY", matchConfidence = "manual", lastAttemptAt = 0, retryAfter = Long.MAX_VALUE))
        assertTrue(runCatching { repository(cache).preview(primeLink, prime) }.isFailure)
        assertEquals(1, cache.mappings.size)
    }
    @Test fun uniqueMovieWithDifferentEditionRuntimeCanBeConfirmedWithoutRetainingRuntimeIdentity() = runTest {
        for (provider in StreamingProvider.entries) {
            val cache = Cache()
            val repo = repository(cache, details.copy(runtimeMinutes = 180))
            val link = if (provider == StreamingProvider.PRIME_VIDEO) primeLink else SharedTitleLink(provider, "60020557")
            val movie = observed.copy(provider = provider, runtimeMinutes = 172)
            val preview = repo.preview(link, movie)
            assertTrue(preview.runtimeDiffers)
            assertEquals(172, preview.observedRuntimeMinutes)
            assertNull(preview.target.netflixRuntimeMinutes)
            assertTrue(cache.mappings.isEmpty())
            repo.save(preview)
            assertEquals("manual", cache.mapping(preview.target.lookupKey)?.matchConfidence)
            assertNull(cache.mapping(preview.target.lookupKey)?.netflixRuntimeMinutes)
            if (provider == StreamingProvider.PRIME_VIDEO) {
                val posterKey = movie.copy(type = null, releaseYear = null).lookup().key
                assertEquals(details.candidate.imdbId, cache.mapping(posterKey)?.imdbId)
            }
        }
    }
    @Test fun durationDifferenceCannotBypassRuntimeEvidenceUsedToResolveNamesakes() = runTest {
        val cache = Cache()
        val prime = observed.copy(provider = StreamingProvider.PRIME_VIDEO, runtimeMinutes = 172)
        cache.putMapping(TitleMapping(prime.lookup().key, null, prime.title, prime.type?.name,
            imdbId = details.candidate.imdbId, releaseYear = "2001", status = "READY",
            matchConfidence = "exact_unique_title_type_year_runtime", lastAttemptAt = 0, retryAfter = Long.MAX_VALUE,
            netflixReleaseYear = 2001, netflixRuntimeMinutes = 172))
        assertTrue(runCatching { repository(cache, details.copy(runtimeMinutes = 180)).preview(primeLink, prime) }.isFailure)
        assertEquals(1, cache.mappings.size)
    }
    @Test fun uniqueMovieWithCloseRuntimeRetainsItsEvidenceWithoutWarning() = runTest {
        val preview = repository(Cache(), details.copy(runtimeMinutes = 106)).preview(primeLink,
            observed.copy(provider = StreamingProvider.PRIME_VIDEO, runtimeMinutes = 105))
        assertFalse(preview.runtimeDiffers)
        assertEquals(105, preview.target.netflixRuntimeMinutes)
    }
    @Test fun primeDetailsCannotBeAssociatedWithANetflixShare() = runTest {
        val cache = Cache()
        try {
            repository(cache).preview("60020557", observed.copy(provider = StreamingProvider.PRIME_VIDEO))
            fail("Prime observation must be rejected")
        } catch (expected: IllegalArgumentException) { assertTrue(cache.mappings.isEmpty()) }
    }
    @Test fun parsesExportedTitleLinksAndRejectsConflictingIds() {
        assertEquals("60020557", NetflixShareParser.netflixId("Watch Heist https://www.netflix.com/pt-en/title/60020557?s=a)."))
        assertEquals("60020557", NetflixShareParser.netflixId("https://netflix.com/title/60020557 https://www.netflix.com/title/60020557"))
        assertNull(NetflixShareParser.netflixId("https://netflix.com/title/60020557 https://netflix.com/title/81234567"))
    }
    @Test fun rejectsSpoofedHostsEpisodeLinksAndInvalidIds() {
        listOf("https://netflix.com.evil.test/title/60020557", "https://netflix.com@evil.test/title/60020557",
            "https://evil.test@netflix.com/title/60020557", "https://netflix.com:443/title/60020557",
            "https://netflix.com/watch/60020557", "https://netflix.com/title/12", "nflx://title/60020557",
            "https://netflix.com/title/60020557/extra").forEach { assertNull(it, NetflixShareParser.netflixId(it)) }
    }
    @Test fun previewRequiresYearAndValidIdAndWritesNothing() = runTest {
        val cache = Cache(); val repo = repository(cache)
        assertTrue(runCatching { repo.preview("bad", observed) }.isFailure)
        assertTrue(runCatching { repo.preview("60020557", observed.copy(releaseYear = null)) }.isFailure)
        assertEquals(details, repo.preview("60020557", observed).details)
        assertTrue(cache.mappings.isEmpty()); assertTrue(cache.ratings.isEmpty())
    }
    @Test fun refusesNonUniqueSearchAndMismatchedReturnedDetails() = runTest {
        assertTrue(runCatching { repository(Cache(), candidates = listOf(details.candidate, details.candidate.copy(imdbId = "tt1234567"))).preview("60020557", observed) }.isFailure)
        listOf(details.candidate.copy(year = "2015"), details.candidate.copy(title = "Other"),
            details.candidate.copy(type = ContentType.SHOW), details.candidate.copy(imdbId = "tt1234567")).forEach {
            assertTrue(runCatching { repository(Cache(), details.copy(candidate = it)).preview("60020557", observed) }.isFailure)
        }
    }
    @Test fun rejectsKnownDifferentNetflixTitle() = runTest {
        val cache = Cache()
        cache.putMapping(TitleMapping("netflix:60020557", "60020557", "Other", "MOVIE", status = "READY", lastAttemptAt = 0, retryAfter = 0))
        assertTrue(runCatching { repository(cache).preview("60020557", observed) }.isFailure)
    }
    @Test fun savedShareIsReusedOnYearlessListAfterRepositoryRestart() = runTest {
        val cache = Cache(); val shares = repository(cache)
        shares.save(shares.preview("60020557", observed))
        assertEquals("manual", cache.mapping("netflix:60020557")?.matchConfidence)
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch = error("Saved identity must bypass search")
        }
        val provider = object : RatingsProvider {
            override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails = error("Fresh ratings must be reused")
        }
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true, { 1_000_000 })
        val poster = observed.copy(netflixId = "60020557", releaseYear = null, presentation = TitlePresentation.POSTER)
        repo.setVisible(listOf(poster)); advanceUntilIdle()
        assertEquals(details.ratings, repo.states.value[poster.lookup().key]?.ratings)
        assertNull(cache.mapping("netflix:81234567"))
    }
}
