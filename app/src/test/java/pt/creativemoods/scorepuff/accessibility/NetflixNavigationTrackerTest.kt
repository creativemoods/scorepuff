// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import org.junit.Assert.*
import org.junit.Test
import pt.creativemoods.scorepuff.data.models.*

class NetflixNavigationTrackerTest {
    private val card = NetflixTitle("80000001", "Heist", ContentType.MOVIE, ScreenRect(30, 500, 330, 920))
    private val details = NetflixTitle(null, "Heist", ContentType.MOVIE, ScreenRect(0, 866, 259, 997),
        2001, TitlePresentation.DETAILS)

    @Test fun actualCardClickAttachesNetflixIdToMatchingDatedDetails() {
        val tracker = NetflixNavigationTracker { 1000L }
        tracker.click(card)
        val linked = tracker.apply(listOf(details)).single()
        assertEquals(card.netflixId, linked.netflixId)
        assertEquals(2001, linked.releaseYear)
        assertTrue(tracker.hasLinkedDetails)
        assertFalse(tracker.hasPendingOrigin)
    }
    @Test fun titleAloneWithoutClickNeverCreatesNetflixIdAssociation() {
        val tracker = NetflixNavigationTracker { 1000L }
        tracker.apply(listOf(card))
        assertNull(tracker.apply(listOf(details)).single().netflixId)
    }
    @Test fun mismatchedTitleOrTypeCannotBorrowClickedId() {
        for (wrong in listOf(details.copy(title = "Another Heist"), details.copy(type = ContentType.SHOW))) {
            val tracker = NetflixNavigationTracker { 1000L }; tracker.click(card)
            assertNull(tracker.apply(listOf(wrong)).single().netflixId)
            assertNull(tracker.apply(listOf(details)).single().netflixId)
        }
    }
    @Test fun staleOrInterruptedNavigationDoesNotLink() {
        var time = 1000L; val tracker = NetflixNavigationTracker { time }
        tracker.click(card); time += 15_001
        assertNull(tracker.apply(listOf(details)).single().netflixId)
        tracker.click(card); tracker.reset()
        assertNull(tracker.apply(listOf(details)).single().netflixId)
    }
    @Test fun continueWatchingWithoutAnIdCannotInventOne() {
        val tracker = NetflixNavigationTracker { 1000L }; tracker.click(card.copy(netflixId = null))
        assertNull(tracker.apply(listOf(details)).single().netflixId)
    }
    @Test fun secondClickReplacesFirstAndLinkedIdentityDoesNotLeakOnReturnToList() {
        val tracker = NetflixNavigationTracker { 1000L }
        tracker.click(card.copy(netflixId = "different-id")); tracker.click(card)
        assertEquals(card.netflixId, tracker.apply(listOf(details)).single().netflixId)
        assertEquals(card.netflixId, tracker.apply(listOf(details)).single().netflixId)
        tracker.apply(listOf(card))
        assertNull(tracker.apply(listOf(details)).single().netflixId)
    }
    @Test fun confirmedYearCannotChangeWithoutAnotherCardClick() {
        val tracker = NetflixNavigationTracker { 1000L }; tracker.click(card)
        tracker.apply(listOf(details))
        assertNull(tracker.apply(listOf(details.copy(releaseYear = 2015))).single().netflixId)
    }
}
