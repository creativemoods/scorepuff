// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import pt.creativemoods.scorepuff.data.models.ScreenRect

/** Bounded Prime-only snapshot. Keep artwork labels, branded title headers and matching metadata. */
class PrimeTreeReader {
    fun read(root: AccessibilityNodeInfo): NetflixTreeReader.Snapshot {
        var visited = 0; var truncated = false; var playback = false
        fun copy(node: AccessibilityNodeInfo, depth: Int, parentClickable: Boolean = false, brandedTitle: Boolean = false): NetflixNode {
            visited++
            val className = node.className?.toString()
            val id = node.viewIdResourceName
            val rect = Rect(); node.getBoundsInScreen(rect)
            val rawDescription = node.contentDescription?.toString()?.take(512)
            val description = when {
                className == "android.widget.ImageView" && (rawDescription == "Prime Video" || brandedTitle) -> rawDescription
                className == "android.view.View" && rawDescription in PrimeNodeParser.navigationLabels -> rawDescription
                className == "android.view.View" && parentClickable && !node.isClickable && !node.isScrollable -> rawDescription
                else -> null
            }
            val rawText = if (className == "android.widget.TextView") node.text?.toString()?.take(512) else null
            val text = rawText?.takeIf { brandedTitle || it in setOf("Release", "Releases", "Seasons", "Duration", "Movies", "TV shows", "Resume") ||
                it.matches(Regex("[0-9]{1,4}|Resume S[0-9]+ E[0-9]+|[0-9]+h(?: [0-9]+m(?:in)?)?|[0-9]+m(?:in)?|[0-9]+ min")) }
            val localId = id.orEmpty().substringAfterLast('/').lowercase()
            if (node.isVisibleToUser && listOf("player_view", "player_surface", "playback_view", "player_controls").any { localId.contains(it) }) playback = true
            val children = mutableListOf<NetflixNode>()
            if (depth >= 60 || visited >= 1800) truncated = true else {
                val isTitle = isBrandedTitle(node)
                for (index in 0 until node.childCount) {
                    if (visited >= 1800) { truncated = true; break }
                    val child = node.getChild(index) ?: continue
                    try { children += copy(child, depth + 1, node.isClickable, isTitle) }
                    finally { NetflixTreeReader.release(child) }
                }
            }
            return NetflixNode(id, description, ScreenRect(rect.left, rect.top, rect.right, rect.bottom), node.isVisibleToUser,
                children, className, text, node.isClickable, node.isScrollable)
        }
        return NetflixTreeReader.Snapshot(copy(root, 0), playback, truncated)
    }

    private fun isBrandedTitle(node: AccessibilityNodeInfo): Boolean {
        if (node.childCount != 2) return false
        val first = node.getChild(0) ?: return false
        try {
            val second = node.getChild(1) ?: return false
            try {
                return first.className?.toString() in setOf("android.widget.TextView", "android.widget.ImageView") &&
                    second.className?.toString() == "android.widget.ImageView" && second.contentDescription?.toString() == "Prime Video"
            } finally { NetflixTreeReader.release(second) }
        } finally { NetflixTreeReader.release(first) }
    }
}
