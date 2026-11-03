// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.data

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pt.creativemoods.scorepuff.data.models.NetflixTitle
import pt.creativemoods.scorepuff.data.models.TitlePresentation
import pt.creativemoods.scorepuff.data.models.TitleLayout
import pt.creativemoods.scorepuff.ratings.*

data class Diagnostics(
    val connected: Boolean = false,
    val overlayAllowed: Boolean = true,
    val debug: Boolean = false,
    val overlayStatus: String = "Service not connected",
    val detectedCount: Int = 0,
    val continueWatchingCount: Int = 0,
    val detailsCount: Int = 0,
    val badgeCount: Int = 0,
    val scanCount: Long = 0,
    val lastScan: String = "Not scanned yet",
    val lastTitles: List<NetflixTitle> = emptyList(),
    val ratingsSummary: String = "Waiting for Netflix or Prime Video",
    val lookupDetails: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/** Manual DI: the activity and service share this in-process repository. */
object DiagnosticsRepository {
    private val mutable = MutableStateFlow(Diagnostics())
    val state = mutable.asStateFlow()
    private var initialized = false

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        mutable.update { it.copy(debug = prefs.getBoolean("debug", false),
            overlayAllowed = prefs.getBoolean("overlay", true)) }
        initialized = true
    }

    fun setDebug(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("debug", enabled).apply()
        mutable.update { it.copy(debug = enabled, lastTitles = emptyList(), lookupDetails = emptyList()) }
    }

    fun setOverlay(context: Context, enabled: Boolean) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("overlay", enabled).apply()
        mutable.update { it.copy(overlayAllowed = enabled) }
    }

    fun connection(connected: Boolean) {
        mutable.update { it.copy(connected = connected, detectedCount = 0, continueWatchingCount = 0, detailsCount = 0, badgeCount = 0,
            overlayStatus = if (connected) "Waiting for Netflix or Prime Video" else "Service not connected") }
    }

    fun hidden(reason: String) {
        mutable.update { it.copy(overlayStatus = reason, detectedCount = 0, continueWatchingCount = 0, detailsCount = 0, badgeCount = 0) }
    }

    fun scanned(titles: List<NetflixTitle>, badges: Int, truncated: Boolean) {
        mutable.update { it.copy(
            detectedCount = titles.size,
            continueWatchingCount = titles.count { it.layout == TitleLayout.CONTINUE_WATCHING },
            detailsCount = titles.count { it.presentation == TitlePresentation.DETAILS },
            badgeCount = badges,
            scanCount = it.scanCount + 1,
            lastScan = time(),
            overlayStatus = when {
                truncated -> "Tree limit reached · partial results"
                titles.isEmpty() -> "No supported artwork"
                badges == 0 -> "Waiting for ratings / no badge space"
                else -> "Showing rating badges"
            },
            lastTitles = if (it.debug && titles.isNotEmpty()) titles else it.lastTitles
        ) }
    }

    fun badgesUpdated(badges: Int) {
        mutable.update { it.copy(badgeCount = badges,
            overlayStatus = if (it.detectedCount > 0 && badges > 0) "Showing rating badges" else it.overlayStatus) }
    }

    fun ratings(titles: List<NetflixTitle>, states: Map<String, RatingDisplay>) {
        val lookups = titles.map { it.lookup() }.distinctBy { it.key }
        val displays = lookups.mapNotNull { states[it.key] }
        val counts = displays.groupingBy { it.status }.eachCount()
        val summary = "${counts[RatingStatus.READY] ?: 0}/${lookups.size} rated · ${counts[RatingStatus.LOADING] ?: 0} loading · ${counts[RatingStatus.AMBIGUOUS] ?: 0} ambiguous · ${counts[RatingStatus.ERROR] ?: 0} errors"
        val problems = displays.map { it.detail }.filter { it.isNotEmpty() }.distinct().joinToString("; ")
        mutable.update { it.copy(ratingsSummary = summary + if (problems.isEmpty()) "" else "\n$problems",
            lookupDetails = if (it.debug && lookups.isNotEmpty()) lookups.map { lookup ->
                val display = states[lookup.key]
                "${lookup.title}: ${display?.status ?: "QUEUED"}${display?.detail?.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty()}"
            } else it.lookupDetails) }
    }

    fun error(tag: String, failure: Exception) {
        // Exception messages can contain node content. Log only the exception type.
        val message = "${time()} · $tag: ${failure.javaClass.simpleName}"
        Log.w(tag, message)
        mutable.update { it.copy(errors = (listOf(message) + it.errors).take(10)) }
    }

    private fun time() = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
}
