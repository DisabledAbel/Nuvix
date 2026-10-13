/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.model

import kotlinx.serialization.Serializable

/**
 * A user-added Stremio add-on: a small web service (identified by its manifest URL) that, given a
 * title, returns playable stream links. OpenTV ships with none — the user pastes the manifest URL
 * of an add-on they have configured themselves (including any debrid key, which is baked into that
 * personalised URL on the add-on's own site, never entered into OpenTV). Stored on-device only,
 * exactly like a provider credential.
 *
 * This is the same open protocol Stremio and Kodi speak; OpenTV is only a neutral client for it and
 * hosts, indexes and bundles no content of its own.
 */
@Serializable
data class StremioAddon(
    /** The manifest URL the user pasted, e.g. https://…/manifest.json. Its identity for de-dup. */
    val manifestUrl: String,
    /** Display name read from the add-on's manifest at add time. */
    val name: String,
)

/**
 * A single playable stream an add-on returned for a title. OpenTV keeps only streams that carry a
 * direct, ready-to-play [url] (what debrid-backed add-ons return); raw torrent (infoHash-only)
 * entries are dropped, because OpenTV plays URLs and deliberately does no torrenting itself.
 */
data class StremioStream(
    val url: String,
    /** A human label for the quality/source line the add-on supplied (e.g. "4K • 12GB"). */
    val title: String,
    /** Which add-on produced this, for grouping in the picker. */
    val addonName: String,
)
