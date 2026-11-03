// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.overlay

import pt.creativemoods.scorepuff.data.models.*

data class PositionedBadge(val key: CardKey, val bounds: ScreenRect)

/** Pure screen-space layout. Do not slide a badge onto a different partially clipped card. */
object BadgeLayout {
    fun place(titles: List<NetflixTitle>, viewport: ScreenRect, density: Float): List<PositionedBadge> {
        val margin = (3 * density).toInt().coerceAtLeast(1)
        val width = (32 * density).toInt().coerceAtLeast(1)
        val height = (20 * density).toInt().coerceAtLeast(1)
        return titles.mapNotNull { title ->
            if (title.presentation == TitlePresentation.DETAILS) {
                val badge = title.badgeAreas.firstNotNullOfOrNull { area ->
                    ScreenRect(area.left + margin, area.top + margin,
                        area.left + margin + width, area.top + margin + height)
                        .takeIf { area.contains(it) && viewport.contains(it) }
                }
                return@mapNotNull badge?.let { PositionedBadge(title.key, it) }
            }
            val poster = title.bounds
            val badge = ScreenRect(poster.right - margin - width, poster.top + margin,
                poster.right - margin, poster.top + margin + height)
            if (poster.contains(badge) && viewport.contains(badge)) PositionedBadge(title.key, badge) else null
        }.distinctBy { it.key }
    }
}
