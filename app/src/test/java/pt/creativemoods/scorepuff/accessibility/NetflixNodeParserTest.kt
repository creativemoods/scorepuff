// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.overlay.BadgeLayout

class NetflixNodeParserTest {
    private val parser = NetflixNodeParser()
    private val screen = ScreenRect(0, 0, 1080, 2340)
    private fun card(id: String = "123", type: String = "Movie", title: String? = "A movie",
        bounds: ScreenRect = ScreenRect(36, 100, 338, 523)) = NetflixNode(
        "PinotUIEntity:PinotStandardBoxshotEntityTreatment:$type:Video:$id", bounds = bounds,
        children = listOf(NetflixNode("BackgroundImage", title, bounds)))

    @Test fun verifiedXmlExtractsMovieShowIdsTitlesAndScreenCoordinates() {
        val titles = parser.parse(fixture(), screen)
        assertEquals(2, titles.size)
        assertEquals(NetflixTitle("82078375", "Collision Course", ContentType.MOVIE,
            ScreenRect(356, 1120, 658, 1543)), titles[0])
        assertEquals(NetflixTitle("80994082", "Lupin", ContentType.SHOW,
            ScreenRect(36, 1684, 338, 2107)), titles[1])
    }

    @Test fun partiallyVisibleCardKeepsOriginalScreenBounds() {
        val bounds = ScreenRect(-100, 100, 202, 523)
        assertEquals(bounds, parser.parse(card(bounds = bounds), screen).single().bounds)
    }
    @Test fun fullyOffscreenCardsAreExcluded() {
        assertTrue(parser.parse(card(bounds = ScreenRect(1100, 0, 1400, 400)), screen).isEmpty())
    }
    @Test fun missingAndBlankDescriptionsAreExcluded() {
        assertTrue(parser.parse(card(title = null), screen).isEmpty())
        assertTrue(parser.parse(card(title = "  "), screen).isEmpty())
    }
    @Test fun trimsLabelWithoutChangingLocalizedTitle() {
        assertEquals("Lupin – Épisode", parser.parse(card(title = "  Lupin – Épisode  "), screen).single().title)
    }
    @Test fun malformedIdentifiersAreExcluded() {
        for (id in listOf("", "abc", "12:extra", "-1")) assertTrue(parser.parse(card(id = id), screen).isEmpty())
        assertTrue(parser.parse(card(type = "Trailer"), screen).isEmpty())
        assertTrue(parser.parse(card().copy(resourceId = "other:Movie:Video:123"), screen).isEmpty())
    }
    @Test fun duplicatesAtSamePositionAreDeduplicated() {
        assertEquals(1, parser.parse(NetflixNode(children = listOf(card(), card())), screen).size)
    }
    @Test fun sameTitleInDifferentCarouselsKeepsBothPositions() {
        assertEquals(2, parser.parse(NetflixNode(children = listOf(card(),
            card(bounds = ScreenRect(36, 800, 338, 1223)))), screen).size)
    }
    @Test fun emptyAndNullTreesProduceNoTitles() {
        assertTrue(parser.parse(null, screen).isEmpty())
        assertTrue(parser.parse(NetflixNode(), screen).isEmpty())
    }
    @Test fun unsupportedContinueWatchingLayoutIsIgnored() {
        assertTrue(parser.parse(NetflixNode("ContinueWatching", "Play Apex, 1 hour 12 minutes remaining",
            screen, children = listOf(NetflixNode("details", "Show details for Apex"))), screen).isEmpty())
    }
    @Test fun invisibleCardsAndEmptyBoundsAreExcluded() {
        assertTrue(parser.parse(card().copy(visible = false), screen).isEmpty())
        assertTrue(parser.parse(card(bounds = ScreenRect(10, 10, 10, 20)), screen).isEmpty())
    }
    @Test fun unrelatedDescriptionsAreNeverUsedAsTitles() {
        assertTrue(parser.parse(card().copy(children = listOf(NetflixNode("play", "Play A movie"))), screen).isEmpty())
    }
    @Test fun nestedCardCannotSupplyParentTitle() {
        assertEquals(listOf("456"), parser.parse(card(title = null).copy(children = listOf(card(id = "456"))), screen)
            .map { it.netflixId })
    }
    @Test fun badgesStayInsideScreenInsetsAndOriginalPoster() {
        val titles = parser.parse(fixture(), screen)
        val safe = ScreenRect(0, 80, 1080, 2260)
        val badges = BadgeLayout.place(titles, safe, 2.75f)
        assertEquals(2, badges.size)
        badges.forEach { badge ->
            assertTrue(safe.contains(badge.bounds))
            assertTrue(titles.first { it.key == badge.key }.bounds.contains(badge.bounds))
        }
    }
    @Test fun clippedOrNarrowCardsDoNotMoveBadgesToWrongPoster() {
        val bounds = listOf(ScreenRect(-250, 100, 52, 523), ScreenRect(20, -200, 322, 223),
            ScreenRect(10, 100, 50, 523))
        for (rect in bounds) {
            assertTrue(BadgeLayout.place(parser.parse(card(bounds = rect), screen), screen, 2.75f).isEmpty())
        }
    }
    @Test fun compactBadgeAnchorsToUpperRightAndAvoidsBottomProgress() {
        val poster = ScreenRect(36, 398, 338, 823)
        val title = parser.parse(card(bounds = poster), screen).single()
        val badge = BadgeLayout.place(listOf(title), screen, 3f).single().bounds
        assertEquals(ScreenRect(233, 407, 329, 467), badge)
        // A card clipped at its bottom can still show its original upper-right corner.
        val clippedAtBottom = title.copy(bounds = ScreenRect(36, 2200, 338, 2623))
        assertEquals(1, BadgeLayout.place(listOf(clippedAtBottom), screen, 3f).size)
    }
    @Test fun layoutIsStableWhenTreeOrderChanges() {
        val a = card()
        val b = card(id = "456", bounds = ScreenRect(400, 100, 702, 523))
        assertEquals(parser.parse(NetflixNode(children = listOf(a, b)), screen),
            parser.parse(NetflixNode(children = listOf(b, a)), screen))
    }

