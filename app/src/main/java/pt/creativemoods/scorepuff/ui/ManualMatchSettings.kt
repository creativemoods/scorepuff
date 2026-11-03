// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.creativemoods.scorepuff.ratings.normalizeTitle

@Composable
fun ManualMatchSettings(model: MainViewModel) {
    val mappings by model.manualMappings.collectAsStateWithLifecycle()
    val state by model.manualMatch.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Manual match", style = MaterialTheme.typography.titleLarge)
            Text("Choose a detected title, preview its IMDb ID, then confirm the correct film or show.")
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = mappings.isNotEmpty() && !state.busy) {
                    Text(state.selected?.netflixTitle ?: "Choose title")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 240.dp)) {
                    mappings.forEach { target ->
                        DropdownMenuItem(text = {
                            Text("${if (target.lookupKey.startsWith("prime:")) "Prime Video" else "Netflix"} · ${target.netflixTitle}${target.netflixReleaseYear?.let { " ($it)" }.orEmpty()} · ${target.contentType ?: "Unknown type"}${if (target.lookupKey.startsWith("prime:video:")) " · shared ID" else ""}${if (target.matchConfidence == "manual") " · manual" else ""}")
                        }, onClick = { model.selectManualTarget(target); expanded = false })
                    }
                }
            }
            if (mappings.isEmpty()) Text("Browse Netflix or Prime Video first, then return here to choose a detected title.")
            state.selected?.let { target ->
                if (target.matchConfidence == "manual") {
                    Text("Saved: ${target.canonicalTitle} (${target.releaseYear}) · ${target.imdbId}")
                }
                OutlinedTextField(value = state.imdbId, onValueChange = model::setManualImdbId,
                    label = { Text("IMDb ID") }, placeholder = { Text("tt26581740") },
                    singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Button(onClick = model::previewManualMatch, enabled = !state.busy && state.imdbId.isNotBlank()) {
                    Text(if (state.busy) "Please wait…" else "Preview IMDb match")
                }
                state.preview?.let { preview ->
                    Text("${preview.candidate.title} (${preview.candidate.year})", style = MaterialTheme.typography.titleMedium)
                    Text("${preview.candidate.type.name} · ${preview.candidate.imdbId}")
                    preview.runtimeMinutes?.let { Text("Runtime: $it min") }
                    Text("Average ${preview.ratings.scoreLabel ?: "—"} / 10")
                    if (normalizeTitle(target.netflixTitle) != normalizeTitle(preview.candidate.title)) {
                        Text("This title differs from the streaming app’s label. Verify that it is the same film or show.")
                    }
                    Button(onClick = model::saveManualMatch, enabled = !state.busy) { Text("Use this match") }
                }
                if (target.matchConfidence == "manual") {
                    TextButton(onClick = model::removeManualMatch, enabled = !state.busy) { Text("Remove manual match") }
                }
            }
            if (state.message.isNotBlank()) Text(state.message)
        }
    }
}
