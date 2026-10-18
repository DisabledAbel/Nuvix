/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import app.opentv.data.model.*

internal const val SPORTS_NOW = 1_800_000_000_000L
/** Builds a synthetic sports guide program with configurable identity, title and start time. */
internal fun sportsProgramme(id: Long = 1, feed: Long = 1, epg: String = "sports-1", start: Long = SPORTS_NOW + 60_000,
    title: String = "NHL: New York Rangers vs Boston Bruins", category: String? = "Hockey") =
    Programme(id = id, feedId = feed, epgChannelId = epg, startUtcMillis = start, endUtcMillis = start + 3 * 60 * 60_000,
        title = title, category = category)
/** Builds a synthetic playable sports channel with source-scoped identity. */
internal fun sportsChannel(id: Long = 1, source: Long = 1, epg: String = "sports-1", stream: String = "$id") =
    Channel(id = id, sourceId = source, streamId = stream, name = "Sports channel $id", groupKey = "same-logical-channel",
        categoryId = "sports", logoUrl = null, epgChannelId = epg, number = id.toInt(), streamUrl = "https://example.invalid/$id")
/** Builds a sports stream fixture pairing a channel with program evidence and match confidence. */
internal fun sportsStream(channel: Channel = sportsChannel(), confidence: MatchConfidence = MatchConfidence.CONFIRMED,
    programme: Programme = sportsProgramme()) = SportsStream(channel, SportsProgramme(programmeKey(programme),
        SportsMatching.inferred(programme, SPORTS_NOW)!!.id, programme.feedId, programme.epgChannelId,
        programme.startUtcMillis, programme.endUtcMillis, programme.title, confidence.name), "Provider ${channel.sourceId}", confidence)
/** Builds a synthetic externally verified sports event with stable source identity. */
internal fun verifiedEvent(source: String = "fixture", sourceId: String = "event-42"): SportsEvent =
    SportsMatching.inferred(sportsProgramme(), SPORTS_NOW)!!.copy(id = "$source:${sportsHash(sourceId)}", source = source,
        sourceEventId = sourceId, verified = true)
