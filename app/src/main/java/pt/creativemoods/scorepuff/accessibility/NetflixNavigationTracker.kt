// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.ratings.normalizeTitle

/** Correlates an explicit accessibility card click with matching dated details. */
class NetflixNavigationTracker(private val now: () -> Long) {
    private data class Pending(val origin: NetflixTitle, val clickedAt: Long)
    private data class Active(val origin: NetflixTitle, val year: Int)
    private var pending: Pending? = null
    private var active: Active? = null
    val hasPendingOrigin get() = pending != null
    val hasLinkedDetails get() = active != null

    fun click(origin: NetflixTitle?) {
        pending = origin?.takeIf { it.netflixId != null && it.presentation == TitlePresentation.POSTER }
            ?.let { Pending(it, now()) }
        active = null
    }

    fun apply(titles: List<NetflixTitle>): List<NetflixTitle> {
        val detail = titles.singleOrNull { it.presentation == TitlePresentation.DETAILS }
        if (pending?.let { now() - it.clickedAt !in 0L..15_000L } == true) pending = null
        if (detail == null) {
            if (titles.any { it.presentation == TitlePresentation.POSTER }) active = null
            return titles
        }
        if (detail.releaseYear == null) { active = null; return titles }
        fun matches(origin: NetflixTitle) = normalizeTitle(origin.title) == normalizeTitle(detail.title) &&
            (origin.type == null || detail.type == null || origin.type == detail.type)
        val clicked = pending?.takeIf { matches(it.origin) }
        if (clicked != null) {
            active = Active(clicked.origin, detail.releaseYear)
            pending = null
        } else if (pending != null) { pending = null; active = null }
        val link = active?.takeIf { it.year == detail.releaseYear && matches(it.origin) }
        active = link
        if (link == null) return titles
        return titles.map { if (it === detail) it.copy(netflixId = link.origin.netflixId,
            type = detail.type ?: link.origin.type, associationEvidence = "accessibility_click") else it }
    }

    fun reset() { pending = null; active = null }
}
