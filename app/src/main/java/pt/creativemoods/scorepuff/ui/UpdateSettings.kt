// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.creativemoods.scorepuff.BuildConfig
import pt.creativemoods.scorepuff.updates.UpdateStatus
import pt.creativemoods.scorepuff.updates.UpdateViewModel

@Composable
fun UpdateSettings(model: UpdateViewModel, sharing: Boolean, openUrl: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val update = state.available
    fun download() {
        update ?: return
        model.dismiss()
        openUrl(if (BuildConfig.DEBUG) update.releaseUrl else update.downloadUrl)
    }
    if (state.prompt && update != null && !sharing) {
        AlertDialog(onDismissRequest = model::dismiss,
            title = { Text("Update available") },
            text = { Text("ScorePuff ${update.versionName} is available. You have ${BuildConfig.VERSION_NAME}. " +
                "Download the APK, then open it to install. Android will ask you to confirm installation.") },
            confirmButton = { TextButton(onClick = ::download) { Text("Download update") } },
            dismissButton = { TextButton(onClick = model::dismiss) { Text("Later") } })
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App updates", style = MaterialTheme.typography.titleMedium)
            Text("Installed: ${BuildConfig.VERSION_NAME}")
            when (state.status) {
                UpdateStatus.CHECKING -> Text("Checking GitHub for updates…")
                UpdateStatus.CURRENT -> Text("No newer release is available.")
                UpdateStatus.FAILED -> Text("Couldn’t check for updates. Please try again later.")
                UpdateStatus.AVAILABLE -> {
                    Text("ScorePuff ${update?.versionName} is available.")
                    if (BuildConfig.DEBUG) Text("This development build cannot be updated with a release APK.")
                    else Text("Your browser downloads the APK. Open it to install; Android may ask you to allow installs from your browser.")
                    Button(onClick = ::download) { Text(if (BuildConfig.DEBUG) "View release" else "Download update") }
                }
            }
            if (state.openFailed) Text("No browser could open the download. Install a browser and try again.")
            TextButton(onClick = model::check, enabled = state.status != UpdateStatus.CHECKING) { Text("Check for updates") }
        }
    }
}
