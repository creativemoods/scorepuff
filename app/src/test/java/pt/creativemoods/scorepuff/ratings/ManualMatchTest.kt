// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import pt.creativemoods.scorepuff.data.cache.*
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.metadata.*

@OptIn(ExperimentalCoroutinesApi::class)
class ManualMatchTest {
    private val target = TitleMapping("title:unknown:weapons", null, "Weapons", null,
        status = "AMBIGUOUS", lastAttemptAt = 1, retryAfter = Long.MAX_VALUE)
    private val details = OmdbDetails(OmdbCandidate("tt26581740", "Weapons", ContentType.MOVIE, "2025"), RatingsResult(7.4, 93))
    private fun manager(cache: FakeCache, mutex: Mutex = Mutex(), source: ImdbDetailsSource = object : ImdbDetailsSource {
        override suspend fun details(imdbId: String) = details
    }) = ManualMatchRepository(cache, source, mutex, MutableSharedFlow(extraBufferCapacity = 1), { 1_000_000 })

    @Test fun previewRequiresValidImdbIdBeforeNetworkRequest() = runTest {
        val manager = manager(FakeCache(), source = object : ImdbDetailsSource {
            override suspend fun details(imdbId: String): OmdbDetails { fail("Must not request invalid ID"); return details }
        })
        assertTrue(runCatching { manager.preview(target, "Weapons") }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun previewRejectsWrongIdAndKnownContentType() = runTest {
        val manager = manager(FakeCache())
        assertTrue(runCatching { manager.preview(target, "tt0497470") }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { manager.preview(target.copy(contentType = "SHOW"), "tt26581740") }.exceptionOrNull() is IllegalArgumentException)
    }
    @Test fun previewDoesNotSaveUntilConfirmed() = runTest {
        val cache = FakeCache()
        assertEquals(details, manager(cache).preview(target, "tt26581740"))
        assertTrue(cache.mappings.isEmpty()); assertTrue(cache.ratings.isEmpty())
    }
    @Test fun confirmationReplacesAmbiguousMappingAndCachesRealScoresOnlyForSelectedKey() = runTest {
        val cache = FakeCache(); cache.putMapping(target)
        val other = target.copy(lookupKey = "netflix:another-weapons", netflixId = "another-weapons")
        cache.putMapping(other)
        val saved = manager(cache).save(target, details)
        assertEquals("manual", saved.matchConfidence)
        assertEquals("tt26581740", cache.mapping(target.lookupKey)?.imdbId)
        assertEquals("AMBIGUOUS", cache.mapping(other.lookupKey)?.status)
        assertEquals(7.4, cache.rating("tt26581740")!!.imdb!!, 0.001)
        assertEquals(93, cache.rating("tt26581740")!!.rottenTomatoes)
    }
    @Test fun manualChoiceSurvivesRestartAndAutomaticMappingExpiry() = runTest {
        val cache = FakeCache(); manager(cache).save(target, details)
        var searchCalls = 0
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
                searchCalls++; error("Manual match must bypass search")
            }
        }
        val provider = object : RatingsProvider {
            override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails {
                assertEquals("tt26581740", imdbId); return details
            }
        }
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true, { 4_000_000_000L })
        val title = NetflixTitle(null, "Weapons", null, ScreenRect(0, 0, 302, 423))
        repo.setVisible(listOf(title)); advanceUntilIdle()
        assertEquals(0, searchCalls)
        assertEquals(RatingsResult(7.4, 93), repo.states.value[title.lookup().key]?.ratings)
        assertEquals("manual", cache.mapping(target.lookupKey)?.matchConfidence)
    }
    @Test fun inFlightAutomaticSearchCannotOverwriteNewManualChoice() = runTest {
        val cache = FakeCache(); val mutex = Mutex()
        val source = object : MetadataSource {
            override suspend fun search(title: String, type: ContentType?, page: Int, year: Int?): OmdbSearch {
                delay(1000)
                return OmdbSearch(listOf(details.candidate.copy(imdbId = "tt0497470", year = "2007")), 1)
            }
        }
        val provider = object : RatingsProvider {
            override suspend fun getRatings(imdbId: String, type: ContentType): OmdbDetails {
                fail("Saved preview ratings must be reused"); return details
            }
        }
        val repo = RatingsRepository(cache, TitleResolver(source), provider, this, true,
            { 1_000_000 }, mappingWrites = mutex)
        val title = NetflixTitle(null, "Weapons", null, ScreenRect(0, 0, 302, 423))
        repo.setVisible(listOf(title)); runCurrent()
        manager(cache, mutex).save(target, details)
        advanceUntilIdle()
        assertEquals("tt26581740", cache.mapping(target.lookupKey)?.imdbId)
        assertEquals(RatingsResult(7.4, 93), repo.states.value[title.lookup().key]?.ratings)
    }
    @Test fun removingChoiceAllowsAutomaticMatchingAgain() = runTest {
        val cache = FakeCache(); val manager = manager(cache)
        val saved = manager.save(target, details)
        manager.remove(saved)
        assertNull(cache.mapping(target.lookupKey))
        assertNotNull(cache.rating("tt26581740")) // Other titles can still share the cached ratings.
    }
    @Test fun manualPreviewCannotOverrideAnExplicitDifferentNetflixYear() = runTest {
        val manager = manager(FakeCache())
        assertTrue(runCatching { manager.preview(target.copy(netflixReleaseYear = 2007), "tt26581740") }
            .exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { manager.save(target.copy(netflixReleaseYear = 2007), details) }
            .exceptionOrNull() is IllegalArgumentException)
    }

    private class FakeCache : RatingsCache {
        val mappings = mutableMapOf<String, TitleMapping>()
        val ratings = mutableMapOf<String, CachedRating>()
        override suspend fun mapping(key: String) = mappings[key]
        override suspend fun putMapping(mapping: TitleMapping) { mappings[mapping.lookupKey] = mapping }
        override suspend fun deleteMapping(key: String) { mappings.remove(key) }
        override suspend fun rating(imdbId: String) = ratings[imdbId]
        override suspend fun putRating(rating: CachedRating) { ratings[rating.imdbId] = rating }
    }
}
