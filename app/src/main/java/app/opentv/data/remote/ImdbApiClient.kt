/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.remote

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ImdbTvMeta(val id: String, val plot: String?, val poster: String?, val genres: String?,
    val cast: String?, val rating: Double?, val year: Int?)

/** Best-effort public IMDbAPI.dev REST adapter; never requires credentials or a paid IMDb service. */
class ImdbApiClient(private val http: MetadataHttp,
    private val base: HttpUrl = "https://api.imdbapi.dev/".toHttpUrl()) {
    suspend fun series(title: String, year: Int?, imdbId: String?): ImdbTvMeta? {
        val id = imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) } ?: run {
            val results = http.get(base.newBuilder().addPathSegments("search/titles")
                .addQueryParameter("query", title).addQueryParameter("limit", "20").build())
                .obj()?.get("titles").array().mapNotNull { it.obj() }
            results.filter { it.text("type") in TV_TYPES &&
                normalizeTitle(it.text("primaryTitle").orEmpty()) == normalizeTitle(title) &&
                (year == null || it.int("startYear") == year) }.singleOrNull()?.text("id")
        } ?: return null
        val o = http.get(base.newBuilder().addPathSegments("titles/$id").build()).obj() ?: return null
        if (o.text("id") != id || o.text("type") !in TV_TYPES) return null
        return ImdbTvMeta(id, o.text("plot"), o["primaryImage"].obj()?.text("url"),
            o["genres"].array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .joinToString(", ").takeIf { it.isNotBlank() },
            o["stars"].array().mapNotNull { it.obj()?.text("displayName") }
                .joinToString(", ").takeIf { it.isNotBlank() },
            (o["rating"].obj()?.get("aggregateRating") as? JsonPrimitive)?.doubleOrNull,
            o.int("startYear"))
    }
    private companion object { val TV_TYPES = setOf("TV_SERIES", "TV_MINI_SERIES") }
}
