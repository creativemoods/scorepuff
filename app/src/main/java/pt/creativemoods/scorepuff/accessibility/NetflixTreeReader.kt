// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import pt.creativemoods.scorepuff.data.models.ScreenRect

/** Bounded traversal of a single Netflix window. Caller owns/releases the root. */
class NetflixTreeReader {
    data class Snapshot(val root: NetflixNode, val playback: Boolean, val truncated: Boolean)

    fun read(root: AccessibilityNodeInfo): Snapshot {
        var visited = 0
        var truncated = false
        var playback = false
        fun copy(node: AccessibilityNodeInfo, depth: Int, insideCard: Boolean = false,
            insideContinueWatching: Boolean = false, insideDetails: Boolean = false, insideHorizontal: Boolean = false, insideGrid: Boolean = false, insideSavedRow: Boolean = false): NetflixNode {
            visited++
            val id = node.viewIdResourceName
            val isHorizontal = id?.startsWith("PinotUIEntity:PinotHorizontalArtworkWithPlaybackEntityTreatment:") == true
            val isCard = isHorizontal || id?.startsWith("PinotUIEntity:PinotStandardBoxshotEntityTreatment:") == true
            val className = node.className?.toString()
            val isSavedRow = insideGrid && className == "android.view.ViewGroup" && isSavedListRow(node)
            val pageHeading = className == "android.widget.TextView" && node.text?.toString() == "My List"
            val detailContext = insideDetails || className == "androidx.recyclerview.widget.RecyclerView"
            val rawText = if ((detailContext || insideHorizontal || insideSavedRow || pageHeading) && className == "android.widget.TextView") node.text?.toString()?.take(512) else null
            val headingLabel = rawText?.takeIf { it.isNotBlank() && node.contentDescription?.toString() == it }
            val metadataText = rawText?.takeIf { it.matches(Regex("(?:19|20)[0-9]{2}|[0-9]+h(?: [0-9]+m)?|[0-9]+m|[0-9]+ (?:Seasons?|Parts?)|Limited Series|Miniseries")) }
            val description = when {
                insideSavedRow && className == "android.widget.ImageButton" && node.contentDescription?.toString() == "Play" -> "Play"
                className == "android.view.View" && id.isNullOrEmpty() &&
                    node.contentDescription?.toString() in NetflixBrowseViewport.navigationLabels -> node.contentDescription.toString()
                headingLabel != null -> headingLabel
                detailContext && className == "android.widget.FrameLayout" && node.contentDescription?.toString() == "Show controls" -> "Show controls"
                insideCard && id == "BackgroundImage" -> node.contentDescription?.toString()?.take(512)
                className == "android.widget.Button" && id?.startsWith("com.netflix.mediaclient:id/") == true ->
                    node.contentDescription?.toString()?.takeIf { it.startsWith("Play ") || it.startsWith("Resume ") }?.take(512)
                insideContinueWatching && className == "android.widget.ImageView" ->
                    node.contentDescription?.toString()?.takeIf { it.startsWith("Show details for ") }?.take(512)
                else -> null
            }
            val isContinueWatching = className == "android.widget.Button" && description?.startsWith("Play ") == true
            val localId = id.orEmpty().substringAfterLast('/').lowercase()
            // Heuristics only; no Netflix playback API is available.
            if (listOf("player_view", "player_surface", "surface_view", "playback_view",
                    "player_controls", "player_fragment", "netflixplayer").any { localId.contains(it) }) {
                playback = true
            }
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val children = mutableListOf<NetflixNode>()
            if (depth >= 60 || visited >= 1800) {
                truncated = true
            } else {
                for (index in 0 until node.childCount) {
                    if (visited >= 1800) { truncated = true; break }
                    val child = node.getChild(index) ?: continue
                    try { children += copy(child, depth + 1, insideCard || isCard,
                        insideContinueWatching || isContinueWatching, detailContext, insideHorizontal || isHorizontal,
                        insideGrid || className == "android.widget.GridView", insideSavedRow || isSavedRow) } finally { release(child) }
                }
            }
            return NetflixNode(
                resourceId = id,
                // Copy only poster titles and the paired Continue Watching title labels.
                description = description,
                bounds = ScreenRect(rect.left, rect.top, rect.right, rect.bottom),
                visible = node.isVisibleToUser,
                children = children,
                className = className,
                text = if (insideHorizontal || insideSavedRow || pageHeading) rawText else headingLabel ?: metadataText
            )
        }
        val snapshot = copy(root, 0)
        return Snapshot(snapshot, playback, truncated)
    }

    private fun isSavedListRow(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount != 3) return false
        val held = mutableListOf<AccessibilityNodeInfo>()
        try {
            val image = node.getChild(0)?.also { held += it } ?: return false
            val label = node.getChild(1)?.also { held += it } ?: return false
            val controls = node.getChild(2)?.also { held += it } ?: return false
            if (controls.childCount != 1) return false
            val play = controls.getChild(0)?.also { held += it } ?: return false
            return image.className?.toString() == "android.widget.ImageView" &&
                label.className?.toString() == "android.widget.TextView" &&
                controls.className?.toString() == "android.widget.FrameLayout" &&
                play.className?.toString() == "android.widget.ImageButton" && play.contentDescription?.toString() == "Play"
        } finally { held.forEach(::release) }
    }

    companion object {
        @Suppress("DEPRECATION")
        fun release(node: AccessibilityNodeInfo) {
            // Node pooling was removed in API 33.
            if (Build.VERSION.SDK_INT < 33) node.recycle()
        }
    }
}
