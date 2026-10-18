/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.multiview

import android.content.Context
import android.content.SharedPreferences
import app.opentv.connections.AccountConnectionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stable database references only. [accountId] is an existing hashed account key. */
data class MultiViewChannelReference(val channelId: Long, val sourceId: Long, val accountId: String? = null)
data class MultiViewSavedPanel(val panelId: String, val channel: MultiViewChannelReference)
data class MultiViewPresentation(
    val panels: List<MultiViewSavedPanel>,
    val primaryPanelId: String,
    val audioPanelId: String?,
    val layout: MultiViewLayout,
    val showLabels: Boolean = true,
)
data class MultiViewPreset(val id: String, val name: String, val presentation: MultiViewPresentation, val updatedAtMs: Long)

/** Preview input is deliberately incapable of opening a stream or carrying a stream URL. */
data class MultiViewPreviewChannel(val channelId: Long, val sourceId: Long, val channelName: String, val providerName: String)
data class MultiViewPreviewPanel(
    val panel: MultiViewSavedPanel,
    val channelName: String?,
    val providerName: String?,
    val available: Boolean,
    val accountChanged: Boolean,
)
data class MultiViewConnectionRequirement(
    val accountId: String?,
    val sourceIds: Set<Long>,
    val requestedConnections: Int,
    val configuredMaximum: Int?,
    val locallyReserved: Int,
) {
    val unknownAllowance: Boolean get() = configuredMaximum == null
    val exceedsConfiguredAllowance: Boolean get() = configuredMaximum?.let { requestedConnections + locallyReserved > it } ?: false
}
data class MultiViewPresetPreview(
    val panels: List<MultiViewPreviewPanel>,
    val requirements: List<MultiViewConnectionRequirement>,
    val deviceCapabilityUncertain: Boolean = true,
)

/**
 * Versioned, device-local SharedPreferences matches AppSettings without changing the encrypted
 * catalogue schema. Only allowlisted presentation fields are encoded. Reservations, approvals,
 * players, surfaces, credentials and resolved URLs have no representation in this format.
 *
 * There was no previous preset schema. Missing storage migrates to v1 on the first write;
 * malformed or newer versions stay untouched and read-only, rather than destroying user data.
 */
