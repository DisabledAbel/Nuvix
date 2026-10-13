/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.parser

import app.opentv.data.model.Movie
import app.opentv.data.model.Series

/**
 * Turns the movie/series titles IPTV providers actually ship into clean, Plex-style titles.
 *
 * A provider VOD list is full of entries like:
 *
 *     4K-EN - Barbie (2023)
 *     EN - Apple Music Live - Ed Sheeran
 *     US| The Godfather 1972 HD
 *     [UK] Toy Story ᵁᴴᴰ
 *
 *     NF - Our Sticky Love (2026) (KR)
 *
 * where a leading language/country/quality/streaming-service tag and stray quality markers bury the
 * real name. This cleaner produces `Barbie (2023)`, `Apple Music Live - Ed Sheeran`,
 * `The Godfather 1972`, `Toy Story` and `Our Sticky Love (2026)` respectively — stripping a leading
 * service tag (`NF -`) and a trailing country code (`(KR)`) while keeping the release year.
 *
 * It deliberately builds on [ChannelNameNormalizer]: superscript decoration folding and the set of
 * quality/stream tokens are reused verbatim, so the two cleaners never drift apart on what counts as
 * "quality". What is *new* here — and specific to VOD — is preserving a trailing release year in
 * parentheses (`(2023)` must survive, where the channel normaliser would flatten it) and only
 * stripping a *leading* prefix, so an interior " - " in a real title (`Apple Music Live - Ed
 * Sheeran`) is left intact.
 *
 * Pure and testable: [clean] is a total function of its input. The raw provider name is still what
 * search and grouping index on; only the displayed string changes (see [displayTitle]).
 */
object VodTitleCleaner {

    /**
     * Language/country codes providers use as a leading tag. A curated set rather than a generic
     * "any two–three capitals", because VOD titles legitimately start with short capitalised words
     * before a separator — `FBI: Most Wanted`, `CSI: Miami`, `TED - ...` — and stripping those would
     * mangle real titles. A code we miss merely leaves a small `XX -` on screen (cosmetic); a code
     * we wrongly strip eats the title, so we bias hard toward this known list.
     */
    private val LANG_CODES: Set<String> = setOf(
        "EN", "ENG", "ES", "ESP", "SPA", "LAT", "FR", "FRA", "FRE", "DE", "GER", "DEU",
        "IT", "ITA", "PT", "POR", "BR", "BRA", "NL", "DUT", "PL", "POL", "RU", "RUS",
        "AR", "ARA", "TR", "TUR", "SV", "SWE", "SE", "NO", "NOR", "DA", "DAN", "DK",
        "FI", "FIN", "EL", "GRE", "GR", "RO", "RON", "CS", "CZE", "CZ", "HU", "HUN",
        "HR", "SR", "BG", "SK", "SL", "UK", "GB", "US", "USA", "CA", "CAN", "AU", "AUS",
        "NZ", "IE", "IN", "IND", "MX", "MEX", "ZA", "JP", "JPN", "KR", "KOR", "CN",
        "VN", "TH", "ID", "PH", "IR", "IL", "HE", "HEB", "AF", "ALB", "MULTI", "VO",
    )

    /**
     * Streaming-service tags providers stamp on the front of a title: `NF - …` (Netflix),
     * `AMZ - …` (Prime), `DSNY - …`/`D+ - …` (Disney+), and so on. Treated exactly like [LANG_CODES]
     * — stripped only when they lead before a separator (or sit in brackets) — and, for the same
     * reason, kept to a curated set: a code we miss leaves a small `NF -` on screen (cosmetic),
     * while wrongly stripping a real leading word eats the title. `D+` is matched via [PLUS_TAG]
     * because its `+` is not a bare-token character.
     */
    private val SERVICE_CODES: Set<String> = setOf(
        "NF",                    // Netflix
        "AMZ", "AMZN", "PMV",    // Amazon Prime Video
        "DSNY", "DNSP", "D+",    // Disney+
        "HBO", "HMAX",           // HBO / Max
        "ATV", "ATVP",           // Apple TV+
        "PMT",                   // Paramount+
        "HULU",                  // Hulu
        "PCOK",                  // Peacock
    )

    /** Edge junk trimmed off the final result — never a parenthesis, so a trailing `(2023)` stays. */
    private val EDGE_JUNK = charArrayOf(' ', '\t', '|', ':', '-', '–', '—', '.', ',')

    /** A leading bracketed tag: `[US]`, `(EN)`, `[4K]`, with an optional separator after it. */
    private val BRACKET_TAG = Regex("""^\s*[\[(]\s*([\p{L}\p{Nd}]{2,5})\s*[])]\s*[|:\-–—]?\s*""")

    /** A leading bare tag followed by a separator: `EN -`, `US|`, `4K:`. */
    private val BARE_TAG = Regex("""^\s*([\p{L}\p{Nd}]{2,5})\s*([|:\-–—])\s*""")

    /** A leading `letters+` service tag before a separator: `D+ -`. The `+` keeps these out of
     *  [BARE_TAG]; only tokens in [SERVICE_CODES] are actually stripped. */
    private val PLUS_TAG = Regex("""^\s*([\p{L}]{1,3}\+)\s*([|:\-–—])\s*""")

