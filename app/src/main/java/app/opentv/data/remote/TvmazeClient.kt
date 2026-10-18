/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.remote

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** TVmaze show identity and display metadata, separate from provider catalog rows. */
// These are display/cache models, deliberately separate from provider Room entities and cloud IDs.
data class TvShow(
    val id: Int, val name: String, val imdbId: String?, val year: Int?,
    val summary: String?, val genres: String?, val poster: String?, val rating: Double?,
) {
    val url get() = "https://www.tvmaze.com/shows/$id"
}
/** TVmaze season numbering, dates, descriptive text and artwork. */
data class TvSeason(val number: Int, val name: String?, val summary: String?,
    val premiereDate: String?, val endDate: String?, val image: String?, val episodeOrder: Int?)
/** TVmaze episode identity, numbering, airdate, runtime and display metadata. */
data class TvEpisode(val id: Int, val season: Int, val number: Int?, val title: String,
    val summary: String?, val airdate: String?, val image: String?, val runtime: Int?)
/** TVmaze person identity and biographical metadata used by profile navigation. */
data class TvPerson(val id: Int, val name: String, val image: String?, val birthday: String?,
    val deathday: String?, val country: String?) {
    val route get() = "tvmaze:$id:$name"
    val url get() = "https://www.tvmaze.com/people/$id"
}
/** Person and character information for a show's cast credit. */
data class TvCast(val person: TvPerson, val character: String?, val characterImage: String?)
/** Person and job information for a show's crew credit. */
data class TvCrew(val person: TvPerson, val job: String?)
/** Show and role attached to a person's cast or crew credit. */
data class TvCredit(val show: TvShow, val role: String?)
/** Person metadata with deduplicated show credits. */
data class TvProfile(val person: TvPerson, val credits: List<TvCredit>)
/** Show metadata assembled with artwork, seasons, episodes, cast and crew. */
data class TvmazeDetails(val show: TvShow, val background: String?, val seasons: List<TvSeason>,
    val episodes: List<TvEpisode>, val cast: List<TvCast>, val crew: List<TvCrew>)

