/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 * Modified for Nuvix on 2026-10-13: source validation and credential-free diagnostics.
 */
package app.opentv.data.repo

import app.opentv.data.db.SourceDao
import app.opentv.core.ProviderDiagnostics
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.remote.StalkerApi
import app.opentv.data.remote.XtreamApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class SourceRepository(
    private val dao: SourceDao,
    private val api: XtreamApi,
    private val stalkerApi: StalkerApi,
    private val onAccountVerified: (Source, XtreamApi.AccountInfo) -> Unit = { _, _ -> },
) {
    fun observeAll(): Flow<List<Source>> = dao.observeAll()

    suspend fun enabled(): List<Source> = dao.enabled()

    suspend fun byId(id: Long): Source? = dao.byId(id)

    suspend fun save(source: Source): Long = withContext(Dispatchers.IO) {
        val normalised = validatedSource(source)
        if (source.id == 0L) dao.insert(normalised)
        else {
            dao.update(normalised)
            source.id
        }
    }

    /** Checks details without persisting them; provider authorization is always required. */
    suspend fun test(source: Source): Result<String> = withContext(Dispatchers.IO) {
        try {
            val validated = validatedSource(source)
            val message = when (validated.kind) {
                SourceKind.XTREAM -> {
                    val info = api.authenticate(validated)
                    if (validated.id > 0) onAccountVerified(validated, info)
                    buildString {
                        append("Connected")
                        info.maxConnections?.let { append(" · $it connection(s)") }
                        info.expiryMillis?.let {
                            append(" · expires ${java.text.DateFormat.getDateInstance().format(java.util.Date(it))}")
                        }
                    }
                }
                SourceKind.M3U -> "Playlist address looks valid. It will be checked on first sync."
                SourceKind.STALKER -> {
                    stalkerApi.handshakeTest(validated)
                    "Portal accepted the MAC address. Loading channels…"
                }
            }
            Result.success(message)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(IllegalStateException(ProviderDiagnostics.describe(error)))
        }
    }

    companion object {
        /** Constant validation messages avoid echoing a credential-bearing URL on screen. */
        fun validatedSource(source: Source): Source {
            val normalized = normaliseUrl(source.url, source.kind)
            validateHttpUrl(normalized)
            source.epgUrl?.takeIf { it.isNotBlank() }?.let { validateHttpUrl(it.trim()) }
            require(source.userAgent.none { it == '\r' || it == '\n' || it == '\u0000' }) { "User-Agent must be a single line." }
            when (source.kind) {
                SourceKind.XTREAM -> {
                    require(!source.username.isNullOrBlank() && !source.password.isNullOrBlank()) {
                        "Xtream sources require a username and password."
                    }
                }
                SourceKind.STALKER -> require(source.macAddress?.matches(Regex("(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) == true) {
                    "Enter the MAC address authorized by your portal, using six colon-separated pairs."
                }
                SourceKind.M3U -> Unit
            }
            return source.copy(
                name = source.name.trim().ifBlank { "IPTV source" }.take(120),
                url = normalized, epgUrl = source.epgUrl?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        fun validateHttpUrl(value: String) {
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Source addresses must be a single line." }
            val parsed = value.toHttpUrlOrNull()
            require(parsed != null && parsed.scheme in setOf("http", "https")) { "Use a valid HTTP or HTTPS source address." }
            require(parsed.username.isEmpty() && parsed.password.isEmpty()) { "Enter credentials separately rather than in URL user information." }
        }

        /**
         * Users paste all sorts of things. Accept them all rather than making someone guess
         * the format: a trailing slash, a missing scheme, or a full `get.php` URL copied out
         * of a provider's welcome email.
         *
         * For an Xtream source we want the bare host, so API paths are stripped. For an M3U
         * source the URL *is* the playlist — stripping the path there would destroy it, which
         * is why [kind] is not optional.
         *
         * Pure and static so it can be tested without standing up the repository.
         */
        fun normaliseUrl(raw: String, kind: SourceKind): String {
            var url = raw.trim()
            if (url.isEmpty()) return url
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                url = "http://$url"
            }
            if (kind == SourceKind.M3U) return url
            // A Stalker portal URL is the portal path itself (e.g. .../stalker_portal or .../c) —
            // stripping Xtream API paths would be wrong; just tidy the trailing slash.
            if (kind == SourceKind.STALKER) return url.trimEnd('/')

            url = url.substringBefore("/player_api.php")
                .substringBefore("/panel_api.php")
                .substringBefore("/get.php")
                .substringBefore("/xmltv.php")
            return url.trimEnd('/')
        }
    }
}
