// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OmdbSettingsCard(model: MainViewModel) {
    val uriHandler = LocalUriHandler.current
    val state by model.omdbKeyState.collectAsStateWithLifecycle()
    val edit by model.omdbKeyEdit.collectAsStateWithLifecycle()
    // Deliberately not saveable: a typed secret must not enter an instance-state bundle.
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(state.revision) { draft = "" }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("OMDb API key", style = MaterialTheme.typography.titleLarge)
            Text(if (state.configured) "A key is saved on this phone." else "Enter your activated OMDb key to retrieve ratings.")
            if (state.unreadable) Text("The saved key could not be read. Enter it again.")
            OutlinedTextField(value = draft, onValueChange = { draft = it },
                label = { Text(if (state.configured) "Replacement key" else "API key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrect = false),
                singleLine = true, enabled = !edit.busy, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { model.saveOmdbKey(draft) }, enabled = draft.isNotBlank() && !edit.busy) {
                    Text(if (edit.busy) "Saving…" else "Save key")
                }
                if (state.configured) TextButton(onClick = { draft = ""; model.saveOmdbKey("") }, enabled = !edit.busy) { Text("Remove key") }
            }
            Text("The key is encrypted on this phone. It is never included in the app download.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                try { uriHandler.openUri("https://www.omdbapi.com/apikey.aspx") }
                catch (_: android.content.ActivityNotFoundException) { /* No browser installed. */ }
            }) { Text("Get an OMDb API key ↗") }
            if (edit.message.isNotEmpty()) Text(edit.message)
        }
    }
}
