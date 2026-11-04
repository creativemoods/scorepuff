// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.creativemoods.scorepuff.BuildConfig

internal enum class UpdateStatus { CHECKING, CURRENT, AVAILABLE, FAILED }
internal data class UpdateState(
    val status: UpdateStatus = UpdateStatus.CHECKING,
    val available: AppUpdate? = null,
    val prompt: Boolean = false,
    val openFailed: Boolean = false
)

class UpdateViewModel(
    private val checkLatest: suspend () -> AppUpdate? = GitHubUpdateClient(
        BuildConfig.VERSION_CODE.toLong(), BuildConfig.RELEASE_CERTIFICATE_SHA256)::check,
    private val automaticPrompt: Boolean = !BuildConfig.DEBUG,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutable = MutableStateFlow(UpdateState())
    internal val state = mutable.asStateFlow()
    private var checking = false

    // One startup check per ViewModel: rotating or returning from the browser never re-prompts.
    init { check() }

    fun check() {
        if (checking) return
        checking = true
        mutable.value = UpdateState()
        viewModelScope.launch {
            try {
                val update = withContext(ioDispatcher) { checkLatest() }
                mutable.value = if (update == null) UpdateState(UpdateStatus.CURRENT)
                    else UpdateState(UpdateStatus.AVAILABLE, update, prompt = automaticPrompt)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = UpdateState(UpdateStatus.FAILED) }
            finally { checking = false }
        }
    }

    fun dismiss() { mutable.value = mutable.value.copy(prompt = false) }
    fun openFailed() { mutable.value = mutable.value.copy(prompt = false, openFailed = true) }
}