    @Test fun capturedNetflixDumpContainsTwelveLabeledStandardCards() {
        val titles = parser.parse(fixture("captured-standard.xml"), screen)
        assertEquals(12, titles.size)
        assertEquals(12, titles.map { it.key }.distinct().size)
        assertTrue(titles.all { it.title.isNotBlank() && it.netflixId?.all(Char::isDigit) == true && !it.bounds.isEmpty })
    }

    @Test fun continueWatchingUsesPairedTitleLabelsAndArtworkBounds() {
        val titles = parser.parse(fixture("continue-watching.xml"), screen)
        assertEquals(4, titles.size)
        assertEquals(listOf("Sample Film, Part II", "Sample Drama", "Sample Travel: World Tour", "Sample Thriller"), titles.map { it.title })
        assertTrue(titles.all { it.netflixId == null && it.type == null })
        assertTrue(titles.all { it.layout == TitleLayout.CONTINUE_WATCHING })
        assertEquals(ScreenRect(36, 398, 338, 823), titles.first().bounds)
        val badges = BadgeLayout.place(titles, screen, 3f)
        assertEquals(3, badges.size) // Fourth card is too narrow at the right edge.
        assertTrue(badges.all { it.bounds.bottom < 841 }) // Progress bar starts here.
    }

    private fun continueCard(title: String = "A movie, part II", play: String = "Play $title, 12 minutes remaining") = NetflixNode(
        "com.netflix.mediaclient:id/volatile-play-id", play, ScreenRect(36, 398, 338, 979),
        children = listOf(
            NetflixNode(bounds = ScreenRect(36, 398, 338, 823), className = "android.widget.ImageView"),
            NetflixNode(description = "Show details for $title", bounds = ScreenRect(51, 859, 171, 979),
                className = "android.widget.ImageView")), className = "android.widget.Button")

