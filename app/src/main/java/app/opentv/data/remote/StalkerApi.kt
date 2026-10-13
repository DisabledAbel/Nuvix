/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.remote

import android.util.Log
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.Source
import app.opentv.data.model.StreamKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * A client for the Stalker / Ministra portal protocol (the "MAG box" middleware many panels speak).
 *
 * ## How it differs from Xtream
 * There is no username/password. A box identifies itself by a **MAC address** and does a **handshake**
 * that returns a short-lived **token**; every later call carries that token as a bearer. A channel's
 * real stream URL is not stable — the catalogue gives a `cmd` string, and the playable URL is minted
 * on demand by [createLink] at tune time. So [liveChannels] stores each channel's `cmd`; the playback
 * path resolves it just-in-time (see [app.opentv.data.repo.CatalogRepository.resolvePlaybackUrl]).
 *
 * ## Portal-path and MAC quirks
 * Portals expose the API under different paths (`/portal.php`, `/server/load.php`, `/stalker_portal/...`);
 * [endpoints] tries the common ones and the first that hands back a token wins, cached per source. The
 * MAC goes in a Cookie, URL-encoded, alongside a MAG-style STB User-Agent — the shape real boxes send.
 *
 * Blocking OkHttp calls wrapped in `withContext(Dispatchers.IO)`, matching [XtreamApi].
 */
