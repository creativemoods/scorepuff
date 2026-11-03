// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class RatingsAverageTest {
    @Test fun convertsPercentToTenPointScaleBeforeEqualAverage() {
        assertEquals(8.35, RatingsResult(7.4, 93).scoreOutOfTen!!, 0.00001)
        assertEquals("8.4", RatingsResult(7.4, 93).scoreLabel)
    }
    @Test fun missingSourceDoesNotLowerTheAverage() {
        assertEquals("7.4", RatingsResult(7.4, null).scoreLabel)
        assertEquals("9.3", RatingsResult(null, 93).scoreLabel)
        assertNull(RatingsResult().scoreOutOfTen)
        assertNull(RatingsResult().scoreLabel)
        assertFalse(RatingsResult().hasScores)
    }
    @Test fun realZeroIsIncludedAndMaximumFitsSingleDecimalFormat() {
        assertEquals("4.0", RatingsResult(8.0, 0).scoreLabel)
        assertEquals("4.0", RatingsResult(0.0, 80).scoreLabel)
        assertEquals("0.0", RatingsResult(0.0, 0).scoreLabel)
        assertEquals("10.0", RatingsResult(10.0, 100).scoreLabel)
    }
    @Test fun invalidSourcesCannotPoisonAnAvailableRating() {
        assertEquals("7.0", RatingsResult(Double.NaN, 70).scoreLabel)
        assertEquals("7.0", RatingsResult(Double.POSITIVE_INFINITY, 70).scoreLabel)
        assertEquals("7.0", RatingsResult(7.0, -1).scoreLabel)
        assertNull(RatingsResult(11.0, 101).scoreLabel)
    }
    @Test fun badgeUsesStableDecimalPointAcrossDeviceLocales() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("8.4", RatingsResult(7.4, 93).scoreLabel)
        } finally { Locale.setDefault(original) }
    }
}