    @Test fun continueWatchingPreservesPunctuationAndDoesNotParseRemainingTimeAsTitle() {
        assertEquals("A movie, part II", parser.parse(continueCard(), screen).single().title)
    }
    @Test fun continueWatchingRequiresMatchingPlayAndDetailsLabels() {
        assertTrue(parser.parse(continueCard(play = "Play Something else"), screen).isEmpty())
        assertTrue(parser.parse(continueCard(play = "Play A movie, part II sequel"), screen).isEmpty())
    }
    @Test fun continueWatchingWithoutKnownArtworkDoesNotCoverControls() {
        val node = continueCard()
        assertTrue(parser.parse(node.copy(children = node.children.drop(1)), screen).isEmpty())
    }
    @Test fun continueWatchingRequiresVisiblePairedDetails() {
        val node = continueCard()
        assertTrue(parser.parse(node.copy(children = listOf(node.children[0], node.children[1].copy(visible = false))), screen).isEmpty())
    }
    @Test fun continueWatchingDeduplicatesByTitleAndArtworkPositionWithoutInventingIds() {
        val node = continueCard()
        assertEquals(1, parser.parse(NetflixNode(children = listOf(node, node)), screen).size)
        val other = continueCard(title = "Different movie")
        assertEquals(2, parser.parse(NetflixNode(children = listOf(node, other)), screen).size)
    }

    @Test fun capturedDetailsScreenExtractsTitleYearTypeAndSafeBadgeArea() {
        val titles = parser.parse(fixture("details-weapons.xml"), screen)
        val detail = titles.single()
        assertEquals("Weapons", detail.title)
        assertEquals(2025, detail.releaseYear)
        assertEquals(ContentType.MOVIE, detail.type)
        assertEquals(TitlePresentation.DETAILS, detail.presentation)
        assertNull(detail.netflixId)
        val badge = BadgeLayout.place(titles, ScreenRect(0, 103, 1080, 2196), 3f).single()
        assertTrue(badge.bounds.left >= detail.bounds.right)
        assertTrue(badge.bounds.top >= 866 && badge.bounds.bottom <= 997)
        assertTrue(badge.bounds.bottom < 1003) // Release-year row remains unobscured.
    }
    private fun detailsTree(year: String = "2025", title: String = "Weapons", button: String = "Resume Weapons", duration: String = "2h 8m") = NetflixNode(
        className = "androidx.recyclerview.widget.RecyclerView", bounds = screen, children = listOf(
            NetflixNode(description = "Show controls", bounds = ScreenRect(0, 259, 1080, 866), className = "android.widget.FrameLayout"),
            NetflixNode(description = title, text = title, bounds = ScreenRect(0, 866, 418, 997), className = "android.widget.TextView"),
            NetflixNode(bounds = ScreenRect(30, 997, 431, 1057), children = listOf(
                NetflixNode(text = year, bounds = ScreenRect(30, 1003, 126, 1052), className = "android.widget.TextView"),
                NetflixNode(text = duration, bounds = ScreenRect(294, 1003, 431, 1052), className = "android.widget.TextView"))),
            NetflixNode(description = button, bounds = ScreenRect(30, 1198, 1050, 1342), className = "android.widget.Button")))

    @Test fun detailsRequireVisibleYearAndMatchingPlayOrResumeButton() {
        assertTrue(parser.parse(detailsTree(year = "16+"), screen).isEmpty())
        assertTrue(parser.parse(detailsTree(button = "Play Another title"), screen).isEmpty())
        val tree = detailsTree()
        assertTrue(parser.parse(tree.copy(children = tree.children.filterNot { it.className == "android.widget.Button" }), screen).isEmpty())
        assertTrue(parser.parse(tree, ScreenRect(0, 1200, 1080, 2340)).isEmpty())
    }
    @Test fun tvDetailsUseSeasonsInsteadOfGuessingMovieType() {
        assertEquals(ContentType.SHOW, parser.parse(detailsTree(duration = "2 Seasons"), screen).single().type)
    }
    @Test fun yearOnDetailsDoesNotLeakToUnrelatedPosterCards() {
        val tree = NetflixNode(children = listOf(detailsTree(), card(bounds = ScreenRect(36, 1700, 338, 2123))))
        val titles = parser.parse(tree, screen)
        assertEquals(2025, titles.first { it.presentation == TitlePresentation.DETAILS }.releaseYear)
        assertNull(titles.first { it.presentation == TitlePresentation.POSTER }.releaseYear)
    }
    @Test fun longDetailTitleCanUsePreviewAreaInsteadOfCoveringTitleOrButtons() {
        val tree = detailsTree()
        val heading = tree.children[1].copy(bounds = ScreenRect(0, 866, 1080, 997))
        val titles = parser.parse(tree.copy(children = listOf(tree.children[0], heading) + tree.children.drop(2)), screen)
        val badge = BadgeLayout.place(titles, ScreenRect(0, 103, 1080, 2196), 3f).single()
        assertTrue(ScreenRect(0, 259, 1080, 866).contains(badge.bounds))
    }
    @Test fun noSafeDetailsSpaceMeansNoBadgeRatherThanCoveringControls() {
        val title = parser.parse(detailsTree(), screen).single().copy(badgeAreas = listOf(ScreenRect(1000, 866, 1080, 997)))
        assertTrue(BadgeLayout.place(listOf(title), screen, 3f).isEmpty())
    }