class MultiViewPresetStore(context: Context) {
    private val storage = context.applicationContext.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE)
    private val initial = read()
    private val state = MutableStateFlow(initial.presets)
    val presets: StateFlow<List<MultiViewPreset>> = state.asStateFlow()
    private val recoveryState = MutableStateFlow(initial.interrupted)
    val recovery: StateFlow<MultiViewPresentation?> = recoveryState.asStateFlow()
    val storageAvailable: Boolean get() = read().writable
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_CONTENT) {
            val saved = read()
            state.value = saved.presets
            recoveryState.value = saved.interrupted
        }
    }

    init { storage.registerOnSharedPreferenceChangeListener(listener) }

    fun close() = storage.unregisterOnSharedPreferenceChangeListener(listener)
    fun find(id: String): MultiViewPreset? = read().presets.firstOrNull { it.id == id }

    /** A non-null id replaces an existing preset; it cannot silently create another. */
    fun upsert(name: String, presentation: MultiViewPresentation, id: String? = null): MultiViewPreset? = synchronized(WRITE_LOCK) {
        val saved = read()
        val cleanName = name.trim()
        if (!saved.writable || !validName(cleanName) || !validPresentation(presentation)) return@synchronized null
        if (id != null && saved.presets.none { it.id == id }) return@synchronized null
        if (id == null && saved.presets.size >= MAX_PRESETS) return@synchronized null
        val preset = MultiViewPreset(id ?: UUID.randomUUID().toString(), cleanName, presentation, System.currentTimeMillis())
        val next = saved.presets.filterNot { it.id == preset.id } + preset
        if (write(saved.copy(presets = next))) preset else null
    }

    fun rename(id: String, name: String): Boolean = synchronized(WRITE_LOCK) {
        val saved = read()
        val cleanName = name.trim()
        if (!saved.writable || !validName(cleanName) || saved.presets.none { it.id == id }) return@synchronized false
        write(saved.copy(presets = saved.presets.map { if (it.id == id) it.copy(name = cleanName, updatedAtMs = System.currentTimeMillis()) else it }))
    }

    fun delete(id: String): Boolean = synchronized(WRITE_LOCK) {
        val saved = read()
        if (!saved.writable || saved.presets.none { it.id == id }) return@synchronized false
        write(saved.copy(presets = saved.presets.filterNot { it.id == id }))
    }

    /** Checkpoint presentation only. Call clearRecovery on an intentional Multi-View exit. */
    fun saveInterrupted(presentation: MultiViewPresentation): Boolean = synchronized(WRITE_LOCK) {
        val saved = read()
        if (!saved.writable || !validPresentation(presentation)) return@synchronized false
        write(saved.copy(interrupted = presentation))
    }

    /** Reading this snapshot never authorizes a reservation or starts playback. */
    fun peekRecovery(): MultiViewPresentation? = read().interrupted
    fun clearRecovery(): Boolean = synchronized(WRITE_LOCK) {
        val saved = read()
        if (!saved.writable) return@synchronized false
        if (saved.interrupted == null) true else write(saved.copy(interrupted = null))
    }

    /** Pure, local preview: callers supply already-loaded catalog and connection observations. */
    fun preview(
        presentation: MultiViewPresentation,
        channels: List<MultiViewPreviewChannel>,
        accounts: List<AccountConnectionSnapshot>,
    ): MultiViewPresetPreview {
        val catalog = channels.associateBy { it.channelId to it.sourceId }
        val bySource = accounts.flatMap { account -> account.sourceIds.map { it to account } }.toMap()
        val panels = presentation.panels.map { panel ->
            val channel = catalog[panel.channel.channelId to panel.channel.sourceId]
            val currentAccount = bySource[panel.channel.sourceId]?.accountKey
            MultiViewPreviewPanel(panel, channel?.channelName, channel?.providerName, channel != null,
                panel.channel.accountId != null && currentAccount != null && currentAccount != panel.channel.accountId)
        }
        // Recompute account grouping from current local settings, not a stale saved account link.
        val requirements = presentation.panels.groupBy { panel ->
            bySource[panel.channel.sourceId]?.accountKey ?: "source_${panel.channel.sourceId}"
        }.map { (_, group) ->
            val snapshot = bySource[group.first().channel.sourceId]
            MultiViewConnectionRequirement(snapshot?.accountKey, group.map { it.channel.sourceId }.toSet(),
                group.size, snapshot?.effectiveMaximum, snapshot?.locallyReserved ?: 0)
        }
        return MultiViewPresetPreview(panels, requirements)
    }

    private data class Stored(
        val presets: List<MultiViewPreset> = emptyList(),
        val interrupted: MultiViewPresentation? = null,
        val writable: Boolean = true,
    )

    private fun read(): Stored {
        val value = storage.all[KEY_CONTENT] ?: return Stored()
        if (value !is String || value.length > MAX_CONTENT_LENGTH) return Stored(writable = false)
        return runCatching {
            val root = JSONObject(value)
            require(root.getInt("version") == VERSION)
            val array = root.getJSONArray("presets")
            require(array.length() <= MAX_PRESETS)
            val presets = List(array.length()) { index ->
                val entry = array.getJSONObject(index)
                MultiViewPreset(entry.getString("id"), entry.getString("name"),
                    decodePresentation(entry.getJSONObject("presentation")), entry.getLong("updatedAtMs")).also {
                    require(validId(it.id) && validName(it.name) && it.updatedAtMs >= 0)
                }
            }
            require(presets.map { it.id }.distinct().size == presets.size)
            Stored(presets, if (root.isNull("interrupted")) null else decodePresentation(root.getJSONObject("interrupted")))
        }.getOrElse { Stored(writable = false) }
    }

    private fun write(saved: Stored): Boolean {
        val root = JSONObject().put("version", VERSION).put("presets", JSONArray().apply {
            saved.presets.forEach { preset ->
                put(JSONObject().put("id", preset.id).put("name", preset.name)
                    .put("updatedAtMs", preset.updatedAtMs).put("presentation", encodePresentation(preset.presentation)))
            }
        }).put("interrupted", saved.interrupted?.let(::encodePresentation) ?: JSONObject.NULL)
        val written = storage.edit().putString(KEY_CONTENT, root.toString()).commit()
        if (written) {
            state.value = saved.presets
            recoveryState.value = saved.interrupted
        }
        return written
    }

    private fun encodePresentation(value: MultiViewPresentation): JSONObject = JSONObject()
        .put("primaryPanelId", value.primaryPanelId).put("audioPanelId", value.audioPanelId ?: JSONObject.NULL)
        .put("layout", value.layout.name).put("showLabels", value.showLabels)
        .put("panels", JSONArray().apply {
            value.panels.forEach { panel ->
                put(JSONObject().put("panelId", panel.panelId).put("channelId", panel.channel.channelId)
                    .put("sourceId", panel.channel.sourceId).put("accountId", panel.channel.accountId ?: JSONObject.NULL))
            }
        })

    private fun decodePresentation(value: JSONObject): MultiViewPresentation {
        val array = value.getJSONArray("panels")
        require(array.length() in 1..4)
        return MultiViewPresentation(List(array.length()) { index ->
            val panel = array.getJSONObject(index)
            MultiViewSavedPanel(panel.getString("panelId"), MultiViewChannelReference(panel.getLong("channelId"),
                panel.getLong("sourceId"), if (panel.isNull("accountId")) null else panel.getString("accountId")))
        }, value.getString("primaryPanelId"), if (value.isNull("audioPanelId")) null else value.getString("audioPanelId"),
            MultiViewLayout.valueOf(value.getString("layout")), value.optBoolean("showLabels", true))
            .also { require(validPresentation(it)) }
    }

    companion object {
        const val STORAGE_NAME = "nuvix_multiview_local"
        internal const val KEY_CONTENT = "content_v1"
        private const val VERSION = 1
        private const val MAX_PRESETS = 32
        private const val MAX_CONTENT_LENGTH = 256_000
        private val WRITE_LOCK = Any()
        private fun validId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{1,80}"))
        private fun validName(value: String): Boolean = value.isNotBlank() && value.length <= 80 &&
            value.none { it.isISOControl() } && !value.contains("://") &&
            !Regex("(?i)(token|password|passwd|authorization|api[_-]?key)\\s*=").containsMatchIn(value)
        private fun validPresentation(value: MultiViewPresentation): Boolean {
            val ids = value.panels.map { it.panelId }
            return value.panels.size in 1..4 && ids.distinct().size == ids.size && ids.all(::validId) &&
                value.primaryPanelId in ids && (value.audioPanelId == null || value.audioPanelId in ids) &&
                value.panels.map { it.channel.channelId }.distinct().size == value.panels.size &&
                value.panels.all { it.channel.channelId > 0 && it.channel.sourceId > 0 &&
                    (it.channel.accountId == null || it.channel.accountId.matches(Regex("acct_[0-9a-f]{64}"))) }
        }
    }
}
