// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import org.junit.Assert.*
import org.junit.Test
import pt.creativemoods.scorepuff.metadata.*
import pt.creativemoods.scorepuff.data.models.StreamingProvider

class PrimeShareParserTest {
    private val id = "amzn1.dv.gti.8b9d82d8-33c1-4e89-ba53-04f03cdc5647"
    private val url = "https://app.primevideo.com/detail?gti=$id&ref_=atv_dp_share_mv&r=web"
    @Test fun capturedSharePayloadExtractsGtiAndDisplayedTitleWithoutFollowingLinks() {
        val share = StreamingShareParser.parse("Hey I’m watching Quantum of Solace. Check it out now on Prime Video!\n$url")!!
        assertEquals(StreamingProvider.PRIME_VIDEO, share.provider)
        assertEquals(id, share.id)
        assertEquals("Quantum of Solace", share.sharedTitle)
        assertEquals(id, PrimeShareParser.primeId("$url $url"))
    }
    @Test fun rejectsSpoofedMalformedAndUnsupportedLinks() {
        listOf(url.replace("app.primevideo.com", "app.primevideo.com.evil.test"),
            url.replace("app.primevideo.com", "app.primevideo.com@evil.test"),
            url.replace("app.primevideo.com", "evil.test@app.primevideo.com"),
            url.replace("app.primevideo.com", "app.primevideo.com:443"),
            url.replace("https:", "http:"), url.replace("/detail?", "/watch?"),
            url.replace(id, "bad-id"), "$url&gti=$id", "$url#fragment",
            "https://a.co/short", "https://www.amazon.com/dp/B000000000").forEach { assertNull(it, PrimeShareParser.primeId(it)) }
    }
    @Test fun conflictingIdentitiesAndMixedProvidersAreRejected() {
        assertNull(PrimeShareParser.primeId("$url ${url.replace("8b9d82d8", "aaaaaaaa")}"))
        assertNull(StreamingShareParser.parse("$url https://netflix.com/title/60020557"))
        assertNull(StreamingShareParser.parse("x".repeat(10_001) + url))
        assertEquals(StreamingProvider.NETFLIX, StreamingShareParser.parse("https://netflix.com/title/60020557")?.provider)
    }
}
