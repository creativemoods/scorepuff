// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import android.app.Application
import android.content.ComponentName
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import pt.creativemoods.scorepuff.data.AppContainer
import pt.creativemoods.scorepuff.data.cache.TitleMapping
import pt.creativemoods.scorepuff.ratings.*
import pt.creativemoods.scorepuff.metadata.StreamingShareParser
import pt.creativemoods.scorepuff.metadata.SharedTitleLink
import pt.creativemoods.scorepuff.metadata.SharedMatchPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pt.creativemoods.scorepuff.accessibility.ScorePuffAccessibilityService
import pt.creativemoods.scorepuff.data.DiagnosticsRepository

data class ManualMatchState(
    val selected: TitleMapping? = null,
    val imdbId: String = "",
    val preview: OmdbDetails? = null,
    val busy: Boolean = false,
    val message: String = ""
)

data class SharedMatchState(
    val visible: Boolean = false,
    val link: SharedTitleLink? = null,
    val preview: SharedMatchPreview? = null,
    val busy: Boolean = false,
    val message: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val manualMappings = AppContainer.mappings(application).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val manualMutable = MutableStateFlow(ManualMatchState())
    val manualMatch = manualMutable.asStateFlow()
    private val matches = AppContainer.manualMatches(application)
    private var operation: Job? = null
    private var shareOperation: Job? = null
    private val sharedMutable = MutableStateFlow(SharedMatchState())
    val sharedMatch = sharedMutable.asStateFlow()
    private val shares = AppContainer.sharedMatches(application)
    val diagnostics = DiagnosticsRepository.state
    private val enabledState = MutableStateFlow(false)
    val enabled = enabledState.asStateFlow()

    init { DiagnosticsRepository.initialize(application); refreshEnabled() }

    fun refreshEnabled() {
        val context = getApplication<Application>()
        val expected = ComponentName(context, ScorePuffAccessibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java)
        enabledState.value = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected }
    }

    fun receiveShare(text: String) {
        shareOperation?.cancel()
        val link = StreamingShareParser.parse(text)
        sharedMutable.value = SharedMatchState(visible = true, link = link, busy = link != null)
        if (link == null) {
            sharedMutable.update { it.copy(message = "Share a Netflix /title/ link or a Prime Video GTI details link using Share → More. Other links are not accepted.") }
            return
        }
        val visit = AppContainer.lastDetails?.takeIf { System.currentTimeMillis() - it.seenAt in 0L..60_000L }
        if (visit == null || visit.title.provider != link.provider) {
            sharedMutable.update { it.copy(busy = false, message = "Open the ${link.providerLabel} details page with its year visible, wait for the rating, then Share → ScorePuff.") }
            return
        }
        shareOperation = viewModelScope.launch {
            try {
                val preview = shares.preview(link, visit.title)
                sharedMutable.update { it.copy(preview = preview, busy = false) }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                sharedMutable.update { it.copy(busy = false, message = when (failure) {
                    is IllegalArgumentException -> failure.message ?: "Could not verify the association."
                    else -> "Could not verify the shared title with OMDb. Please try again later."
                }) }
            }
        }
    }

    fun saveSharedAssociation() {
        val preview = sharedMutable.value.preview ?: return
        if (sharedMutable.value.busy) return
        shareOperation = viewModelScope.launch {
            sharedMutable.update { it.copy(busy = true) }
            try {
                shares.save(preview)
                sharedMutable.update { it.copy(preview = null, busy = false,
                    message = "Association saved locally. Return to the streaming app to see its ratings on list cards.") }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                sharedMutable.update { it.copy(busy = false, message = "Could not save the association. Try again.") }
            }
        }
    }

    fun dismissSharedAssociation() {
        shareOperation?.cancel()
        sharedMutable.value = SharedMatchState()
    }

    fun selectManualTarget(target: TitleMapping) {
        operation?.cancel()
        manualMutable.value = ManualMatchState(selected = target,
            imdbId = if (target.matchConfidence == "manual") target.imdbId.orEmpty() else "")
    }

    fun setManualImdbId(value: String) {
        operation?.cancel()
        manualMutable.update { it.copy(imdbId = value.trim(), preview = null, busy = false, message = "") }
    }

    fun previewManualMatch() {
        val state = manualMutable.value
        val target = state.selected ?: return
        runManualOperation {
            val preview = matches.preview(target, state.imdbId)
            manualMutable.update { it.copy(preview = preview, message = "Check the title, year and type before saving.") }
        }
    }

    fun saveManualMatch() {
        val state = manualMutable.value
        val target = state.selected ?: return
        val preview = state.preview ?: return
        runManualOperation {
            val saved = matches.save(target, preview)
            manualMutable.update { it.copy(selected = saved, preview = null,
                message = "Saved ${preview.candidate.title} (${preview.candidate.year}). Return to the streaming app to see its ratings.") }
        }
    }

    fun removeManualMatch() {
        val target = manualMutable.value.selected?.takeIf { it.matchConfidence == "manual" } ?: return
        runManualOperation {
            matches.remove(target)
            manualMutable.value = ManualMatchState(message = "Manual match removed. Automatic matching will resume.")
        }
    }

    private fun runManualOperation(block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch {
            manualMutable.update { it.copy(busy = true, message = "") }
            try { block() }
            catch (failure: CancellationException) { throw failure }
            catch (failure: ProviderException) {
                manualMutable.update { it.copy(preview = null, message = when (failure.problem) {
                    ProviderProblem.AUTH -> "Check your OMDb key and activation."
                    ProviderProblem.QUOTA -> "OMDb request limit reached. Try later."
                    else -> "OMDb could not verify that IMDb ID. Try again later."
                }) }
            } catch (failure: IllegalArgumentException) {
                manualMutable.update { it.copy(preview = null, message = failure.message ?: "Invalid match") }
            } catch (failure: Exception) {
                manualMutable.update { it.copy(preview = null, message = "Could not save or retrieve this match. Try again.") }
            } finally {
                manualMutable.update { it.copy(busy = false) }
            }
        }
    }

    fun setDebug(enabled: Boolean) = DiagnosticsRepository.setDebug(getApplication(), enabled)
    fun setOverlay(enabled: Boolean) = DiagnosticsRepository.setOverlay(getApplication(), enabled)
}