    @Test fun syntheticSearchGridKeepsEachIdentityAndUpperRightCorner() {
        val titles = parser.parse(fixture("mock-search-grid.xml"), screen)
        assertEquals(9, titles.size)
        assertEquals(9, titles.map { it.netflixId }.distinct().size)
        val badges = BadgeLayout.place(titles, screen, 3f)
        assertEquals(9, badges.size)
        badges.forEach { badge ->
            val poster = titles.single { it.key == badge.key }.bounds
            assertEquals(poster.right - 9, badge.bounds.right)
            assertEquals(poster.top + 9, badge.bounds.top)
            assertTrue(poster.contains(badge.bounds))
        }
    }
    @Test fun syntheticTopTenDoesNotUseRankAsTitleOrArtworkBounds() {
        val titles = parser.parse(fixture("mock-top-ten.xml"), screen)
        assertEquals(6, titles.size)
        assertTrue(titles.all { it.title.startsWith("Mock title ") && it.bounds.width == 250 })
        assertEquals(6, BadgeLayout.place(titles, screen, 3f).size)
    }
    @Test fun syntheticWideArtworkUsesOriginalCardGeometry() {
        val titles = parser.parse(fixture("mock-wide-cards.xml"), screen)
        assertEquals(6, titles.size)
        assertTrue(titles.all { it.bounds.width == 480 && it.bounds.height == 270 })
        assertEquals(6, BadgeLayout.place(titles, screen, 3f).size)
    }
    @Test fun syntheticSeriesHeaderDoesNotBorrowEpisodeYearOrIdentity() {
        val detail = parser.parse(fixture("mock-series-details.xml"), screen).single()
        assertEquals("Example Series", detail.title)
        assertEquals(ContentType.SHOW, detail.type)
        assertEquals(2020, detail.releaseYear)
        assertNull(detail.netflixId)
        assertEquals(1, BadgeLayout.place(listOf(detail), screen, 3f).size)
    }
    @Test fun unsupportedLayoutDoesNotGuessTitlesFromArbitraryImagesAndYears() {
        val titles = parser.parse(fixture("mock-unsupported.xml"), screen)
        assertTrue(titles.isEmpty())
        assertTrue(BadgeLayout.place(titles, screen, 3f).isEmpty())
    }
    @Test fun invisibleContainerCannotExposePosterOrContinueWatchingDescendants() {
        val hidden = NetflixNode(visible = false, children = listOf(card(), continueCard()))
        assertTrue(parser.parse(hidden, screen).isEmpty())
        assertTrue(parser.parse(NetflixNode(children = listOf(hidden)), screen).isEmpty())
    }
    @Test fun hiddenLabelBranchCannotSupplyVisiblePosterTitle() {
        val title = NetflixNode("BackgroundImage", "Hidden title")
        val hidden = NetflixNode(visible = false, children = listOf(title))
        assertTrue(parser.parse(card().copy(children = listOf(hidden)), screen).isEmpty())
    }
    @Test fun hiddenContinueWatchingControlsCannotSupplyPairedTitle() {
        val node = continueCard()
        val hidden = NetflixNode(visible = false, children = listOf(node.children[1]))
        assertTrue(parser.parse(node.copy(children = listOf(node.children[0], hidden)), screen).isEmpty())
    }
    @Test fun hiddenDetailsContainerCannotSupplyTitleOrYear() {
        assertTrue(parser.parse(NetflixNode(visible = false, children = listOf(detailsTree())), screen).isEmpty())
        val tree = detailsTree()
        val hidden = NetflixNode(visible = false, children = listOf(tree.children[1]))
        assertTrue(parser.parse(tree.copy(children = listOf(tree.children[0], hidden) + tree.children.drop(2)), screen).isEmpty())
    }

