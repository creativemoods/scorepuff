// Copyright 2026 Creative Moods, Lda.
// SPDX-License-Identifier: Apache-2.0

package pt.creativemoods.scorepuff.ratings

import org.json.JSONObject
import pt.creativemoods.scorepuff.data.models.ContentType

object OmdbJson {
    fun check(json: JSONObject): Boolean {
        if (!json.optString("Response").equals("False", ignoreCase = true)) return true
        val error = json.optString("Error").lowercase()
        when {
            "api key" in error || "apikey" in error -> throw ProviderException(ProviderProblem.AUTH)
            "limit" in error -> throw ProviderException(ProviderProblem.QUOTA)
            "not found" in error || "too many results" in error -> return false
            else -> throw ProviderException(ProviderProblem.INVALID_RESPONSE)
        }
    }

    fun candidate(json: JSONObject): OmdbCandidate? {
        val id = json.optString("imdbID").takeIf { it.matches(Regex("tt[0-9]{7,10}")) } ?: return null
        val title = json.optString("Title").takeIf { it.isNotBlank() && it != "N/A" } ?: return null
        val type = when (json.optString("Type")) {
            "movie" -> ContentType.MOVIE
            "series" -> ContentType.SHOW
            else -> return null
        }
        return OmdbCandidate(id, title, type, json.optString("Year"))
    }

    fun search(json: JSONObject): OmdbSearch {
        if (json.optString("Error").contains("too many results", ignoreCase = true)) return OmdbSearch(emptyList(), 31)
        if (!check(json)) return OmdbSearch(emptyList(), 0)
        val rows = json.optJSONArray("Search") ?: throw ProviderException(ProviderProblem.INVALID_RESPONSE)
        val total = json.optString("totalResults").toIntOrNull()?.takeIf { it >= rows.length() }
            ?: throw ProviderException(ProviderProblem.INVALID_RESPONSE)
        return OmdbSearch((0 until rows.length()).mapNotNull { candidate(rows.getJSONObject(it)) }, total)
    }

    fun details(json: JSONObject): OmdbDetails {
        if (!check(json)) throw ProviderException(ProviderProblem.INVALID_RESPONSE)
        val candidate = candidate(json) ?: throw ProviderException(ProviderProblem.INVALID_RESPONSE)
        val imdb = json.optString("imdbRating").toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 }
        val scores = json.optJSONArray("Ratings")
        val rt = (0 until (scores?.length() ?: 0)).map { scores!!.getJSONObject(it) }
            .firstOrNull { it.optString("Source") == "Rotten Tomatoes" }
            ?.optString("Value")?.takeIf { it.matches(Regex("[0-9]{1,3}%")) }
            ?.removeSuffix("%")?.toIntOrNull()?.takeIf { it in 0..100 }
        val runtime = Regex("([0-9]+) min").matchEntire(json.optString("Runtime"))?.groupValues?.get(1)
            ?.toIntOrNull()?.takeIf { it > 0 }
        return OmdbDetails(candidate, RatingsResult(imdb, rt), runtime)
    }
}
