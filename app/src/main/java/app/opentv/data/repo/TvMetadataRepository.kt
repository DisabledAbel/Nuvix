/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.data.repo

import app.opentv.data.model.Episode
import app.opentv.data.model.Series
import app.opentv.data.remote.*

/** External metadata is never written over provider rows or used for playback/cloud identities. */
data class TvSeriesDetail(val series: Series, val tvmaze: TvmazeDetails?,
    val imdb: ImdbTvMeta?, val tmdbRating: Double?)

class TvMetadataRepository(private val tvmaze: TvmazeClient, private val imdb: ImdbApiClient) {
    suspend fun detail(provider: Series, title: String, imdbId: String?,
        fallback: suspend () -> TmdbMeta?): TvSeriesDetail {
        val suffix = Regex("""\s+\(?(19\d{2}|20\d{2})\)?$""").find(title)
        val query = suffix?.let { title.substring(0, it.range.first).trim() } ?: title
        val year = provider.year ?: suffix?.groupValues?.get(1)?.toIntOrNull()
        var show = optional { tvmaze.match(query, year, imdbId) }
        val secondary = optional { imdb.series(query, year, show?.imdbId ?: imdbId) }
        // An IMDb title search can disambiguate a title that TVmaze did not match by name.
        if (show == null && imdbId == null && secondary != null) {
            show = optional { tvmaze.match("", year, secondary.id) }
        }
        val primary = show?.let { optional { tvmaze.details(it) } }
        val partial = mergeTvMetadata(provider, primary, secondary, null)
        val last = if (partial.posterUrl.isNullOrBlank() || partial.backdropUrl.isNullOrBlank() ||
            partial.plot.isNullOrBlank() || partial.cast.isNullOrBlank() || partial.genre.isNullOrBlank()) optional { fallback() } else null
        return TvSeriesDetail(mergeTvMetadata(provider, primary, secondary, last), primary, secondary, last?.rating)
    }
    suspend fun profile(id: Int) = optional { tvmaze.profile(id) }

    private suspend fun <T> optional(block: suspend () -> T?): T? = try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
}

internal fun mergeTvMetadata(provider: Series, tvmaze: TvmazeDetails?, imdb: ImdbTvMeta?, tmdb: TmdbMeta?): Series {
    fun first(vararg values: String?) = values.firstOrNull { !it.isNullOrBlank() }
    return provider.copy(
        plot = first(tvmaze?.show?.summary, imdb?.plot, provider.plot, tmdb?.overview),
        posterUrl = first(tvmaze?.show?.poster, imdb?.poster, provider.posterUrl, tmdb?.posterUrl),
        backdropUrl = first(tvmaze?.background, provider.backdropUrl, tmdb?.backdropUrl),
        genre = first(tvmaze?.show?.genres, imdb?.genres, provider.genre, tmdb?.genre),
        cast = first(tvmaze?.cast?.map { it.person.name }?.joinToString(", "), imdb?.cast, provider.cast, tmdb?.cast),
        // Provider rating/year/IDs stay authoritative; external ratings are displayed separately.
    )
}

data class TvEpisodeListing(val provider: Episode?, val metadata: TvEpisode?) {
    val key get() = provider?.let { "provider:${it.id}" } ?: "tvmaze:${metadata!!.id}"
    val season get() = metadata?.season ?: provider!!.season
    val number get() = metadata?.number ?: provider?.episodeNumber
    val title get() = metadata?.title?.takeIf { it.isNotBlank() } ?: provider?.title.orEmpty()
    val plot get() = metadata?.summary?.takeIf { it.isNotBlank() } ?: provider?.plot
    val image get() = metadata?.image ?: provider?.stillUrl
}

/** Only unique, numbered season/episode pairs can attach metadata to a provider stream. */
fun tvEpisodeListings(provider: List<Episode>, metadata: List<TvEpisode>): List<TvEpisodeListing> {
    val byNumber = metadata.filter { it.number != null }.groupBy { it.season to it.number }
    val used = mutableSetOf<Int>()
    val rows = provider.map { ep ->
        val match = byNumber[ep.season to ep.episodeNumber]?.singleOrNull()
        match?.let { used.add(it.id) }
        TvEpisodeListing(ep, match)
    } + metadata.filter { it.id !in used }.map { TvEpisodeListing(null, it) }
    return rows.sortedWith(compareBy<TvEpisodeListing> { it.season }.thenBy { it.number ?: Int.MAX_VALUE })
}