    @Test fun capturedHomeNavigationExcludesCardsReportedBehindBottomBar() {
        val root = fixture("captured-home-mixed.xml")
        val browse = NetflixBrowseViewport.resolve(root, screen)
        assertEquals(2004, browse.bottom)
        val behindNavigation = card(id = "98765432", bounds = ScreenRect(36, 2004, 338, 2427))
        val titles = parser.parse(NetflixNode(children = listOf(root, behindNavigation)), browse)
        assertEquals(16, titles.size)
        assertTrue(titles.none { it.netflixId == "98765432" })
        assertTrue(BadgeLayout.place(titles, browse, 3f).all { it.bounds.bottom <= 2004 })
    }
    @Test fun badgeCrossingNetflixNavigationBoundaryIsNotDrawn() {
        val title = parser.parse(card(bounds = ScreenRect(36, 1980, 338, 2403)), screen).single()
        assertTrue(BadgeLayout.place(listOf(title), screen.copy(bottom = 2004), 3f).isEmpty())
    }
    @Test fun titleLabelsAloneDoNotShrinkBrowseViewport() {
        val fakeNavigation = NetflixNode(children = listOf(
            card(title = "Home", bounds = ScreenRect(36, 1800, 338, 2223)),
            card(title = "Search", bounds = ScreenRect(356, 1800, 658, 2223))))
        assertEquals(screen, NetflixBrowseViewport.resolve(fakeNavigation, screen))
        assertEquals(screen, NetflixBrowseViewport.resolve(NetflixNode(className = "android.view.View",
            description = "Home", bounds = ScreenRect(75, 2004, 303, 2148)), screen))
    }
    @Test fun hiddenNavigationAndOverlappingLabelsDoNotShrinkViewport() {
        val nav = fixture("captured-home-mixed.xml")
        assertEquals(screen, NetflixBrowseViewport.resolve(nav.copy(visible = false), screen))
        val labels = NetflixBrowseViewport.navigationLabels.map { label ->
            NetflixNode(className = "android.view.View", description = label, bounds = ScreenRect(75, 2004, 900, 2148))
        }
        assertEquals(screen, NetflixBrowseViewport.resolve(NetflixNode(children = labels), screen))
    }

    @Test fun capturedSearchLandingUsesWideArtworkInsteadOfPlayButtonRow() {
        val titles = parser.parse(fixture("captured-search-recommendations.xml"), screen)
        assertEquals(8, titles.size)
        assertTrue(titles.all { it.layout == TitleLayout.HORIZONTAL_ARTWORK })
        assertEquals("Sample show 01", titles.first().title)
        assertEquals("90000001", titles.first().netflixId)
        assertEquals(ScreenRect(36, 391, 396, 594), titles.first().bounds)
        assertTrue(titles.all { it.bounds.width == 360 })
        val badges = BadgeLayout.place(titles, screen, 3f)
        assertEquals(8, badges.size)
        assertTrue(badges.all { it.bounds.right <= 396 })
    }
    @Test fun capturedSearchResultsKeepSavedMovieIdentitySeparateFromSameNamedSeriesAndGames() {
        val titles = parser.parse(fixture("captured-search-heist.xml"), screen)
        val movie = titles.filter { it.netflixId == "60020557" }
        assertEquals(2, movie.size)
        assertTrue(movie.all { it.type == ContentType.MOVIE && it.title == "Heist" })
        assertTrue(titles.any { it.netflixId == "81087195" && it.type == ContentType.SHOW })
        assertTrue(titles.none { it.netflixId in setOf("81738613", "81665065", "82048611", "81581183") })
    }
    @Test fun horizontalRowRequiresOneOwnTitleAndOneOwnImage() {
        fun row(children: List<NetflixNode>) = NetflixNode(
            "PinotUIEntity:PinotHorizontalArtworkWithPlaybackEntityTreatment:Movie:Video:12345678",
            bounds = ScreenRect(36, 391, 1044, 594), children = children)
        val image = NetflixNode("BackgroundImage", bounds = ScreenRect(36, 391, 396, 594))
        val label = NetflixNode(text = "Example", className = "android.widget.TextView", bounds = ScreenRect(426, 454, 918, 526))
        assertEquals("Example", parser.parse(row(listOf(image, label)), screen).single().title)
        for (children in listOf(listOf(image), listOf(label), listOf(image, image, label), listOf(image, label, label),
            listOf(image, label.copy(visible = false)), listOf(image.copy(visible = false), label),
            listOf(image, label.copy(bounds = ScreenRect(100, 454, 300, 526))))) {
            assertTrue(parser.parse(row(children), screen).isEmpty())
        }
    }

