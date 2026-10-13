/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.data.model.StremioStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * A thin, read-only client for the open Stremio add-on protocol — the same protocol Stremio and
 * many Kodi add-ons speak. OpenTV ships with no add-ons and hosts no content; this only talks to
 * manifest URLs the user has pasted in themselves.
 *
 * ## What it does
 * - [fetchManifest] fetches an add-on's `manifest.json` and returns its display name if it looks
 *   like a real add-on (has an id + name), so the settings screen can validate a pasted URL.
 * - [streams] asks an add-on for the streams it offers for a title and returns only the ones with a
 *   direct playable URL. Debrid-backed add-ons resolve a title to such a URL internally (using the
 *   debrid key baked into the user's personalised manifest URL), so OpenTV never handles a debrid
 *   key and never touches a torrent — it just plays the URL the add-on hands back.
 *
 * ## Blocking on purpose
 * Methods block on the calling thread; callers already run them inside `withContext(Dispatchers.IO)`,
 * matching [TmdbClient].
 */
class StremioClient(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    /**
     * Fetch and lightly validate an add-on manifest. Returns the add-on's display name, or null if
     * the URL doesn't resolve to something that looks like a Stremio manifest (id + name present).
     */
    fun fetchManifest(manifestUrl: String): String? {
        val url = manifestUrl.trim().toHttpUrlOrNull() ?: return null
        val o = get(url)?.jsonObject ?: return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val hasId = o["id"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
        return if (hasId) name else null
    }

    /**
     * The streams [addonManifestUrl] offers for a title. [type] is "movie" or "series"; [id] is the
     * IMDb id — `tt1234567` for a film, `tt1234567:1:3` for series season 1 episode 3. Only entries
     * with a direct `url` are returned (see the class note on why torrents are skipped).
     */
    fun streams(addonManifestUrl: String, addonName: String, type: String, id: String): List<StremioStream> {
        // A manifest URL is "<base>/manifest.json"; resource URLs hang off the same base.
        val base = addonManifestUrl.trim().removeSuffix("manifest.json").trimEnd('/')
        val url = "$base/stream/$type/$id.json".toHttpUrlOrNull() ?: return emptyList()
        val streams = get(url)?.jsonObject?.get("streams")?.jsonArray ?: return emptyList()
        return streams.mapNotNull { element ->
            val s = element.jsonObject
            val streamUrl = s["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null // infoHash-only (raw torrent) — skipped on purpose
            val label = (s["title"] ?: s["name"])?.jsonPrimitive?.contentOrNull
                ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotBlank() } ?: "Stream"
            StremioStream(url = streamUrl, title = label, addonName = addonName)
        }
    }

    private fun get(url: HttpUrl): JsonElement? {
        val request = Request.Builder().url(url).header("User-Agent", "OpenTV").build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                json.parseToJsonElement(body)
            }
        }.onFailure { Log.w(TAG, "Stremio request failed", it) }.getOrNull()
    }

    private companion object {
        const val TAG = "StremioClient"
    }
}
