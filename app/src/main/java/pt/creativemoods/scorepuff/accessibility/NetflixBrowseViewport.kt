// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import pt.creativemoods.scorepuff.data.models.ScreenRect

/** Recognize the captured English Netflix bottom navigation, never a lone title label. */
object NetflixBrowseViewport {
    val navigationLabels = setOf("Home", "Clips", "Search", "My Netflix")

    fun resolve(root: NetflixNode, viewport: ScreenRect): ScreenRect {
        val navigation = mutableListOf<NetflixNode>()
        val pending = ArrayDeque<NetflixNode>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.visible) continue
            pending.addAll(node.children)
            if (node.className == "android.view.View" && node.resourceId.isNullOrEmpty() &&
                node.description in navigationLabels && !node.bounds.isEmpty &&
                node.bounds.top >= viewport.top + viewport.height / 2 &&
                node.bounds.height <= viewport.height / 4 && viewport.contains(node.bounds)) navigation += node
        }
        val top = navigation.groupBy { it.bounds.top to it.bounds.bottom }.values.filter { row ->
            row.map { it.description }.distinct().size >= 3 &&
                row.maxOf { it.bounds.right } - row.minOf { it.bounds.left } >= viewport.width / 2 &&
                row.sortedBy { it.bounds.left }.zipWithNext().all { (a, b) -> a.bounds.right <= b.bounds.left }
        }.minOfOrNull { row -> row.first().bounds.top }
        return if (top == null) viewport else viewport.copy(bottom = top)
    }
}