    @Test fun capturedMyListUsesNativeArtworkWithoutInventingCatalogIds() {
        val titles = parser.parse(fixture("captured-my-list.xml"), screen)
        assertEquals(6, titles.size)
        assertTrue(titles.all { it.layout == TitleLayout.SAVED_LIST })
        assertEquals("Saved sample 01", titles.first().title)
        assertEquals(ScreenRect(36, 749, 456, 989), titles.first().bounds)
        assertTrue(titles.all { it.netflixId == null && it.type == null })
        assertTrue(BadgeLayout.place(titles, screen, 3f).all { it.bounds.right <= 456 })
    }
    @Test fun nativeSavedRowsRequirePageHeadingPlayControlAndNonOverlappingArtwork() {
        val root = fixture("captured-my-list.xml")
        fun change(node: NetflixNode, transform: (NetflixNode) -> NetflixNode): NetflixNode =
            transform(node.copy(children = node.children.map { change(it, transform) }))
        val noHeading = change(root) { if (it.text == "My List") it.copy(text = null) else it }
        val noPlay = change(root) { if (it.className == "android.widget.ImageButton") it.copy(description = "Download") else it }
        val hiddenArt = change(root) { if (it.className == "android.widget.ImageView") it.copy(visible = false) else it }
        val overlappingArt = change(root) { if (it.className == "android.widget.ImageView") it.copy(bounds = it.bounds.copy(right = 800)) else it }
        for (invalid in listOf(noHeading, noPlay, hiddenArt, overlappingArt)) {
            assertTrue(parser.parse(invalid, screen).isEmpty())
        }
    }
    @Test fun capturedNetflixFeatureTooltipHasNoRecognizedTitles() {
        assertTrue(parser.parse(fixture("captured-feature-tooltip.xml"), screen).isEmpty())
    }

    @Test fun capturedSeriesDetailsRecognizePartsAndChapterPlaybackWithoutBorrowingEpisodeIdentity() {
        val detail = parser.parse(fixture("captured-details-lupin.xml"), screen).single()
        assertEquals("Lupin", detail.title)
        assertEquals(ContentType.SHOW, detail.type)
        assertEquals(2026, detail.releaseYear) // Preserve Netflix's observed year; do not invent the original series year.
        assertNull(detail.netflixId)
        assertEquals(TitleLayout.DETAILS, detail.layout)
        assertEquals(1, BadgeLayout.place(listOf(detail), screen, 3f).size)
    }
    @Test fun chapterPlaybackCannotMakeAMovieOrUnknownHeaderASeries() {
        assertTrue(parser.parse(detailsTree(button = "Play Chapter 9"), screen).isEmpty())
        assertTrue(parser.parse(detailsTree(button = "Play Chapter 9", duration = "Unknown"), screen).isEmpty())
        assertEquals(ContentType.SHOW, parser.parse(detailsTree(button = "Play Chapter 9", duration = "4 Parts"), screen).single().type)
    }

    @Test fun capturedPaybackDetailsKeepRuntimeFromTheSameMetadataRow() {
        val detail = parser.parse(fixture("captured-details-payback.xml"), screen).single()
        assertEquals("Payback", detail.title)
        assertEquals(1999, detail.releaseYear)
        assertEquals(ContentType.MOVIE, detail.type)
        assertEquals(101, detail.runtimeMinutes)
        assertEquals(1, BadgeLayout.place(listOf(detail), screen, 3f).size)
    }

    private fun fixture(name: String = "standard.xml"): NetflixNode {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")!!
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        fun convert(element: Element): NetflixNode {
            val bounds = Regex("-?\\d+").findAll(element.getAttribute("bounds")).map { it.value.toInt() }.toList()
            val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            return NetflixNode(element.getAttribute("resource-id").ifEmpty { null },
                element.getAttribute("content-desc").ifEmpty { null },
                if (bounds.size == 4) ScreenRect(bounds[0], bounds[1], bounds[2], bounds[3]) else screen,
                children = children.map(::convert), className = element.getAttribute("class").ifEmpty { null },
                text = element.getAttribute("text").ifEmpty { null })
        }
        return convert(document.documentElement)
    }
}
