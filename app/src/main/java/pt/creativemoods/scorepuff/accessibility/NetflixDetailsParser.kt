// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import pt.creativemoods.scorepuff.data.models.*

/** Recognize a details header, its own metadata row and matching Play/Resume control. */
object NetflixDetailsParser {
    private const val RECYCLER = "androidx.recyclerview.widget.RecyclerView"
    private const val TEXT = "android.widget.TextView"
    private val yearPattern = Regex("(?:19|20)[0-9]{2}")
    private val durationPattern = Regex("[0-9]+h(?: [0-9]+m)?|[0-9]+m")
    private val seasonsPattern = Regex("[0-9]+ (?:Seasons?|Parts?)|Limited Series|Miniseries")

    fun parse(root: NetflixNode, viewport: ScreenRect): NetflixTitle? {
        val matches = mutableListOf<NetflixTitle>()
        for (recycler in flatten(root).filter { it.className == RECYCLER }) {
            val nodes = flatten(recycler, skipNestedRecyclers = true).filter { it.visible }
            for (row in nodes) {
                val years = row.children.filter { it.visible && it.className == TEXT && yearPattern.matches(it.text.orEmpty()) }
                val year = years.singleOrNull() ?: continue
                if (year.bounds.intersect(viewport) == null) continue
                val texts = row.children.filter { it.visible && it.className == TEXT }.mapNotNull { it.text }
                val type = when {
                    texts.any { seasonsPattern.matches(it) } -> ContentType.SHOW
                    texts.any { durationPattern.matches(it) } -> ContentType.MOVIE
                    else -> null
                }
                val headings = nodes.filter { heading ->
                    heading.className == TEXT && !heading.text.isNullOrBlank() && heading.text == heading.description &&
                        heading.bounds.intersect(viewport) != null && !heading.bounds.isEmpty &&
                        year.bounds.top - heading.bounds.bottom in 0..(year.bounds.height * 2) &&
                        nodes.any { button -> button.className == "android.widget.Button" &&
                            (button.description in listOf("Play ${heading.text}", "Resume ${heading.text}") ||
                                (type == ContentType.SHOW && Regex("Play Chapter [0-9]+").matches(button.description.orEmpty()))) &&
                            button.bounds.top >= year.bounds.bottom &&
                            button.bounds.top - year.bounds.bottom <= year.bounds.height * 8 }
                }
                val heading = headings.singleOrNull() ?: continue
                val besideTitle = ScreenRect(heading.bounds.right, heading.bounds.top, viewport.right, heading.bounds.bottom)
                val preview = nodes.filter { it.className == "android.widget.FrameLayout" && it.description == "Show controls" &&
                    it.bounds.bottom <= heading.bounds.top && !it.bounds.isEmpty }
                    .maxByOrNull { it.bounds.width.toLong() * it.bounds.height }?.bounds
                val areas = listOfNotNull(besideTitle.takeUnless { it.isEmpty }, preview)
                matches += NetflixTitle(null, heading.text!!, type, heading.bounds, year.text!!.toInt(),
                    TitlePresentation.DETAILS, areas, layout = TitleLayout.DETAILS,
                    runtimeMinutes = if (type == ContentType.MOVIE) texts.mapNotNull(::runtimeMinutes).singleOrNull() else null)
            }
        }
        return matches.distinctBy { it.key }.singleOrNull()
    }

    fun runtimeMinutes(text: String): Int? {
        val hours = Regex("([0-9]+)h(?: ([0-9]+)m)?").matchEntire(text)
        val total = if (hours != null) {
            val h = hours.groupValues[1].toLongOrNull() ?: return null
            val m = hours.groupValues[2].ifEmpty { "0" }.toLongOrNull() ?: return null
            if (h > Int.MAX_VALUE / 60 || m !in 0..59) return null
            h * 60 + m
        } else {
            Regex("([0-9]+)m").matchEntire(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        }
        return total.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
    }

    private fun flatten(root: NetflixNode, skipNestedRecyclers: Boolean = false): List<NetflixNode> {
        val result = mutableListOf<NetflixNode>()
        val pending = ArrayDeque<NetflixNode>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (!node.visible) continue
            if (node !== root && skipNestedRecyclers && node.className == RECYCLER) continue
            result += node
            pending.addAll(node.children)
        }
        return result
    }
}
