// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.metadata

import java.net.URI
import java.util.Locale

object NetflixShareParser {
    fun netflixId(text: String): String? {
        if (text.length > 10_000) return null
        val ids = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { match ->
            val uri = runCatching { URI(match.value.trimEnd('.', ',', ')', ']', ';')) }.getOrNull() ?: return@mapNotNull null
            if (uri.host?.lowercase(Locale.ROOT) !in setOf("netflix.com", "www.netflix.com") ||
                uri.userInfo != null || uri.port != -1) return@mapNotNull null
            // Watch links can identify an episode rather than the show's catalog card. Accept title links only.
            Regex("^/(?:[a-z]{2}(?:-[a-z]{2,4})?/)?title/([0-9]{6,10})/?$", RegexOption.IGNORE_CASE)
                .matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
        }.toSet()
        return ids.singleOrNull()
    }
}
