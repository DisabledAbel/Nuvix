/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.core

/** The full guide's row presentation; independent of the existing GRID/LIST channel list. */
enum class GuideLayout { STANDARD, COMPACT, EXPANDED }

/** Horizontal timeline scale. Wider cells give long programme titles more room. */
enum class GuideTimelineDensity { COMFORTABLE, DENSE, WIDE }

/** Portable filter choice. Selected provider/group database IDs always remain local. */
enum class GuideChannelFilter { ALL, FAVOURITES, RECENT, GROUP, PROVIDER, WITH_EPG, AIRING }

/** Ordering overlays the existing channel organization without changing provider identifiers. */
enum class GuideChannelSort { PROVIDER_ORDER, CHANNEL_NUMBER, ALPHABETICAL, FAVOURITES_FIRST, CUSTOM }

/** Local navigation state, deliberately excluded from portable preferences and playback state. */
data class GuidePosition(val channelId: Long, val timeMs: Long)