    /**
     * A trailing bracketed 2-letter tag: `(KR)`, `[US]`, `(en)`. Stripped only when the two letters
     * are a known country/language code (see [stripTrailingCodes]). The `{2}` and letters-only class
     * are deliberate: a trailing release year is digits (`(2026)`) and never matches, so it survives.
     */
    private val TRAILING_CODE_PAREN = Regex("""\s*[\[(]\s*([A-Za-z]{2})\s*[\])]\s*$""")

    private val MULTI_SPACE = Regex("""\s+""")

    /**
     * Cleans a raw provider title. Strips a leading language/country/quality prefix (and its
     * separators), removes stray quality tags anywhere, and folds superscript decoration — while
     * preserving the real title and any trailing `(year)`. A title with no provider junk comes back
     * unchanged. Never returns blank: if cleaning would empty the string, the trimmed original wins.
     */
    fun clean(raw: String): String {
        val folded = ChannelNameNormalizer.foldSuperscripts(raw).trim()
        if (folded.isEmpty()) return raw.trim()

        val deprefixed = stripLeadingPrefix(folded)
        val dequalified = stripStrayQuality(deprefixed)
        val detrailed = stripTrailingCodes(dequalified)
        val tidied = MULTI_SPACE.replace(detrailed, " ").trim().trim(*EDGE_JUNK).trim()
        return tidied.ifBlank { folded }
    }

    /**
     * Peels recognised tag segments off the FRONT only, one at a time, until the leading segment is
     * real title. Bracketed tags (`[US]`) are delimited by their brackets; bare tags (`EN`, `4K`)
     * must be followed by a separator. A generic (non-listed) two–three-letter capital code is only
     * trusted before a `|` or inside brackets — never a bare `:`/`-`, where it is far more likely to
     * be the start of a real title.
     */
    private fun stripLeadingPrefix(input: String): String {
        var s = input
        while (true) {
            val bracket = BRACKET_TAG.find(s)
            if (bracket != null && isPrefixTag(bracket.groupValues[1], bracketed = true, separator = null)) {
                s = s.substring(bracket.range.last + 1)
                continue
            }
            // A `letters+` service tag (`D+ -`) can't be a bare token, so it gets its own pass; only
            // curated service marks are trusted here, never a generic code.
            val plus = PLUS_TAG.find(s)
            if (plus != null && plus.groupValues[1].uppercase() in SERVICE_CODES) {
                s = s.substring(plus.range.last + 1)
                continue
            }
            val bare = BARE_TAG.find(s)
            if (bare != null &&
                isPrefixTag(bare.groupValues[1], bracketed = false, separator = bare.groupValues[2].firstOrNull())
            ) {
                s = s.substring(bare.range.last + 1)
                continue
            }
            return s
        }
    }

    private fun isPrefixTag(token: String, bracketed: Boolean, separator: Char?): Boolean {
        if (ChannelNameNormalizer.qualityRankOfToken(token) != null) return true
        if (ChannelNameNormalizer.isStreamMarker(token)) return true
        val upper = token.uppercase()
        if (upper in LANG_CODES) return true
        if (upper in SERVICE_CODES) return true
        // A generic short capital code (POR, SWE, GER…) we don't list: trust it only where a real
        // title could not plausibly sit — inside brackets, or immediately before a pipe.
        val isGenericCode = token.length in 2..3 && token.all { it.isLetter() } && token == upper
        return isGenericCode && (bracketed || separator == '|')
    }

    /**
     * Drops stray resolution/quality tokens (`4K`, `FHD`, `HD`, `SD`, `UHD`, `1080p`…) wherever they
     * appear, using [ChannelNameNormalizer]'s own token table. Splits on spaces only and keeps every
     * non-quality token verbatim — including a lone `-` (so an interior `A - B` title survives) and a
     * `(2023)` year, whose bare form (`2023`) is not a quality tag.
     */
    private fun stripStrayQuality(input: String): String {
        if (input.isBlank()) return input
        return input.split(' ')
            .filter { part ->
                val bare = part.trim('.', ',', ':', ';', '|', '-', '(', ')', '[', ']')
                // Keep punctuation-only tokens (real separators) and anything that isn't a quality tag.
                bare.isEmpty() || ChannelNameNormalizer.qualityRankOfToken(bare) == null
            }
            .joinToString(" ")
    }

    /**
     * Strips a trailing bracketed 2-letter country/language tag — `Our Sticky Love (2026) (KR)` →
     * `Our Sticky Love (2026)` — looping so a doubled `… (KR) (US)` clears fully. A trailing release
     * year is left untouched: [TRAILING_CODE_PAREN] only matches two *letters*, so `(2026)` never
     * does, and an unknown 2-letter parenthetical (not a real code) is kept rather than guessed at.
     */
    private fun stripTrailingCodes(input: String): String {
        var s = input.trim()
        while (true) {
            val match = TRAILING_CODE_PAREN.find(s) ?: break
            if (match.groupValues[1].uppercase() !in LANG_CODES) break
            s = s.substring(0, match.range.first).trim()
        }
        return s
    }
}

/** The clean, display-ready title for a movie — the VOD analogue of `Channel.shownName`. */
val Movie.displayTitle: String get() = VodTitleCleaner.clean(name)

/** The clean, display-ready title for a series. */
val Series.displayTitle: String get() = VodTitleCleaner.clean(name)
