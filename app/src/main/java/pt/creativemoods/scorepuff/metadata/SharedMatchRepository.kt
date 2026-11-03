// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.metadata

import pt.creativemoods.scorepuff.data.cache.*
import pt.creativemoods.scorepuff.data.models.*
import pt.creativemoods.scorepuff.ratings.*

data class SharedMatchPreview(val target: TitleMapping, val details: OmdbDetails,
    val provider: StreamingProvider = StreamingProvider.NETFLIX, val listTargets: List<TitleMapping> = emptyList(),
    val observedRuntimeMinutes: Int? = null) {
    val runtimeDiffers: Boolean get() = observedRuntimeMinutes != null && details.runtimeMinutes != null &&
        kotlin.math.abs(observedRuntimeMinutes.toLong() - details.runtimeMinutes) > 2
}

/** Explicit share payload supplies identity; recent visible metadata supplies year/title/type. */
class SharedMatchRepository(
    private val cache: RatingsCache,
    private val resolver: TitleResolver,
    private val detailsSource: ImdbDetailsSource,
    private val manualMatches: ManualMatchRepository
) {
    suspend fun preview(netflixId: String, observed: NetflixTitle): SharedMatchPreview =
        preview(SharedTitleLink(StreamingProvider.NETFLIX, netflixId), observed)

    suspend fun preview(link: SharedTitleLink, observed: NetflixTitle): SharedMatchPreview {
        val prime = link.provider == StreamingProvider.PRIME_VIDEO
        val label = link.providerLabel
        require(observed.provider == link.provider) { "Open $label before sharing its title link." }
        require(if (prime) PrimeShareParser.primeId("https://app.primevideo.com/detail?gti=${link.id}") == link.id
            else link.id.matches(Regex("[0-9]{6,10}"))) { "Invalid $label title ID." }
        require(!prime || link.sharedTitle == null || normalizeTitle(link.sharedTitle) == normalizeTitle(observed.title)) {
            "The shared Prime title differs from the recent details page. Reopen its details with the year visible and share again."
        }
        val year = requireNotNull(observed.releaseYear) { "Open a $label details page with a visible year before sharing it." }
        val baseKey = if (prime) "prime:video:${link.id}" else "netflix:${link.id}"
        val existing = cache.mapping(baseKey)
        require(existing == null || normalizeTitle(existing.netflixTitle) == normalizeTitle(observed.title)) {
            "That $label ID belongs to a different cached title. Reopen its details page and share again."
        }
        require(existing?.contentType == null || observed.type == null || existing.contentType == observed.type.name) {
            "That shared $label ID has a different movie/show type."
        }
        val lookup = observed.copy(netflixId = null).lookup()
        val cached = cache.mapping(lookup.key)?.takeIf { it.status == RatingStatus.READY.name && it.imdbId != null &&
            it.netflixReleaseYear == year && it.releaseYear?.take(4)?.toIntOrNull() == year }
        val resolution = if (cached == null) resolver.resolve(lookup) else null
        val runtimeRequired = cached?.matchConfidence?.contains("runtime") == true || resolution?.runtimeDetails != null
        val imdbId = cached?.imdbId ?: resolution?.candidate?.imdbId
        require(imdbId != null) { "The displayed title and year do not resolve uniquely in OMDb." }
        val details = detailsSource.details(imdbId)
        require(details.candidate.imdbId == imdbId && normalizeTitle(details.candidate.title) == normalizeTitle(observed.title) &&
            details.candidate.year.take(4).toIntOrNull() == year &&
            (observed.type == null || observed.type == details.candidate.type)) {
            "OMDb could not verify the displayed title, year and type."
        }
        val runtimeDiffers = observed.runtimeMinutes != null && details.runtimeMinutes != null &&
            kotlin.math.abs(observed.runtimeMinutes.toLong() - details.runtimeMinutes) > 2
        require(!runtimeRequired || (observed.runtimeMinutes != null && details.runtimeMinutes != null && !runtimeDiffers)) {
            "Runtime was needed to distinguish this title, but OMDb could not verify that duration."
        }
        // Catalog editions can have different durations even for a unique title/year/type.
        // Disclose this for confirmation; do not retain that duration as identity evidence.
        val identityRuntime = observed.runtimeMinutes.takeUnless { runtimeDiffers }
        val target = TitleMapping(baseKey, if (prime) null else link.id, observed.title, observed.type?.name ?: details.candidate.type.name,
            status = RatingStatus.READY.name, lastAttemptAt = 0, retryAfter = 0, netflixRuntimeMinutes = identityRuntime)
        // Prime's captured artwork has no GTI. A separately disclosed, confirmed title
        // choice is needed for these yearless cards; never treat their title as an ID.
        val listTargets = if (prime) listOf(observed.type, null).distinct().map { type ->
            target.copy(lookupKey = observed.copy(netflixId = null, type = type, releaseYear = null).lookup().key,
                contentType = type?.name)
        } else emptyList()
        for (alias in listTargets) {
            val existingAlias = cache.mapping(alias.lookupKey)
            require(existingAlias?.matchConfidence != "manual" || existingAlias.imdbId == details.candidate.imdbId) {
                "A different manual match already exists for this Prime title. Remove it in Manual match before sharing again."
            }
        }
        return SharedMatchPreview(target, details, link.provider, listTargets, observed.runtimeMinutes)
    }

    suspend fun save(preview: SharedMatchPreview): TitleMapping {
        val saved = manualMatches.save(preview.target, preview.details)
        for (alias in preview.listTargets) manualMatches.save(alias, preview.details)
        return saved
    }
}
