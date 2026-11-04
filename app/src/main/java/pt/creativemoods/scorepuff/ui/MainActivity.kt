// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.creativemoods.scorepuff.R
import pt.creativemoods.scorepuff.data.DiagnosticsRepository

class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val updates: pt.creativemoods.scorepuff.updates.UpdateViewModel by viewModels()
    override fun onResume() { super.onResume(); model.refreshEnabled() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.rgb(25, 10, 43)
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.rgb(17, 7, 29)
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) model.receiveShare(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
        setContent {
            val diagnostics by model.diagnostics.collectAsStateWithLifecycle()
            val enabled by model.enabled.collectAsStateWithLifecycle()
            val sharedMatch by model.sharedMatch.collectAsStateWithLifecycle()
            val omdbKeyState by model.omdbKeyState.collectAsStateWithLifecycle()
            ScorePuffTheme {
                SharedMatchDialog(model)
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF280B40), PuffPurple, Color(0xFF11071D))))
                    .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Settings", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground)
                    ScorePuffBrand()
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Accessibility service", Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium)
                                Surface(color = if (enabled) Color(0xFF357B44) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50)) {
                                    Text(if (enabled) "Enabled" else "Disabled", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium, color = Color.White)
                                }
                            }
                            Text(if (diagnostics.connected) "Service connected" else "Service not connected")
                            Text(diagnostics.overlayStatus)
                            Text("${diagnostics.detectedCount} visible titles · ${diagnostics.badgeCount} badges")
                        }
                    }
                    OmdbSettingsCard(model)
                    Text("This service reads Netflix and Prime Video’s on-screen titles and poster positions. It sends recognized titles, displayed release year and movie/show type to OMDb over HTTPS to find IMDb IDs and retrieve ratings. Results are cached on this phone. It does not capture video or collect unrelated accessibility content.",
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("Badges show one score out of 10: the equal average of IMDb and Rotten Tomatoes (RT percentage divided by 10). Missing sources are excluded. A white spinner means loading; an amber circled question mark means the title is ambiguous. Titles with no available scores stay blank. You can pause lookups and badges below or disable the service in Android settings.",
                        color = MaterialTheme.colorScheme.onBackground)
                    Button(onClick = { openAccessibilitySettings() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Open Accessibility Settings")
                    }
                    Text("Under Installed apps (or Downloaded apps), choose ScorePuff and enable it. Then open Netflix or Prime Video and browse movie or show artwork.",
                        color = MaterialTheme.colorScheme.onBackground)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("Overlay settings", style = MaterialTheme.typography.titleMedium)
                            Text("Netflix and Prime Video", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Show ratings", Modifier.weight(1f))
                                Switch(diagnostics.overlayAllowed, onCheckedChange = model::setOverlay)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Debug title details", Modifier.weight(1f))
                                Switch(diagnostics.debug, onCheckedChange = model::setDebug)
                            }
                        }
                    }
                    TextButton(onClick = {
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.website_url)))) }
                        catch (_: ActivityNotFoundException) { /* No browser is installed. */ }
                    }) { Text(getString(R.string.website_label)) }
                    if (pt.creativemoods.scorepuff.BuildConfig.DEBUG) {
                        OutlinedButton(onClick = {
                            startActivity(Intent().setClassName(this@MainActivity,
                                "pt.creativemoods.scorepuff.ui.MockCatalogActivity"))
                        }) { Text("Open rating playground") }
                    }
                    ManualMatchSettings(model)
                    Text("Diagnostics", style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground)
                    Text("Scans: ${diagnostics.scanCount}\nLast scan: ${diagnostics.lastScan}\nStandard posters and supported Continue Watching cards. Badges hide during playback or landscape.",
                        color = MaterialTheme.colorScheme.onBackground)
                    Text(if (omdbKeyState.configured) "OMDb key configured" else "OMDb key missing · add it in settings",
                        color = MaterialTheme.colorScheme.primary)
                    Text(diagnostics.ratingsSummary, color = MaterialTheme.colorScheme.onBackground)
                    if (diagnostics.debug) {
                        Text(diagnostics.lookupDetails.joinToString("\n").ifEmpty { "No rating lookups yet" },
                            color = MaterialTheme.colorScheme.onBackground)
                        Text("Last detected streaming titles (memory only)", color = MaterialTheme.colorScheme.primary)
                        Text(if (diagnostics.lastTitles.isEmpty()) "No titles captured. Leave debug on and open Netflix or Prime Video." else
                            diagnostics.lastTitles.joinToString("\n\n") {
                                "${it.title}${it.releaseYear?.let { year -> " ($year)" }.orEmpty()} · ${it.type?.name ?: "Type unavailable"}\nID ${it.netflixId ?: "Not exposed"}\n[${it.bounds.left},${it.bounds.top}][${it.bounds.right},${it.bounds.bottom}]"
                            }, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground)
                    }
                    Text("Recent errors", color = MaterialTheme.colorScheme.primary)
                    Text(diagnostics.errors.joinToString("\n").ifEmpty { "None in this process" },
                        color = MaterialTheme.colorScheme.onBackground)
                    UpdateSettings(updates, sharing = sharedMatch.visible) { url ->
                        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        catch (_: ActivityNotFoundException) { updates.openFailed() }
                    }
                    Text("ScorePuff · Version ${pt.creativemoods.scorepuff.BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Ratings supplied by OMDb (omdbapi.com), CC BY-NC 4.0. Not affiliated with Netflix, Amazon, IMDb, or Rotten Tomatoes.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND) model.receiveShare(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
    }

    private fun openAccessibilitySettings() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (failure: ActivityNotFoundException) { DiagnosticsRepository.error("NRSettings", failure) }
    }
}
