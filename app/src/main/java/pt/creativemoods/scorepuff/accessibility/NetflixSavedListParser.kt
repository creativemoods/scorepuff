// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import pt.creativemoods.scorepuff.data.models.*

/** Captured native My List rows expose a title/image/Play relationship, but no catalog ID. */
object NetflixSavedListParser {
    fun parse(root: NetflixNode, viewport: ScreenRect): List<NetflixTitle> {
        val nodes = mutableListOf<NetflixNode>()
        val pending = ArrayDeque<NetflixNode>(); pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.visible) continue
            nodes += node; pending.addAll(node.children)
        }
        val heading = nodes.filter { it.className == "android.widget.TextView" && it.text == "My List" &&
            it.resourceId?.startsWith("com.netflix.mediaclient:id/") == true &&
            it.bounds.intersect(viewport) != null }.singleOrNull() ?: return emptyList()
        return nodes.filter { it.className == "android.widget.GridView" && it.bounds.top >= heading.bounds.bottom }
            .flatMap { grid -> grid.children.mapNotNull { row ->
                if (!row.visible || row.className != "android.view.ViewGroup" || row.children.size != 3) return@mapNotNull null
                val (image, title, controls) = row.children
                val play = controls.children.singleOrNull() ?: return@mapNotNull null
                if (!listOf(image, title, controls, play).all { it.visible } || image.className != "android.widget.ImageView" ||
                    title.className != "android.widget.TextView" || title.text.isNullOrBlank() ||
                    controls.className != "android.widget.FrameLayout" || play.className != "android.widget.ImageButton" ||
                    play.description != "Play" || image.bounds.isEmpty || !row.bounds.contains(image.bounds) ||
                    !row.bounds.contains(title.bounds) || !row.bounds.contains(controls.bounds) || !controls.bounds.contains(play.bounds) ||
                    image.bounds.right > title.bounds.left || title.bounds.right > controls.bounds.left ||
                    image.bounds.top != title.bounds.top || image.bounds.bottom != title.bounds.bottom ||
                    image.bounds.intersect(viewport) == null) return@mapNotNull null
                NetflixTitle(null, title.text.trim(), null, image.bounds, layout = TitleLayout.SAVED_LIST)
            } }
    }
}
