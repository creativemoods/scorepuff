// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.overlay

import org.junit.Assert.*
import org.junit.Test
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.ratings.*

class TitleBadgeTest {
    @Test fun loadingAmbiguityAndScoreUseTheSameTitlePlacementOnBothProvidersAndScreens() {
        for (provider in StreamingProvider.entries) for (presentation in TitlePresentation.entries) {
            val title = NetflixTitle(null, "A title", ContentType.MOVIE, ScreenRect(50, 100, 350, 550),
                presentation = presentation, badgeAreas = listOf(ScreenRect(350, 100, 550, 200)), provider = provider)
            val states = listOf(RatingDisplay(RatingStatus.LOADING), RatingDisplay(RatingStatus.AMBIGUOUS),
                RatingDisplay(RatingStatus.READY, RatingsResult(7.4, 90)))
            val badges = states.map { title.badge(it)!! }
            assertEquals(listOf(BadgeContent.Loading, BadgeContent.Ambiguous, BadgeContent.Score("8.2")), badges.map { it.content })
            val placements = badges.map { BadgeLayout.place(listOf(it.title), ScreenRect(0, 0, 1080, 2340), 3f) }
            assertTrue(placements.first().isNotEmpty())
            assertTrue(placements.all { it == placements.first() })
        }
    }
    @Test fun noMatchOrProviderFailureIsNotPresentedAsAmbiguityOrZero() {
        val title = NetflixTitle(null, "A title", null, ScreenRect(0, 0, 300, 400))
        for (status in listOf(RatingStatus.NOT_FOUND, RatingStatus.UNAVAILABLE, RatingStatus.ERROR, RatingStatus.KEY_MISSING)) {
            assertNull(title.badge(RatingDisplay(status)))
        }
        assertNull(title.badge(null))
        assertNull(title.badge(RatingDisplay(RatingStatus.READY, RatingsResult())))
        assertEquals(BadgeContent.Score("0.0"), title.badge(RatingDisplay(RatingStatus.READY, RatingsResult(0.0, null)))?.content)
    }
}