/** Reads TVmaze metadata without changing provider identities or playback data. */
class TvmazeClient(private val http: MetadataHttp,
    private val base: HttpUrl = "https://api.tvmaze.com/".toHttpUrl()) {
    /** Fetches a TVmaze endpoint with safely encoded query parameters through the metadata cache. */
    private suspend fun get(path: String, vararg params: Pair<String, String>): JsonElement? {
        val url = base.newBuilder().addPathSegments(path)
        params.forEach { (key, value) -> url.addQueryParameter(key, value) }
        return http.get(url.build())
    }

    /** Matches by IMDb ID or an unambiguous normalized title and optional year; ambiguity returns null. */
    suspend fun match(title: String, year: Int?, imdbId: String?): TvShow? {
        val imdb = imdbId?.takeIf { it.matches(Regex("tt[0-9]+")) }
        if (imdb != null) {
            parseShow(get("lookup/shows", "imdb" to imdb))?.let {
                if (it.imdbId == null || it.imdbId == imdb) return it
            }
        }
        if (title.isBlank()) return null
        val candidates = get("search/shows", "q" to title).array().mapNotNull {
            parseShow(it.obj()?.get("show"))
        }.filter { normalizeTitle(it.name) == normalizeTitle(title) &&
            (year == null || it.year == year) && (imdb == null || it.imdbId == null || it.imdbId == imdb) }
        // Remakes without a year are ambiguous; leave provider metadata intact rather than guess.
        return candidates.singleOrNull()
    }

    /** Fetches show artwork, seasons, episodes and credits, deduplicating episode and person entries. */
    suspend fun details(show: TvShow): TvmazeDetails {
        val images = get("shows/${show.id}/images").array().mapNotNull { it.obj() }
        val background = images.filter { it.text("type") == "background" }
            .sortedByDescending { (it["main"] as? JsonPrimitive)?.booleanOrNull == true }
            .firstNotNullOfOrNull { it["resolutions"].obj()?.get("original").obj()?.text("url") }
        val seasons = get("shows/${show.id}/seasons").array().mapNotNull { element ->
            val o = element.obj() ?: return@mapNotNull null
            TvSeason(o.int("number") ?: return@mapNotNull null, o.text("name"), o.text("summary"),
                o.text("premiereDate"), o.text("endDate"), o.image(), o.int("episodeOrder"))
        }
        val episodes = get("shows/${show.id}/episodes", "specials" to "1").array().mapNotNull { element ->
            val o = element.obj() ?: return@mapNotNull null
            TvEpisode(o.int("id") ?: return@mapNotNull null, o.int("season") ?: 0,
                o.int("number"), o.text("name") ?: "", o.text("summary"), o.text("airdate"),
                o.image(), o.int("runtime"))
        }
        val cast = get("shows/${show.id}/cast").array().mapNotNull { element ->
            val o = element.obj() ?: return@mapNotNull null
            val person = parsePerson(o["person"]) ?: return@mapNotNull null
            TvCast(person, o["character"].obj()?.text("name"), o["character"].obj()?.image())
        }
        val crew = get("shows/${show.id}/crew").array().mapNotNull { element ->
            val o = element.obj() ?: return@mapNotNull null
            TvCrew(parsePerson(o["person"]) ?: return@mapNotNull null, o.text("type"))
        }
        return TvmazeDetails(show, background, seasons, episodes.distinctBy { it.id },
            cast.distinctBy { it.person.id to it.character }, crew.distinctBy { it.person.id to it.job })
    }

    /** Loads a valid person ID and combines cast and crew credits, or returns null when absent. */
    suspend fun profile(id: Int): TvProfile? {
        if (id <= 0) return null
        val person = parsePerson(get("people/$id")) ?: return null
        val cast = get("people/$id/castcredits", "embed[]" to "show", "embed[]" to "character")
            .array().mapNotNull { element ->
                val embedded = element.obj()?.get("_embedded").obj() ?: return@mapNotNull null
                TvCredit(parseShow(embedded["show"]) ?: return@mapNotNull null,
                    embedded["character"].obj()?.text("name"))
            }
        val crew = get("people/$id/crewcredits", "embed" to "show").array().mapNotNull { element ->
            val o = element.obj() ?: return@mapNotNull null
            TvCredit(parseShow(o["_embedded"].obj()?.get("show")) ?: return@mapNotNull null, o.text("type"))
        }
        return TvProfile(person, (cast + crew).distinctBy { it.show.id to it.role })
    }
}

/** Normalizes a title to lowercase letters and digits for exact metadata matching. */
internal fun normalizeTitle(title: String) = title.lowercase(java.util.Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]"), "")
/** Returns this JSON value as an object, or null for a different shape. */
internal fun JsonElement?.obj() = this as? JsonObject
/** Returns this JSON value as an array, using an empty list for other shapes. */
internal fun JsonElement?.array() = (this as? JsonArray).orEmpty()
/** Reads a nonblank primitive string field, or null when unavailable. */
internal fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
/** Reads an integer primitive field, or null when unavailable or malformed. */
internal fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
/** Prefers the original image URL and falls back to the medium image. */
internal fun JsonObject.image() = this["image"].obj()?.let { it.text("original") ?: it.text("medium") }
/** Parses a show with a required ID and name, or returns null when those fields are missing. */
internal fun parseShow(element: JsonElement?): TvShow? {
    val o = element.obj() ?: return null
    return TvShow(o.int("id") ?: return null, o.text("name") ?: return null,
        o["externals"].obj()?.text("imdb"), o.text("premiered")?.take(4)?.toIntOrNull(),
        o.text("summary"), o["genres"].array().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .joinToString(", ").takeIf { it.isNotBlank() }, o.image(),
        (o["rating"].obj()?.get("average") as? JsonPrimitive)?.doubleOrNull)
}
/** Parses a person with a required ID and name, or returns null when those fields are missing. */
internal fun parsePerson(element: JsonElement?): TvPerson? {
    val o = element.obj() ?: return null
    return TvPerson(o.int("id") ?: return null, o.text("name") ?: return null, o.image(),
        o.text("birthday"), o.text("deathday"), o["country"].obj()?.text("name"))
}