class StalkerApi(
    private val http: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {

    class StalkerException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class Session(val token: String, val endpoint: HttpUrl, val expiresAt: Long)

    /** One live token per source, so zapping doesn't re-handshake on every channel. */
    private val sessions = ConcurrentHashMap<Long, Session>()

    /** Add-source test: proves the portal + MAC produce a token. Throws [StalkerException] if not. */
    suspend fun handshakeTest(source: Source) = withContext(Dispatchers.IO) {
        session(source, force = true)
        Unit
    }

    /** Live categories ("genres") for the guide's rail. */
    suspend fun liveCategories(source: Source): List<Category> = withContext(Dispatchers.IO) {
        val arr = callRetrying(source, type = "itv", action = "get_genres") as? JsonArray ?: return@withContext emptyList()
        arr.mapIndexedNotNull { index, element ->
            val o = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.str("id") ?: return@mapIndexedNotNull null
            Category(
                id = id,
                sourceId = source.id,
                name = o.str("title") ?: id,
                kind = StreamKind.LIVE,
                sortIndex = index,
            )
        }
    }

    /** Every live channel. Each carries its `cmd`; the real URL is minted by [createLink] at play time. */
    suspend fun liveChannels(source: Source): List<Channel> = withContext(Dispatchers.IO) {
        val s = session(source)
        val js = callRetrying(source, type = "itv", action = "get_all_channels")
        // get_all_channels returns { js: { data: [ ... ] } }; some portals return the array directly.
        val data = when (js) {
            is JsonArray -> js
            is JsonObject -> js["data"] as? JsonArray ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        data.mapIndexedNotNull { index, element ->
            val o = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = o.str("id") ?: return@mapIndexedNotNull null
            val name = o.str("name") ?: return@mapIndexedNotNull null
            val number = o.str("number")?.toIntOrNull()
            Channel(
                sourceId = source.id,
                streamId = id,
                name = name,
                categoryId = o.str("tv_genre_id"),
                logoUrl = o.str("logo")?.takeIf { it.isNotBlank() }?.let { absoluteLogo(s.endpoint, it) },
                epgChannelId = o.str("xmltv_id")?.takeIf { it.isNotBlank() },
                number = number,
                // Never played directly — a marker so nothing mistakes it for a real URL; [cmd] is
                // what gets resolved. Distinct per channel so de-dup/quality-grouping still works.
                streamUrl = "stalker://${source.id}/$id",
                cmd = o.str("cmd"),
                sortIndex = number ?: index,
            )
        }
    }

    /** Mint the real, short-lived stream URL for a channel's [cmd]. Null if the portal declines. */
    suspend fun createLink(source: Source, cmd: String): String? = withContext(Dispatchers.IO) {
        val js = callRetrying(source, type = "itv", action = "create_link") { b ->
            b.addQueryParameter("cmd", cmd)
            b.addQueryParameter("forced_storage", "0")
            b.addQueryParameter("disable_ad", "0")
        }
        val linkCmd = (js as? JsonObject)?.str("cmd") ?: return@withContext null
        stripCmdPrefix(linkCmd)
    }

    // ---- Session / handshake -----------------------------------------------------------------

    private fun session(source: Source, force: Boolean = false): Session {
        val now = System.currentTimeMillis()
        if (!force) sessions[source.id]?.let { if (it.expiresAt > now) return it }
        val mac = source.macAddress?.trim().orEmpty()
        if (mac.isEmpty()) throw StalkerException("This portal needs a MAC address (e.g. 00:1A:79:xx:xx:xx).")
        var lastError: Throwable? = null
        for (endpoint in endpoints(source)) {
            val hs = runCatching { handshake(source, endpoint) }
                .onFailure { lastError = it }
                .getOrNull()
            if (hs != null) {
                // Many portals won't serve itv data until get_profile is called with a full MAG
                // identity (device_id/signature) — that's what unlocks the ones that authenticate the
                // box rather than just accepting any MAC.
                runCatching { getProfile(source, endpoint, hs.token, hs.random) }
                return Session(hs.token, endpoint, now + TOKEN_TTL_MILLIS).also { sessions[source.id] = it }
            }
        }
        throw StalkerException(
            "The portal didn't accept this MAC address, or the URL is wrong.",
            lastError,
        )
    }

    private data class Handshake(val token: String, val random: String)

    private fun handshake(source: Source, endpoint: HttpUrl): Handshake? {
        val url = endpoint.newBuilder()
            .addQueryParameter("type", "stb")
            .addQueryParameter("action", "handshake")
            .addQueryParameter("token", "")
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build()
        val js = (execute(source, url, token = null) as? JsonObject)?.obj("js") ?: return null
        val token = js.str("token")?.takeIf { it.isNotBlank() } ?: return null
        // Some Ministra versions return a `random` at handshake that the box folds into the
        // get_profile signature; capture it so we can.
        return Handshake(token, js.str("random").orEmpty())
    }

    /**
     * MAG-box emulation. A lot of real Stalker/Ministra portals won't hand over channels to a client
     * that just presents a MAC — they expect the box's full identity: a serial, a `device_id` and a
     * `signature`, all derived from the MAC, plus a `metrics` blob. The apps that "just work" against
     * those portals send exactly this; sending only the MAC is the usual reason a portal that plays
     * elsewhere returns nothing here. Everything is derived deterministically from the MAC so it's
     * stable across sessions, and portals that don't check any of it simply ignore it.
     */
    private fun getProfile(source: Source, endpoint: HttpUrl, token: String, random: String) {
        val mac = source.macAddress?.trim().orEmpty()
        val id = stbIdentity(mac)
        val metrics = "{\"mac\":\"$mac\",\"sn\":\"${id.sn}\",\"model\":\"MAG250\",\"type\":\"STB\"," +
            "\"uid\":\"${id.deviceId}\",\"random\":\"$random\"}"
        val url = endpoint.newBuilder()
            .addQueryParameter("type", "stb")
            .addQueryParameter("action", "get_profile")
            .addQueryParameter("hd", "1")
            .addQueryParameter("ver", STB_VER)
            .addQueryParameter("num_banks", "2")
            .addQueryParameter("sn", id.sn)
            .addQueryParameter("stb_type", "MAG250")
            .addQueryParameter("client_type", "STB")
            .addQueryParameter("image_version", "218")
            .addQueryParameter("video_out", "hdmi")
            .addQueryParameter("device_id", id.deviceId)
            .addQueryParameter("device_id2", id.deviceId2)
            .addQueryParameter("signature", sha256Upper(id.sn + mac + random))
            .addQueryParameter("auth_second_step", "0")
            .addQueryParameter("hw_version", "1.7-BD-00")
            .addQueryParameter("hw_version_2", sha1Upper(mac))
            .addQueryParameter("not_valid_token", "0")
            .addQueryParameter("api_signature", "262")
            .addQueryParameter("metrics", metrics)
            .addQueryParameter("timestamp", (System.currentTimeMillis() / 1000).toString())
            .addQueryParameter("prehash", "")
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build()
        execute(source, url, token)
    }

    private data class StbIdentity(val sn: String, val deviceId: String, val deviceId2: String)

    /** The MAG identity a portal expects, derived deterministically from the MAC. */
    private fun stbIdentity(mac: String): StbIdentity {
        val deviceId = sha256Upper(mac.uppercase())
        return StbIdentity(sn = md5Upper(mac).take(13), deviceId = deviceId, deviceId2 = deviceId)
    }

    private fun sha256Upper(s: String): String = hashUpper("SHA-256", s)
    private fun sha1Upper(s: String): String = hashUpper("SHA-1", s)
    private fun md5Upper(s: String): String = hashUpper("MD5", s)
    private fun hashUpper(algo: String, s: String): String =
        java.security.MessageDigest.getInstance(algo).digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.uppercase()

    // ---- HTTP --------------------------------------------------------------------------------

    private fun call(
        session: Session,
        source: Source,
        type: String,
        action: String,
        extra: (HttpUrl.Builder) -> Unit = {},
    ): JsonElement? {
        val builder = session.endpoint.newBuilder()
            .addQueryParameter("type", type)
            .addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml")
        extra(builder)
        val body = execute(source, builder.build(), session.token) ?: return null
        return (body as? JsonObject)?.get("js")
    }

    /**
     * An itv call that survives a dropped session. Real portals hand out short-lived tokens and now
     * and then refuse a call mid-session (server load, a token invalidated early); the first attempt
     * then comes back null. Re-handshake once for a fresh token and try again, so an intermittent
     * "sometimes just rejected" becomes a silent retry instead of an empty channel list or a channel
     * that won't tune.
     */
    private fun callRetrying(
        source: Source,
        type: String,
        action: String,
        extra: (HttpUrl.Builder) -> Unit = {},
    ): JsonElement? {
        call(session(source), source, type, action, extra)?.let { return it }
        return call(session(source, force = true), source, type, action, extra)
    }

    private fun execute(source: Source, url: HttpUrl, token: String?): JsonElement? {
        val mac = source.macAddress?.trim().orEmpty()
        // adid (the lower-case device id) rides in the cookie the way a real box sends it; some
        // portals key off it alongside the MAC.
        val cookie = buildString {
            append("mac=").append(URLEncoder.encode(mac, "UTF-8"))
            append("; stb_lang=en; timezone=Europe/London")
            append("; adid=").append(stbIdentity(mac).deviceId2.lowercase())
            // Some Ministra backends read the token from the cookie, not the Authorization header —
            // send it in both so those portals recognise the session and return the channel list.
            if (token != null) append("; token=").append(token)
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", stbUserAgent(source))
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Referer", "${url.scheme}://${url.host}:${url.port}/c/")
            .header("Cookie", cookie)
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) return null
                json.parseToJsonElement(text)
            }
        }.onFailure { Log.w(TAG, "Stalker request failed", it) }.getOrNull()
    }

    /** Candidate API endpoints for a portal URL, most common first. First that handshakes wins. */
    private fun endpoints(source: Source): List<HttpUrl> {
        val raw = source.url.trim().trimEnd('/')
        // A portal URL is often given as ".../c" (the web-client path); the API lives at the root.
        val root = raw.removeSuffix("/c").trimEnd('/')
        return listOf(
            "$root/portal.php",
            "$root/c/portal.php",
            "$root/server/load.php",
            "$root/stalker_portal/server/load.php",
            "$root/magLoad.php",
        ).mapNotNull { it.toHttpUrlOrNull() }
    }

    /** A MAG-box style STB User-Agent when the source hasn't set a specific one. */
    private fun stbUserAgent(source: Source): String {
        val ua = source.userAgent
        return if (ua.isBlank() || ua == Source.DEFAULT_USER_AGENT) DEFAULT_STB_UA else ua
    }

    /** Provider logos are often relative to the portal host. */
    private fun absoluteLogo(endpoint: HttpUrl, logo: String): String =
        if (logo.startsWith("http")) logo
        else "${endpoint.scheme}://${endpoint.host}:${endpoint.port}/${logo.trimStart('/')}"

    /** create_link returns e.g. "ffmpeg http://…" / "auto http://…" / a bare URL; keep just the URL. */
    private fun stripCmdPrefix(cmd: String): String {
        val trimmed = cmd.trim()
        val httpIdx = trimmed.indexOf("http")
        return if (httpIdx > 0) trimmed.substring(httpIdx).trim() else trimmed
    }

    private companion object {
        const val TAG = "StalkerApi"
        const val TOKEN_TTL_MILLIS = 4 * 60 * 1000L // handshake tokens are short-lived; re-mint often
        const val DEFAULT_STB_UA =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) " +
                "MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

        /** A realistic MAG firmware version string, sent in get_profile like a real box would. */
        const val STB_VER =
            "ImageDescription: 0.2.18-r23-250; ImageDate: Wed Aug 29 10:49:53 EEST 2018; " +
                "PORTAL version: 5.6.9; API Version: JS API version: 343; STB API version: 146; " +
                "Player Engine version: 0x58c"
    }
}

// ---- Minimal JSON helpers (self-contained; the Xtream accessors are private to that file) --------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() && it != "null" }

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
