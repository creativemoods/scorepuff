// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import pt.creativemoods.scorepuff.data.models.*

/** Only immutable Netflix metadata reaches the parser. No Android dependency. */
data class NetflixNode(
    val resourceId: String? = null,
    val description: String? = null,
    val bounds: ScreenRect = ScreenRect(0, 0, 0, 0),
    val visible: Boolean = true,
    val children: List<NetflixNode> = emptyList(),
    val className: String? = null,
    val text: String? = null,
    val clickable: Boolean = false,
    val scrollable: Boolean = false
)

class NetflixNodeParser {
    private val cardId = Regex(
        "^PinotUIEntity:(?:PinotStandardBoxshotEntityTreatment|PinotHorizontalArtworkWithPlaybackEntityTreatment):(Movie|Show):Video:([0-9]+)$"
    )

    fun parse(root: NetflixNode?, viewport: ScreenRect): List<NetflixTitle> {
        if (root == null || viewport.isEmpty) return emptyList()
        val result = linkedMapOf<CardKey, NetflixTitle>()
        NetflixDetailsParser.parse(root, viewport)?.let { result[it.key] = it }
        NetflixSavedListParser.parse(root, viewport).forEach { result[it.key] = it }
        val pending = ArrayDeque<NetflixNode>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.visible) continue
            pending.addAll(node.children)
            parseContinueWatching(node, viewport)?.let { result.putIfAbsent(it.key, it) }
            val match = cardId.matchEntire(node.resourceId.orEmpty()) ?: continue
            if (!node.visible || node.bounds.intersect(viewport) == null) continue
            val horizontal = node.resourceId.orEmpty().startsWith("PinotUIEntity:PinotHorizontalArtworkWithPlaybackEntityTreatment:")
            val artwork = if (horizontal) horizontalArtwork(node) ?: continue else null
            val title = artwork?.first ?: findTitle(node) ?: continue
            val parsed = NetflixTitle(
                netflixId = match.groupValues[2],
                title = title,
                type = if (match.groupValues[1] == "Movie") ContentType.MOVIE else ContentType.SHOW,
                bounds = artwork?.second ?: node.bounds,
                layout = if (horizontal) TitleLayout.HORIZONTAL_ARTWORK else TitleLayout.STANDARD_POSTER
            )
            result.putIfAbsent(parsed.key, parsed)
        }
        // Tree ordering can change independently of screen content.
        return result.values.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }, { it.netflixId }))
    }

    private fun parseContinueWatching(node: NetflixNode, viewport: ScreenRect): NetflixTitle? {
        if (!node.visible || node.className != "android.widget.Button" ||
            node.resourceId?.startsWith("com.netflix.mediaclient:id/") != true ||
            node.description?.startsWith("Play ") != true) return null
        val descendants = ArrayDeque<NetflixNode>()
        descendants.addAll(node.children)
        val details = mutableListOf<NetflixNode>()
        val artwork = mutableListOf<NetflixNode>()
        while (descendants.isNotEmpty()) {
            val child = descendants.removeLast()
            if (!child.visible) continue
            if (child.className == "android.widget.Button" || cardId.matches(child.resourceId.orEmpty())) continue
            descendants.addAll(child.children)
            if (!child.visible || !node.bounds.contains(child.bounds)) continue
            if (child.className == "android.widget.ImageView") {
                if (child.description?.startsWith("Show details for ") == true) details += child
                else if (child.description.isNullOrBlank() && child.bounds.left == node.bounds.left &&
                    child.bounds.top == node.bounds.top && child.bounds.right == node.bounds.right) artwork += child
            }
        }
        val detail = details.singleOrNull() ?: return null
        // The details label preserves punctuation, commas and colons in the title.
        val title = detail.description!!.removePrefix("Show details for ").trim().takeIf { it.isNotEmpty() } ?: return null
        val suffix = node.description.removePrefix("Play ").removePrefix(title)
        if (node.description != "Play $title" &&
            !(node.description.startsWith("Play $title,") && suffix.startsWith(", "))) return null
        // Use the image rectangle, not the whole play button including progress/details controls.
        val poster = artwork.filter { it.bounds.bottom <= detail.bounds.top && !it.bounds.isEmpty }
            .maxByOrNull { it.bounds.height }?.bounds ?: return null
        if (poster.intersect(viewport) == null) return null
        return NetflixTitle(null, title, null, poster, layout = TitleLayout.CONTINUE_WATCHING)
    }

    private fun horizontalArtwork(card: NetflixNode): Pair<String, ScreenRect>? {
        val heading = card.children.filter { it.visible && it.className == "android.widget.TextView" &&
            !it.text.isNullOrBlank() && !it.bounds.isEmpty && card.bounds.contains(it.bounds) }.singleOrNull() ?: return null
        val images = mutableListOf<NetflixNode>()
        val pending = ArrayDeque<NetflixNode>()
        pending.addAll(card.children)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.visible || cardId.matches(node.resourceId.orEmpty())) continue
            if (node.resourceId == "BackgroundImage" && !node.bounds.isEmpty && card.bounds.contains(node.bounds)) images += node
            pending.addAll(node.children)
        }
        val image = images.singleOrNull() ?: return null
        if (heading.bounds.left < image.bounds.right || heading.bounds.top < image.bounds.top ||
            heading.bounds.bottom > image.bounds.bottom) return null
        return heading.text!!.trim() to image.bounds
    }

    private fun findTitle(card: NetflixNode): String? {
        val pending = ArrayDeque<NetflixNode>()
        pending.addAll(card.children)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.visible) continue
            // Never borrow a label from another nested card or unrelated controls.
            if (cardId.matches(node.resourceId.orEmpty())) continue
            if (node.visible && node.resourceId == "BackgroundImage") {
                node.description?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            pending.addAll(node.children)
        }
        return null
    }
}
