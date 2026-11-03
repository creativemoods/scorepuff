// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayAttachmentTest {
    @Test fun rejectedTokenDoesNotEscapeToTheRatingCollectorAndRetriesLater() {
        var time = 100L; var attempts = 0; var failures = 0
        val attachment = OverlayAttachment({ time }) { failures++ }
        assertFalse(attachment.show { attempts++; throw IllegalStateException("Token revoked") })
        assertFalse(attachment.attached)
        repeat(10) { assertFalse(attachment.show { attempts++ }) }
        assertEquals(1, attempts)
        assertEquals(1, failures)
        time += 1_000
        assertTrue(attachment.show { attempts++ })
        assertTrue(attachment.attached)
        assertEquals(2, attempts)
    }
    @Test fun attachedWindowIsReusedUntilHiddenThenCanAttachAgain() {
        val attachment = OverlayAttachment({ 0 }) { fail("Unexpected failure") }
        var adds = 0; var removes = 0
        repeat(5) { assertTrue(attachment.show { adds++ }) }
        attachment.hide { removes++ }
        attachment.hide { removes++ }
        assertFalse(attachment.attached)
        assertTrue(attachment.show { adds++ })
        assertEquals(2, adds)
        assertEquals(1, removes)
    }
    @Test fun failedRemovalStillClearsAttachmentAndAllowsRecovery() {
        var failures = 0
        val attachment = OverlayAttachment({ 0 }) { failures++ }
        attachment.show { }
        attachment.hide { throw IllegalArgumentException("Already removed") }
        assertFalse(attachment.attached)
        assertTrue(attachment.show { })
        assertEquals(1, failures)
    }
}
