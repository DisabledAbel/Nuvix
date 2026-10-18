/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local identifiers only: never stores or uploads credential-bearing stream URLs. */
class RecentChannels private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nuvix_recent_channels", Context.MODE_PRIVATE)
    private val mutableIds = MutableStateFlow(parseIds(prefs.getString("ids", "").orEmpty()))
    val ids = mutableIds.asStateFlow()

    /** Moves a valid channel ID to the front of the bounded recent-history list. */
    @Synchronized fun record(id: Long) {
        if (id <= 0) return
        val updated = (listOf(id) + mutableIds.value.filter { it != id }).take(40)
        prefs.edit().putString("ids", updated.joinToString(",")).apply()
        mutableIds.value = updated
    }

    companion object {
        @Volatile private var instance: RecentChannels? = null
        /** Returns the process-wide recent-channel store. */
        fun get(context: Context): RecentChannels = instance ?: synchronized(this) {
            instance ?: RecentChannels(context).also { instance = it }
        }
        /** Decodes valid positive channel IDs from saved recent history. */
        internal fun parseIds(value: String): List<Long> = value.split(',').mapNotNull { it.toLongOrNull() }
            .filter { it > 0 }.distinct().take(40)
    }
}
