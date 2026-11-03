// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.overlay

/** A revoked window token must not terminate the rating-state collector or cause a retry loop. */
internal class OverlayAttachment(private val now: () -> Long, private val failed: (RuntimeException) -> Unit) {
    var attached = false
        private set
    private var retryAt = 0L

    fun show(add: () -> Unit): Boolean {
        if (attached) return true
        if (now() < retryAt) return false
        return try {
            add()
            attached = true
            true
        } catch (failure: RuntimeException) {
            retryAt = now() + 1_000
            failed(failure)
            false
        }
    }

    fun hide(remove: () -> Unit) {
        if (attached) try { remove() } catch (failure: RuntimeException) { failed(failure) }
        attached = false
    }
}
