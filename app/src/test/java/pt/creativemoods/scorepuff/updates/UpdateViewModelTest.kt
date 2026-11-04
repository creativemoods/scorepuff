// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.updates

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private val available = AppUpdate("0.2.19", 21,
        "https://github.com/creativemoods/scorepuff/releases/download/v0.2.19/scorepuff.apk", "https://github.com/creativemoods/scorepuff/releases/tag/v0.2.19")

    @Test fun startupChecksOnceAndLaterDismissesOnlyThePrompt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var checks = 0
            val model = UpdateViewModel(checkLatest = { checks++; available }, automaticPrompt = true, ioDispatcher = StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertEquals(1, checks)
            assertTrue(model.state.value.prompt)
            model.dismiss()
            assertFalse(model.state.value.prompt)
            assertEquals(available, model.state.value.available)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedCheckNeverDisplaysAnUpdatePrompt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = UpdateViewModel(checkLatest = { throw IOException() }, automaticPrompt = true, ioDispatcher = StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertEquals(UpdateStatus.FAILED, model.state.value.status)
            assertFalse(model.state.value.prompt)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun developmentBuildCanShowAvailabilityWithoutPromptingToInstall() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val model = UpdateViewModel(checkLatest = { available }, automaticPrompt = false, ioDispatcher = StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertEquals(UpdateStatus.AVAILABLE, model.state.value.status)
            assertFalse(model.state.value.prompt)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun manualRetryCanRecoverFromOfflineFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var checks = 0
            val model = UpdateViewModel(checkLatest = {
                if (++checks == 1) throw IOException() else available
            }, automaticPrompt = true, ioDispatcher = StandardTestDispatcher(testScheduler))
            advanceUntilIdle()
            assertEquals(UpdateStatus.FAILED, model.state.value.status)
            model.check()
            advanceUntilIdle()
            assertEquals(UpdateStatus.AVAILABLE, model.state.value.status)
            assertTrue(model.state.value.prompt)
            assertEquals(2, checks)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun repeatedChecksCannotStartConcurrentRequests() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var checks = 0
            val result = CompletableDeferred<AppUpdate?>()
            val model = UpdateViewModel(checkLatest = { checks++; result.await() }, automaticPrompt = true,
                ioDispatcher = StandardTestDispatcher(testScheduler))
            runCurrent()
            model.check()
            runCurrent()
            assertEquals(1, checks)
            result.complete(available)
            advanceUntilIdle()
            assertEquals(UpdateStatus.AVAILABLE, model.state.value.status)
        } finally { Dispatchers.resetMain() }
    }

}
