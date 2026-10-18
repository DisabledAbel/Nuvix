/* Copyright (C) 2026 Nuvix Contributors. Licensed under GPL-3.0-or-later. */
package app.opentv.data.repo

/** Search filters describe supplied guide metadata; titles never imply verified sporting events. */
enum class EpgSearchFilter { ALL, CURRENTLY_AIRING, UPCOMING, MOVIES, SPORTS, SERIES }

/** Escapes SQL LIKE wildcards in user text and builds a bounded substring-search pattern. */
internal fun epgSearchPattern(query: String): String =
    "%" + query.trim().take(120).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

/** Hex category ids keep separators unambiguous and use just one SQLite bind variable. */
internal fun epgExcludedCategories(categories: Set<Pair<Long, String>>): String =
    categories.joinToString(separator = "|", prefix = "|", postfix = "|") { (source, category) ->
        "$source:" + category.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "0123456789ABCDEF"[value ushr 4].toString() + "0123456789ABCDEF"[value and 15]
        }
    }
