// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SharedMatchDialog(model: MainViewModel) {
    val state by model.sharedMatch.collectAsStateWithLifecycle()
    if (!state.visible) return
    AlertDialog(
        onDismissRequest = { if (!state.busy) model.dismissSharedAssociation() },
        title = { Text("Save ${state.link?.providerLabel ?: "title"} association") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.link?.let { Text("Shared ${it.providerLabel} ID: ${it.id}") }
                state.preview?.let { preview ->
                    Text("${preview.details.candidate.title} (${preview.details.candidate.year})",
                        style = MaterialTheme.typography.titleMedium)
                    Text("${preview.details.candidate.type.name} · IMDb ${preview.details.candidate.imdbId}")
                    if (preview.runtimeDiffers) {
                        Text("Different runtimes: ${state.link?.providerLabel} ${preview.observedRuntimeMinutes} min; OMDb ${preview.details.runtimeMinutes} min. Title, year and type match uniquely. Confirm this is the same movie.",
                            color = MaterialTheme.colorScheme.tertiary)
                    } else preview.details.runtimeMinutes?.let { Text("Runtime: $it min") }
                    Text("Average ${preview.details.ratings.scoreLabel ?: "—"} / 10")
                    Text(if (preview.listTargets.isEmpty()) "Link this Netflix ID to the verified title above? Its list cards will use this match even when no year is shown."
                        else "Save this Prime ID and use this match for Prime cards named “${preview.target.netflixTitle}” without a year? These cards do not expose an ID, so this title choice also applies to other versions with the same name. You can change it in Manual match.")
                }
                if (state.busy) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Verifying…") }
                if (state.message.isNotBlank()) Text(state.message)
            }
        },
        confirmButton = {
            if (state.preview != null) TextButton(onClick = model::saveSharedAssociation, enabled = !state.busy) {
                Text("Save association")
            } else if (!state.busy) TextButton(onClick = model::dismissSharedAssociation) { Text("Done") }
        },
        dismissButton = {
            if (state.preview != null) TextButton(onClick = model::dismissSharedAssociation, enabled = !state.busy) { Text("Cancel") }
        }
    )
}
