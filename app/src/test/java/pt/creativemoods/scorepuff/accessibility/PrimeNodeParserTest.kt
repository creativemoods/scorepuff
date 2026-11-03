// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.overlay.BadgeLayout
import pt.creativemoods.scorepuff.ratings.lookup

class PrimeNodeParserTest {
    private val screen = ScreenRect(0, 0, 1080, 2340)
    @Test fun capturedHomeUsesArtworkBoundsAndExcludesHeroNavigationAndSlivers() {
        val root = fixture("captured-prime-home.xml")
        val viewport = PrimeNodeParser.browseViewport(root, screen)
        assertEquals(2028, viewport.bottom)
        val titles = PrimeNodeParser.parse(root, viewport)
        assertEquals(listOf("Sample Series", "Sample Movie", "Sample Show", "Sample Series: Companion", "Sample Drama"), titles.map { it.title })
        assertTrue(titles.all { it.provider == StreamingProvider.PRIME_VIDEO && it.netflixId == null })
        assertEquals(ContentType.SHOW, titles.first().type)
        assertEquals(ScreenRect(60, 1308, 530, 1573), titles.first().bounds)
        val badges = BadgeLayout.place(titles, viewport, 3f)
        assertEquals(5, badges.size)
        badges.forEach { assertTrue(viewport.contains(it.bounds)) }
    }
    @Test fun capturedSeriesDetailsUseOwnReleaseYearAndKeepBadgeAwayFromLogo() {
        val root = fixture("captured-prime-details-luther.xml")
        val viewport = PrimeNodeParser.browseViewport(root, screen)
        val title = PrimeNodeParser.parse(root, viewport).single()
        assertEquals("Luther", title.title)
        assertEquals(2010, title.releaseYear)
        assertEquals(ContentType.SHOW, title.type)
        assertNull(title.runtimeMinutes)
        assertEquals(TitlePresentation.DETAILS, title.presentation)
        val badge = BadgeLayout.place(listOf(title), viewport, 3f).single()
        assertTrue(title.badgeAreas.single().contains(badge.bounds))
    }
    @Test fun capturedMovieDetailsReadImageLabelYearAndFullDuration() {
        val root = fixture("captured-prime-details-casino-royale.xml")
        val viewport = PrimeNodeParser.browseViewport(root, screen)
        val title = PrimeNodeParser.parse(root, viewport).single()
        assertEquals("Casino Royale", title.title)
        assertEquals(2006, title.releaseYear)
        assertEquals(144, title.runtimeMinutes)
        assertEquals(ContentType.MOVIE, title.type)
        assertEquals(TitlePresentation.DETAILS, title.presentation)
        assertEquals(1, BadgeLayout.place(listOf(title), viewport, 3f).size)
    }
    @Test fun movieNeedsBothOwnReleaseAndDurationMetadata() {
        val root = fixture("captured-prime-details-casino-royale.xml")
        fun withoutRuntime(node: NetflixNode): NetflixNode = node.copy(text = node.text?.takeUnless { it == "2h 24min" }, children = node.children.map(::withoutRuntime))
        assertTrue(PrimeNodeParser.parse(withoutRuntime(root), screen).isEmpty())
    }
    @Test fun invalidMovieDurationAndWrongEpisodeControlAreRejected() {
        val root = fixture("captured-prime-details-casino-royale.xml")
        fun change(node: NetflixNode, from: String, to: String): NetflixNode = node.copy(text = if (node.text == from) to else node.text,
            children = node.children.map { change(it, from, to) })
        assertTrue(PrimeNodeParser.parse(change(root, "2h 24min", "2h 99min"), screen).isEmpty())
        assertTrue(PrimeNodeParser.parse(change(root, "Resume", "Resume S1 E1"), screen).isEmpty())
    }
    @Test fun arbitraryClickableLabelsWithoutBrowseNavigationAreIgnored() {
        val bounds = ScreenRect(50, 500, 500, 800)
        val root = NetflixNode(children = listOf(NetflixNode(bounds = bounds, className = "android.view.View", clickable = true,
            children = listOf(NetflixNode(description = "Account", bounds = bounds, className = "android.view.View")))))
        assertTrue(PrimeNodeParser.parse(root, screen).isEmpty())
    }
    @Test fun invisibleAncestorsAndMissingReleaseMetadataCannotResolveDetails() {
        val root = fixture("captured-prime-details-luther.xml")
        assertTrue(PrimeNodeParser.parse(root.copy(visible = false), screen).isEmpty())
        fun withoutYear(node: NetflixNode): NetflixNode = node.copy(text = node.text?.takeUnless { it == "2010" }, children = node.children.map(::withoutYear))
        assertTrue(PrimeNodeParser.parse(withoutYear(root), screen).isEmpty())
    }
    @Test fun providerNamespacesKeepManualMappingsAndCardKeysSeparate() {
        val netflix = NetflixTitle(null, "Luther", ContentType.SHOW, screen, 2010)
        val prime = netflix.copy(provider = StreamingProvider.PRIME_VIDEO)
        assertNotEquals(netflix.key, prime.key)
        assertEquals("title:SHOW:luther:year:2010", netflix.lookup().key)
        assertEquals("prime:title:SHOW:luther:year:2010", prime.lookup().key)
    }
    private fun fixture(name: String): NetflixNode {
        val doc = javaClass.getResourceAsStream("/fixtures/$name")!!.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        fun convert(e: Element): NetflixNode {
            val b = Regex("-?\\d+").findAll(e.getAttribute("bounds")).map { it.value.toInt() }.toList()
            val children = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }
            return NetflixNode(e.getAttribute("resource-id").ifEmpty { null }, e.getAttribute("content-desc").ifEmpty { null },
                if (b.size == 4) ScreenRect(b[0], b[1], b[2], b[3]) else screen,
                visible = e.getAttribute("visible-to-user") != "false", children = children.map(::convert),
                className = e.getAttribute("class").ifEmpty { null }, text = e.getAttribute("text").ifEmpty { null },
                clickable = e.getAttribute("clickable") == "true", scrollable = e.getAttribute("scrollable") == "true")
        }
        return convert(doc.documentElement)
    }
}
