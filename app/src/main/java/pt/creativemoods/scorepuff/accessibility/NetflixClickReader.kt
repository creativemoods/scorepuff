// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.ratings.normalizeTitle

object NetflixClickReader {
    var lastSourceIds: List<String> = emptyList()
        private set
    private val cardId = Regex("^PinotUIEntity:(?:PinotStandardBoxshotEntityTreatment|PinotHorizontalArtworkWithPlaybackEntityTreatment):(Movie|Show):Video:([0-9]+)$")

    /** Takes ownership of source and releases it and all obtained parents before returning. */
    fun origin(source: AccessibilityNodeInfo, visiblePosters: List<NetflixTitle>): NetflixTitle? {
        val held = mutableListOf<AccessibilityNodeInfo>()
        val ids = mutableListOf<String>()
        lastSourceIds = emptyList()
        try {
            held += source
            if (source.packageName?.toString() != ScorePuffAccessibilityService.NETFLIX_PACKAGE) return null
            val rect = Rect()
            source.getBoundsInScreen(rect)
            val bounds = ScreenRect(rect.left, rect.top, rect.right, rect.bottom)
            val imageTitle = if (source.viewIdResourceName == "BackgroundImage") source.contentDescription?.toString() else null
            var node = source
            repeat(10) {
                ids += node.viewIdResourceName.orEmpty()
                val id = cardId.matchEntire(node.viewIdResourceName.orEmpty())?.groupValues?.get(2)
                if (id != null) return visiblePosters.firstOrNull { it.netflixId == id }
                val parent = node.parent ?: return@repeat
                held += parent
                if (parent.packageName?.toString() != ScorePuffAccessibilityService.NETFLIX_PACKAGE) return null
                node = parent
            }
            // Some virtual children omit their parent ID. Require both the known card rectangle and image label.
            return visiblePosters.filter { it.netflixId != null && !bounds.isEmpty && it.bounds.contains(bounds) &&
                imageTitle != null && normalizeTitle(imageTitle) == normalizeTitle(it.title) }
                .singleOrNull()
        } finally { lastSourceIds = ids.distinct(); held.forEach { NetflixTreeReader.release(it) } }
    }
}
