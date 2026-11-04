// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pt.creativemoods.scorepuff.R

val PuffPurple = Color(0xFF190A2B)
val PuffGold = Color(0xFFFFCC00)

@Composable
fun ScorePuffTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = PuffGold, onPrimary = Color(0xFF251039),
        secondary = Color(0xFFD9BCF2), onSecondary = Color(0xFF271139),
        background = PuffPurple, onBackground = Color(0xFFFFF8FF),
        surface = Color(0xFF2D1C3D), onSurface = Color(0xFFFFF8FF),
        surfaceVariant = Color(0xFF3B284C), onSurfaceVariant = Color(0xFFDDCFE6),
        outline = Color(0xFF9D83AE), error = Color(0xFFFFB4AB)
    ), shapes = Shapes(small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)), content = content)
}

@Composable
fun ScorePuffBrand() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(R.drawable.scorepuff_mascot), contentDescription = null,
            modifier = Modifier.size(88.dp))
        Column {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White)) { append("Score") }
                withStyle(SpanStyle(color = PuffGold)) { append("Puff") }
            }, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
            Text("Ratings that pop.", style = MaterialTheme.typography.bodyLarge,
                fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}
