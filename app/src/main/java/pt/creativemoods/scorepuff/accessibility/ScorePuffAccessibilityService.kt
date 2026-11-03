// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import pt.creativemoods.scorepuff.data.DiagnosticsRepository
import pt.creativemoods.scorepuff.data.AppContainer
import pt.creativemoods.scorepuff.ratings.*
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.overlay.RatingOverlayManager
import pt.creativemoods.scorepuff.overlay.badge

class ScorePuffAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val parser = NetflixNodeParser()
    private val reader = NetflixTreeReader()
    private val primeReader = PrimeTreeReader()
    private var activePackage: String? = null
    private val navigation = NetflixNavigationTracker { android.os.SystemClock.uptimeMillis() }
    private var recentPosters = emptyList<NetflixTitle>()
    private var postersSeenAt = 0L
    private var clickEvents = 0
    private var lastClickStatus = "none"
    private var overlay: RatingOverlayManager? = null
    private var connected = false
    private var refreshPending = false
    private var playerActivity = false
    private var ratings: RatingsRepository? = null
    private var currentTitles = emptyList<NetflixTitle>()
    private var currentViewport: ScreenRect? = null

    private val refresh = Runnable {
        refreshPending = false
        scan()
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!connected) return
            // Also refresh settled animations even when Netflix emits no final scroll event.
            if (!refreshPending) scan()
            handler.postDelayed(this, 350)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagnosticsRepository.initialize(this)
        if (connected) shutdown()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        connected = true
        overlay = RatingOverlayManager(this).also { manager ->
            manager.onBadgeCount = { count ->
                if (currentTitles.isNotEmpty()) DiagnosticsRepository.badgesUpdated(count)
            }
        }
        ratings = AppContainer.ratings(this, scope)
        scope.launch { ratings!!.states.collect { renderRatings() } }
        scope.launch { AppContainer.matchChanges.collect {
            ratings?.invalidateMatches()
            ratings?.setVisible(currentTitles)
        } }
        DiagnosticsRepository.connection(true)
        scope.launch {
            DiagnosticsRepository.state.map { it.overlayAllowed }.distinctUntilChanged().collect { allowed ->
                if (!allowed) hide("Overlays paused") else schedule()
            }
        }
        handler.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !connected || event.packageName?.toString() !in SUPPORTED_PACKAGES) return
        selectPackage(event.packageName?.toString())
        if (event.packageName?.toString() == NETFLIX_PACKAGE && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            clickEvents++
            lastClickStatus = "expired poster snapshot"
            var origin: NetflixTitle? = null
            if (DiagnosticsRepository.state.value.overlayAllowed && android.os.SystemClock.uptimeMillis() - postersSeenAt <= 2_000) {
                try {
                    val source = event.source
                    lastClickStatus = if (source == null) "source absent" else "source present; cards=${recentPosters.size}"
                    source?.let { origin = NetflixClickReader.origin(it, recentPosters) }
                    if (origin != null) lastClickStatus = "known Netflix ID"
                }
                catch (failure: RuntimeException) { /* Source may disappear during navigation; fail closed. */ }
            }
            navigation.click(origin)
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Avoid storing arbitrary window labels. Only retain this boolean heuristic.
            val name = event.className?.toString().orEmpty().lowercase()
            if (name.endsWith("activity")) playerActivity = name.contains("player") || name.contains("playback")
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            hide("Streaming app changing · refreshing")
        }
        schedule()
    }

    private fun schedule() {
        if (refreshPending || !connected) return
        // Coalesce without resetting the deadline: sustained events cannot starve refreshes.
        refreshPending = true
        handler.postDelayed(refresh, 180)
    }

    private fun scan() {
        if (!connected) return
        try {
            if (!DiagnosticsRepository.state.value.overlayAllowed) { hide("Overlays paused"); return }
            val power = getSystemService(PowerManager::class.java)
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (!power.isInteractive || keyguard.isKeyguardLocked) { navigation.reset(); hide("Screen off or locked"); return }
            // Only inspect package ownership of other apps, never their trees/descriptions.
            val root = rootInActiveWindow
            if (root == null) { hide("No active window"); return }
            try {
                if (root.packageName?.toString() !in SUPPORTED_PACKAGES) {
                    selectPackage(null)
                    hide("Supported streaming app is not foreground")
                    return
                }
                selectPackage(root.packageName?.toString())
                val prime = activePackage == PRIME_PACKAGE
                // Cover notifications, permission dialogs, multiwindow focus and the keyboard.
                val windowList = windows
                try {
                    val netflixWindow = windowList.firstOrNull { it.id == root.windowId }
                    val foreignFocus = windowList.any {
                        it.isFocused && it.id != root.windowId && it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    }
                    if (netflixWindow == null || (!netflixWindow.isActive && !netflixWindow.isFocused) || foreignFocus) {
                        hide("Streaming window is not focused")
                        return
                    }
                    if (playerActivity || resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        hide("Playback / landscape · badges hidden")
                        return
                    }
                    val snapshot = if (prime) primeReader.read(root) else reader.read(root)
                    if (snapshot.playback) { hide("Playback detected · badges hidden"); return }
                    val windowBounds = Rect()
                    netflixWindow.getBoundsInScreen(windowBounds)
                    val screen = ScreenRect(windowBounds.left, windowBounds.top, windowBounds.right, windowBounds.bottom)
                    val viewport = if (prime) PrimeNodeParser.browseViewport(snapshot.root, screen) else NetflixBrowseViewport.resolve(snapshot.root, screen)
                    val parsed = if (prime) PrimeNodeParser.parse(snapshot.root, viewport) else parser.parse(snapshot.root, viewport)
                    val titles = if (prime) parsed else navigation.apply(parsed)
                    val posters = parsed.filter { it.presentation == TitlePresentation.POSTER }
                    if (!prime && posters.isNotEmpty()) {
                        recentPosters = posters
                        postersSeenAt = android.os.SystemClock.uptimeMillis()
                    }
                    titles.firstOrNull { it.presentation == TitlePresentation.DETAILS }?.let {
                        AppContainer.lastDetails = AppContainer.DetailVisit(it, System.currentTimeMillis())
                    }
                    if (titles.none { it.presentation == TitlePresentation.DETAILS } && titles.any { it.presentation == TitlePresentation.POSTER }) AppContainer.lastDetails = null
                    currentViewport = viewport
                    currentTitles = titles
                    ratings?.setVisible(titles)
                    renderRatings()
                    DiagnosticsRepository.scanned(titles, overlay?.badgeCount ?: 0, snapshot.truncated)
                } finally {
                    if (android.os.Build.VERSION.SDK_INT < 33) {
                        @Suppress("DEPRECATION")
                        windowList.forEach { it.recycle() }
                    }
                }
            } finally {
                NetflixTreeReader.release(root)
            }
        } catch (failure: RuntimeException) {
            // Accessibility nodes may disappear mid-traversal when Netflix replaces its window.
            DiagnosticsRepository.error("NRService", failure)
            hide("Scan failed · retrying")
        }
    }

    private fun selectPackage(packageName: String?) {
        if (activePackage == packageName) return
        activePackage = packageName
        playerActivity = false
        navigation.reset()
        recentPosters = emptyList()
        // Preserve the recent visit across the system Share chooser and our receiver.
        // Switching streaming providers invalidates it; receiveShare also checks provider/age.
        if (packageName in SUPPORTED_PACKAGES && AppContainer.lastDetails?.title?.provider !=
            (if (packageName == PRIME_PACKAGE) StreamingProvider.PRIME_VIDEO else StreamingProvider.NETFLIX)) AppContainer.lastDetails = null
        hide("Streaming app changed · refreshing")
    }

    private fun renderRatings() {
        if (!connected) return
        if (currentTitles.isEmpty()) { overlay?.hide(); return }
        val states = ratings?.states?.value.orEmpty()
        val resolved = currentTitles.mapNotNull { title ->
            title.badge(states[title.lookup().key])
        }
        overlay?.show(resolved, currentViewport)
        DiagnosticsRepository.ratings(currentTitles, states)
    }

    private fun hide(reason: String) {
        ratings?.setVisible(emptyList())
        currentTitles = emptyList()
        currentViewport = null
        overlay?.hide()
        DiagnosticsRepository.hidden(reason)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hide("Display changed · refreshing")
        schedule()
    }

    override fun onInterrupt() {
        handler.removeCallbacks(refresh)
        refreshPending = false
        hide("Accessibility interrupted")
    }

    private fun shutdown() {
        connected = false
        navigation.reset()
        recentPosters = emptyList()
        handler.removeCallbacksAndMessages(null)
        refreshPending = false
        hide("Service disconnected")
        overlay = null
        scope.cancel()
        ratings = null
        DiagnosticsRepository.connection(false)
    }

    override fun onUnbind(intent: Intent?): Boolean { shutdown(); return super.onUnbind(intent) }
    override fun onDestroy() { shutdown(); super.onDestroy() }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>?) {
        val state = DiagnosticsRepository.state.value
        writer.println("ScorePuff: connected=${state.connected}; ${state.overlayStatus}")
        writer.println("Detected=${state.detectedCount}; ContinueWatching=${state.continueWatchingCount}; details=${state.detailsCount}; badges=${state.badgeCount}")
        writer.println("Ratings: ${state.ratingsSummary}")
        writer.println("Provider: $activePackage; browse viewport: $currentViewport")
        writer.println("Prime artwork=${currentTitles.count { it.layout == TitleLayout.PRIME_ARTWORK }}")
        writer.println("Layouts: savedList=${currentTitles.count { it.layout == TitleLayout.SAVED_LIST }}; horizontalArtwork=${currentTitles.count { it.layout == TitleLayout.HORIZONTAL_ARTWORK }}")
        writer.println("Navigation: pending=${navigation.hasPendingOrigin}; linkedDetails=${navigation.hasLinkedDetails}")
        writer.println("Clicks=$clickEvents; last=$lastClickStatus; sourceIds=${NetflixClickReader.lastSourceIds}")
        writer.println("Last scan=${state.lastScan}; scans=${state.scanCount}; errors=${state.errors.size}")
        if (args?.contains("buttons") == true) {
            val root = rootInActiveWindow
            if (root != null) try {
                if (root.packageName?.toString() == NETFLIX_PACKAGE) {
                    NetflixButtonInspector.inspect(root).forEach(writer::println)
                }
            } finally { NetflixTreeReader.release(root) }
        }
    }

    companion object {
        const val NETFLIX_PACKAGE = "com.netflix.mediaclient"
        const val PRIME_PACKAGE = "com.amazon.avod.thirdpartyclient"
        private val SUPPORTED_PACKAGES = setOf(NETFLIX_PACKAGE, PRIME_PACKAGE)
    }
}
