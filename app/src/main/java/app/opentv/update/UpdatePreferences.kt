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

    fun saveAvailable(update: UpdateChecker.Update?) {
        prefs.edit().putString("available", update?.let { UpdateChecker.json.encodeToString(it) }).apply()
    }
    fun available(): UpdateChecker.Update? = decode(prefs.getString("available", null))
    fun savePendingSession(sessionId: Int, update: UpdateChecker.Update) {
        // commit before PackageInstaller can deliver its first asynchronous callback.
        prefs.edit().putInt("session_id", sessionId)
            .putString("session_update", UpdateChecker.json.encodeToString(update)).commit()
    }
    fun pendingSessionId(): Int = prefs.getInt("session_id", -1)
    fun pendingUpdate(): UpdateChecker.Update? = decode(prefs.getString("session_update", null))
    fun clearPendingSession() { prefs.edit().remove("session_id").remove("session_update").apply() }

    private fun decode(raw: String?): UpdateChecker.Update? = raw?.let {
        runCatching { UpdateChecker.json.decodeFromString<UpdateChecker.Update>(it) }.getOrNull()
    }
}
