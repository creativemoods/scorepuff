// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import pt.creativemoods.scorepuff.accessibility.*
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.overlay.*
import pt.creativemoods.scorepuff.ratings.*

/** Offline debug screen: real parser, layout and Canvas badge renderer; synthetic scores only. */
class MockCatalogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val screens = listOf("Home / carousels" to "captured-standard.xml", "Continue Watching" to "continue-watching.xml",
                "Movie details" to "details-weapons.xml",
                "Prime Home (captured)" to "captured-prime-home.xml",
                "Prime series details (captured)" to "captured-prime-details-luther.xml",
                "Prime movie details (captured)" to "captured-prime-details-casino-royale.xml",
                "Payback runtime (captured)" to "captured-details-payback.xml",
                "Series details (captured)" to "captured-details-lupin.xml",
                "My List (captured)" to "captured-my-list.xml",
                "Netflix popup (captured)" to "captured-feature-tooltip.xml",
                "Search landing (captured)" to "captured-search-recommendations.xml",
                "Search results (captured)" to "captured-search-heist.xml",
                "Home navigation (captured)" to "captured-home-mixed.xml", "Search / My List grid (mock)" to "mock-search-grid.xml",
                "Top 10 (mock)" to "mock-top-ten.xml", "Wide cards (mock)" to "mock-wide-cards.xml",
                "Series details (mock)" to "mock-series-details.xml", "Unsupported layout (mock)" to "mock-unsupported.xml")
            var fixture by remember { mutableStateOf("captured-standard.xml") }
            var menuOpen by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                    Column(Modifier.fillMaxSize().background(ComposeColor(0xFF101218)).safeDrawingPadding()) {
                        Text("ScorePuff playground", Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
                        Text("Offline mock • synthetic scores • drag to scroll", Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall)
                        Box(Modifier.padding(horizontal = 12.dp)) {
                            OutlinedButton(onClick = { menuOpen = true }) {
                                Text(screens.first { it.second == fixture }.first)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                screens.forEach { (label, file) ->
                                    DropdownMenuItem(text = { Text(label) }, onClick = { fixture = file; menuOpen = false })
                                }
                            }
                        }
                        key(fixture) {
                            AndroidView(factory = { FixtureScreen(it, fixture) }, modifier = Modifier.fillMaxWidth().weight(1f))
                        }
                        Text("Spinner: loading · ?: ambiguous · number: average /10", Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private class FixtureScreen(context: Context, fixture: String) : FrameLayout(context) {
    private val badges = RatingBadgeView(context)
    private val artwork = FixtureArtwork(context, fixture) { badges.submit(it); badges.invalidate() }
    init {
        addView(artwork, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(badges, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}

private class FixtureArtwork(context: Context, fixture: String, val submit: (List<TitleBadge>) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val source: List<NetflixTitle>
    private val origin = IntArray(2)
    private var shown = emptyList<TitleBadge>()
    private var offset = 0f
    private var lastY = 0f
    private val samples = listOf(BadgeContent.Loading, BadgeContent.Ambiguous,
        BadgeContent.Score("8.4"), BadgeContent.Score("0.0"), BadgeContent.Score("10.0"))

    init {
        val document = context.assets.open("fixtures/$fixture").use {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it)
        }
        fun convert(element: Element): NetflixNode {
            val coordinates = Regex("-?\\d+").findAll(element.getAttribute("bounds")).map { it.value.toInt() }.toList()
            val children = (0 until element.childNodes.length).mapNotNull { element.childNodes.item(it) as? Element }
            return NetflixNode(element.getAttribute("resource-id").ifEmpty { null },
                element.getAttribute("content-desc").ifEmpty { null },
                if (coordinates.size == 4) ScreenRect(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
                else ScreenRect(0, 0, 1080, 2340),
                visible = element.getAttribute("visible-to-user") != "false",
                children = children.map(::convert), className = element.getAttribute("class").ifEmpty { null },
                text = element.getAttribute("text").ifEmpty { null },
                clickable = element.getAttribute("clickable") == "true", scrollable = element.getAttribute("scrollable") == "true")
        }
        val root = convert(document.documentElement)
        val screen = ScreenRect(0, 0, 1080, 2340)
        source = if (fixture.startsWith("captured-prime")) PrimeNodeParser.parse(root, PrimeNodeParser.browseViewport(root, screen))
            else NetflixNodeParser().parse(root, NetflixBrowseViewport.resolve(root, screen))
        contentDescription = "Mock streaming artwork. Drag vertically to test clipped cards. Scores are synthetic."
        isClickable = true
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> rebuild() }
    }

    private fun rebuild() {
        if (width == 0) return
        getLocationOnScreen(origin)
        val scale = width / 1080f
        val firstTop = source.minOfOrNull { it.bounds.top } ?: 0
        fun transform(r: ScreenRect) = ScreenRect((r.left * scale).toInt() + origin[0],
            ((r.top - firstTop) * scale + 12 * density - offset).toInt() + origin[1],
            (r.right * scale).toInt() + origin[0],
            ((r.bottom - firstTop) * scale + 12 * density - offset).toInt() + origin[1])
        shown = source.mapIndexed { index, title ->
            TitleBadge(title.copy(bounds = transform(title.bounds), badgeAreas = title.badgeAreas.map(::transform)),
                samples[index % samples.size])
        }
        submit(shown)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 18, 24))
        if (shown.isEmpty()) {
            paint.color = Color.LTGRAY; paint.typeface = Typeface.DEFAULT; paint.textSize = 14 * density
            canvas.drawText("No recognized cards — no badges", 12 * density, 36 * density, paint)
        }
        shown.forEachIndexed { index, rated ->
            val r = rated.title.bounds
            val left = (r.left - origin[0]).toFloat(); val top = (r.top - origin[1]).toFloat()
            paint.color = Color.rgb(40 + index % 3 * 15, 50 + index % 4 * 12, 75 + index % 5 * 14)
            canvas.drawRoundRect(left, top, left + r.width, top + r.height, 4 * density, 4 * density, paint)
            canvas.save()
            canvas.clipRect(left + 6 * density, top, left + r.width - 6 * density, top + r.height)
            paint.color = Color.WHITE; paint.typeface = Typeface.DEFAULT_BOLD; paint.textSize = 12 * density
            canvas.drawText(rated.title.title, left + 8 * density, top + r.height / 2f, paint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastY = event.y; return true }
            MotionEvent.ACTION_MOVE -> {
                val contentHeight = (source.maxOfOrNull { it.bounds.bottom } ?: 0) - (source.minOfOrNull { it.bounds.top } ?: 0)
                offset = (offset + lastY - event.y).coerceIn(0f, maxOf(0f, contentHeight * width / 1080f - height + 60 * density))
                lastY = event.y; rebuild(); return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
