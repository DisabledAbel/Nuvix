/* Nuvix Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.recording

import android.content.Context

/** Only safe recording/window identifiers persist; runtime reservations and accounts do not. */
class RecordingWarningStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("recording_conflict_warnings", Context.MODE_PRIVATE)
    fun dismissed(key: String) = prefs.getString("dismissed:${key.substringBefore(':')}", null) == key
    fun dismiss(key: String) { prefs.edit().putString("dismissed:${key.substringBefore(':')}", key).apply() }
    fun notified(key: String) = prefs.getString("notified:${key.substringBefore(':')}", null) == key
    fun markNotified(key: String) { prefs.edit().putString("notified:${key.substringBefore(':')}", key).apply() }
    fun prune(keys: Set<String>) {
        val ids = keys.map { it.substringBefore(':') }.toSet()
        val edit = prefs.edit()
        prefs.all.keys.filter { it.substringAfter(':') !in ids }.forEach(edit::remove)
        edit.apply()
    }
}
