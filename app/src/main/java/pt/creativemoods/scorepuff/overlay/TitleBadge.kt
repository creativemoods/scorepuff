// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.overlay

import pt.creativemoods.scorepuff.data.models.NetflixTitle
import pt.creativemoods.scorepuff.ratings.RatingDisplay
import pt.creativemoods.scorepuff.ratings.RatingStatus

sealed interface BadgeContent {
    data class Score(val label: String) : BadgeContent
    data object Loading : BadgeContent
    data object Ambiguous : BadgeContent
}

data class TitleBadge(val title: NetflixTitle, val content: BadgeContent)

fun NetflixTitle.badge(display: RatingDisplay?): TitleBadge? {
    val content = when (display?.status) {
        RatingStatus.READY -> display.ratings?.scoreLabel?.let { BadgeContent.Score(it) }
        RatingStatus.LOADING -> BadgeContent.Loading
        RatingStatus.AMBIGUOUS -> BadgeContent.Ambiguous
        else -> null
    }
    return content?.let { TitleBadge(this, it) }
}
