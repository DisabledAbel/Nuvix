/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.player

import app.opentv.data.model.Source

/** Compare in memory only. Catalogue timestamps and cosmetic edits cannot cancel playback. */
internal fun Source.playbackConfiguration() = copy(name = "", epgUrl = null, lastCatalogSyncMillis = 0, managerRevision = 0)
