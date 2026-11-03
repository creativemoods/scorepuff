// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.data.models

// Values, not Android Rect or AccessibilityNodeInfo: safe to compare and retain.
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val isEmpty get() = width <= 0 || height <= 0
    fun intersect(other: ScreenRect): ScreenRect? = ScreenRect(
        maxOf(left, other.left), maxOf(top, other.top),
        minOf(right, other.right), minOf(bottom, other.bottom)
    ).takeUnless { it.isEmpty }
    fun contains(other: ScreenRect) = !other.isEmpty &&
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
}

enum class StreamingProvider { NETFLIX, PRIME_VIDEO }

enum class ContentType { MOVIE, SHOW }
enum class TitlePresentation { POSTER, DETAILS }
enum class TitleLayout { STANDARD_POSTER, CONTINUE_WATCHING, HORIZONTAL_ARTWORK, SAVED_LIST, DETAILS, PRIME_ARTWORK }

data class NetflixTitle(
    val netflixId: String?,
    val title: String,
    val type: ContentType?,
    val bounds: ScreenRect,
    val releaseYear: Int? = null,
    val presentation: TitlePresentation = TitlePresentation.POSTER,
    val badgeAreas: List<ScreenRect> = emptyList(),
    val associationEvidence: String? = null,
    val layout: TitleLayout = TitleLayout.STANDARD_POSTER,
    val runtimeMinutes: Int? = null,
    val provider: StreamingProvider = StreamingProvider.NETFLIX
) {
    val key get() = CardKey(netflixId, bounds, if (netflixId == null) title else null, releaseYear, provider)
}

// Continue Watching exposes labels without a video ID. Never fabricate one.
data class CardKey(val netflixId: String?, val bounds: ScreenRect, val fallbackTitle: String? = null, val releaseYear: Int? = null, val provider: StreamingProvider = StreamingProvider.NETFLIX)
