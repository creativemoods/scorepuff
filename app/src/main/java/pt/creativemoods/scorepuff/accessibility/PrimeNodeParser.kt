// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import pt.creativemoods.scorepuff.data.models.*

/** Captured Prime Android English layouts. No guessed ASINs or Netflix associations. */
object PrimeNodeParser {
    val navigationLabels = setOf("Home", "Prime", "My Stuff", "Downloads", "Search")
    private fun flatten(root: NetflixNode): List<NetflixNode> {
        val result = mutableListOf<NetflixNode>(); val pending = ArrayDeque<NetflixNode>(); pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeLast(); if (!node.visible) continue
            result += node; pending.addAll(node.children)
        }
        return result
    }

    private fun navigationTop(nodes: List<NetflixNode>, viewport: ScreenRect): Int? = nodes.filter {
        it.className == "android.view.View" && it.description in navigationLabels &&
            !it.bounds.isEmpty && viewport.contains(it.bounds) && it.bounds.top >= viewport.top + viewport.height * 2 / 3
    }.groupBy { it.bounds.top }.values.filter { row ->
        row.map { it.description }.distinct().size >= 3 &&
            row.maxOf { it.bounds.right } - row.minOf { it.bounds.left } >= viewport.width / 2 &&
            row.sortedBy { it.bounds.left }.zipWithNext().all { (a, b) -> a.bounds.right <= b.bounds.left }
    }.minOfOrNull { row -> row.first().bounds.top }

    fun browseViewport(root: NetflixNode, viewport: ScreenRect): ScreenRect {
        val nodes = flatten(root)
        val bottom = navigationTop(nodes, viewport) ?: viewport.bottom
        val tabs = nodes.filter { it.className == "android.widget.TextView" && it.text in setOf("Movies", "TV shows") &&
            it.bounds.top in viewport.top..(viewport.top + viewport.height / 4) }
        val top = if (tabs.map { it.text }.distinct().size == 2) nodes.filter { node ->
            tabs.all { node.bounds.contains(it.bounds) } && node.bounds.height in 1..(viewport.height / 4)
        }.minByOrNull { it.bounds.width.toLong() * it.bounds.height }?.bounds?.bottom ?: viewport.top else viewport.top
        return ScreenRect(viewport.left, maxOf(viewport.top, top), viewport.right, minOf(viewport.bottom, bottom))
    }

    fun parse(root: NetflixNode?, viewport: ScreenRect): List<NetflixTitle> {
        if (root == null || viewport.isEmpty) return emptyList()
        val nodes = flatten(root)
        val result = mutableListOf<NetflixTitle>()
        // A details page is never a browse grid: its download/entitlement buttons can
        // have the same clickable shape as artwork. Fail closed if metadata is missing.
        if (nodes.any { row -> row.children.size == 2 && titleLabel(row.children[0]) != null && row.children[1].className == "android.widget.ImageView" &&
                row.children[1].description == "Prime Video" }) return listOfNotNull(details(nodes, viewport))
        // Profile/account pages must not turn arbitrary clickable labels into title lookups.
        if (navigationTop(nodes, viewport.copy(bottom = maxOf(viewport.bottom, nodes.maxOfOrNull { it.bounds.bottom } ?: viewport.bottom))) != null) {
            for (node in nodes) {
                if (!node.clickable || node.scrollable || node.className != "android.view.View" ||
                    node.bounds.intersect(viewport) == null || node.bounds.width < viewport.width / 7 ||
                    node.bounds.height < viewport.height / 30 || node.bounds.width < node.bounds.height / 2 ||
                    node.bounds.width > node.bounds.height * 3) continue
                val label = node.children.filter { it.visible && !it.clickable && !it.scrollable &&
                    it.className == "android.view.View" && !it.description.isNullOrBlank() && it.bounds == node.bounds }.singleOrNull() ?: continue
                if (label.description in navigationLabels && node.bounds.top >= viewport.bottom) continue
                val season = Regex("^(.*?)(?: - S[0-9]+| - Season [0-9]+| Season [0-9]+ \\(4K UHD\\))$").matchEntire(label.description!!)
                val title = season?.groupValues?.get(1)?.trim() ?: label.description.trim()
                if (title.isEmpty()) continue
                result += NetflixTitle(null, title, if (season != null) ContentType.SHOW else null, node.bounds,
                    layout = TitleLayout.PRIME_ARTWORK, provider = StreamingProvider.PRIME_VIDEO)
            }
        }
        return result.distinctBy { it.key }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun titleLabel(node: NetflixNode): String? = when (node.className) {
        "android.widget.TextView" -> node.text
        "android.widget.ImageView" -> node.description
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() && it != "Prime Video" }

    private fun details(nodes: List<NetflixNode>, viewport: ScreenRect): NetflixTitle? {
        val branded = nodes.mapNotNull { row ->
            if (row.children.size != 2 || !row.children.all { it.visible }) return@mapNotNull null
            val (title, logo) = row.children
            if (titleLabel(title) == null || logo.className != "android.widget.ImageView" || logo.description != "Prime Video" ||
                title.bounds.isEmpty || !viewport.contains(title.bounds)) return@mapNotNull null
            title to logo
        }.singleOrNull() ?: return null
        fun metadata(label: String, valid: (String) -> Boolean): NetflixNode? = nodes.mapNotNull { cell ->
            val texts = cell.children.filter { it.visible && it.className == "android.widget.TextView" }
            texts.takeIf { it.size == 2 && it[0].text == label && valid(it[1].text.orEmpty()) && viewport.contains(it[1].bounds) }?.get(1)
        }.singleOrNull()
        val yearPattern = Regex("(?:19|20)[0-9]{2}")
        val seriesYear = metadata("Releases") { yearPattern.matches(it) }
        val movieYear = metadata("Release") { yearPattern.matches(it) }
        val seasons = metadata("Seasons") { it.toIntOrNull()?.let { count -> count > 0 } == true }
        val duration = metadata("Duration") { runtime(it) != null }
        val series = seriesYear != null && seasons != null && movieYear == null && duration == null
        val movie = movieYear != null && duration != null && seriesYear == null && seasons == null
        if (!series && !movie) return null
        val release = (if (series) seriesYear else movieYear)!!
        val play = nodes.filter { it.clickable && it.bounds.top >= branded.first.bounds.bottom && it.bounds.bottom <= release.bounds.top &&
            it.children.any { child -> child.visible && child.className == "android.widget.TextView" &&
                (if (series) Regex("Resume S[0-9]+ E[0-9]+").matches(child.text.orEmpty()) else child.text == "Resume") } &&
            it.children.any { child -> child.visible && child.className == "android.widget.Button" && child.bounds == it.bounds } }.singleOrNull()
        if (play == null) return null
        val title = branded.first
        val area = ScreenRect(title.bounds.right, title.bounds.top, branded.second.bounds.left, title.bounds.bottom)
        return NetflixTitle(null, titleLabel(title)!!, if (series) ContentType.SHOW else ContentType.MOVIE, title.bounds, release.text!!.toInt(),
            TitlePresentation.DETAILS, listOf(area), layout = TitleLayout.DETAILS,
            runtimeMinutes = if (movie) runtime(duration!!.text!!) else null, provider = StreamingProvider.PRIME_VIDEO)
    }

    private fun runtime(text: String): Int? {
        val match = Regex("(?:(\\d+)h(?: (\\d+)m(?:in)?)?|(\\d+)m(?:in)?)").matchEntire(text) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: match.groupValues[3].toIntOrNull() ?: 0
        if (hours > 24 || (hours > 0 && minutes >= 60)) return null
        return (hours * 60 + minutes).takeIf { it in 1..1440 }
    }
}
