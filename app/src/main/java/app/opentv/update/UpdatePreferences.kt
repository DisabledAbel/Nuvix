package app.opentv.update

import android.content.Context
import kotlinx.serialization.encodeToString

/** Independent update choices; no provider, account or playback settings are touched. */
class UpdatePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nuvix_updates", Context.MODE_PRIVATE)
    var automaticChecks: Boolean
        get() = prefs.getBoolean("automatic_checks", true)
        set(value) { prefs.edit().putBoolean("automatic_checks", value).apply() }
    var backgroundDownloads: Boolean
        get() = prefs.getBoolean("background_downloads", false)
        set(value) { prefs.edit().putBoolean("background_downloads", value).apply() }
    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(value) { prefs.edit().putBoolean("notifications", value).apply() }
    var lastCheckTime: Long
        get() = prefs.getLong("last_check", 0)
        set(value) { prefs.edit().putLong("last_check", value).apply() }
    var lastFailure: String?
        get() = prefs.getString("last_failure", null)
        set(value) { prefs.edit().putString("last_failure", value).apply() }

    /** Persists the latest eligible release for background and foreground update presentation. */
    fun saveAvailable(update: UpdateChecker.Update?) {
        prefs.edit().putString("available", update?.let { UpdateChecker.json.encodeToString(it) }).apply()
    }
    /** Returns the saved eligible release, or null when none can be decoded. */
    fun available(): UpdateChecker.Update? = decode(prefs.getString("available", null))
    /** Persists the Android installer session and its associated release metadata. */
    fun savePendingSession(sessionId: Int, update: UpdateChecker.Update) {
        // commit before PackageInstaller can deliver its first asynchronous callback.
        prefs.edit().putInt("session_id", sessionId)
            .putString("session_update", UpdateChecker.json.encodeToString(update)).commit()
    }
    /** Returns the saved active installer session ID when available. */
    fun pendingSessionId(): Int = prefs.getInt("session_id", -1)
    /** Returns the release associated with the saved installer session. */
    fun pendingUpdate(): UpdateChecker.Update? = decode(prefs.getString("session_update", null))
    /** Clears the saved installer session and its release metadata. */
    fun clearPendingSession() { prefs.edit().remove("session_id").remove("session_update").apply() }

    /** Decodes saved release metadata, returning null for missing or invalid data. */
    private fun decode(raw: String?): UpdateChecker.Update? = raw?.let {
        runCatching { UpdateChecker.json.decodeFromString<UpdateChecker.Update>(it) }.getOrNull()
    }
}
