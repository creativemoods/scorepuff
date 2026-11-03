// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.metadata

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import pt.creativemoods.scorepuff.data.models.StreamingProvider

/** Accept the GTI detail link captured from Prime Android's Share → More payload. No redirects. */
object PrimeShareParser {
    fun sharedTitle(text: String): String? = if (text.length > 10_000) null else
        Regex("Hey I[’']m watching (.+?)\\. Check it out now on Prime Video!").find(text)?.groupValues?.get(1)?.trim()

    fun primeId(text: String): String? {
        if (text.length > 10_000) return null
        val ids = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE).findAll(text).mapNotNull { match ->
            val uri = runCatching { URI(match.value.trimEnd('.', ',', ')', ']', ';')) }.getOrNull() ?: return@mapNotNull null
            if (uri.scheme?.lowercase(Locale.ROOT) != "https" || uri.host?.lowercase(Locale.ROOT) != "app.primevideo.com" ||
                uri.userInfo != null || uri.port != -1 || uri.path != "/detail" || uri.fragment != null) return@mapNotNull null
            val parameters = runCatching { uri.rawQuery.orEmpty().split('&').map {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            } }.getOrNull() ?: return@mapNotNull null
            val id = parameters.filter { it.first == "gti" }.singleOrNull()?.second ?: return@mapNotNull null
            id.takeIf { Regex("amzn1\\.dv\\.gti\\.[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", RegexOption.IGNORE_CASE).matches(it) }
                ?.lowercase(Locale.ROOT)
        }.toSet()
        return ids.singleOrNull()
    }
}

data class SharedTitleLink(val provider: StreamingProvider, val id: String, val sharedTitle: String? = null) {
    val providerLabel get() = if (provider == StreamingProvider.PRIME_VIDEO) "Prime Video" else "Netflix"
}

object StreamingShareParser {
    fun parse(text: String): SharedTitleLink? {
        val netflix = NetflixShareParser.netflixId(text)?.let { SharedTitleLink(StreamingProvider.NETFLIX, it) }
        val prime = PrimeShareParser.primeId(text)?.let { SharedTitleLink(StreamingProvider.PRIME_VIDEO, it, PrimeShareParser.sharedTitle(text)) }
        return listOfNotNull(netflix, prime).singleOrNull()
    }
}
