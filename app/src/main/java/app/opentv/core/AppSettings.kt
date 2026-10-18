/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.core

import android.content.Context
import app.opentv.data.model.StremioAddon
import app.opentv.multiview.MultiViewPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The handful of user preferences that are not "data" (sources, guides) but "how the app
 * behaves". Backed by [android.content.SharedPreferences] rather than DataStore on purpose:
 * three flags read at composition time do not need an async, Flow-based store and the
 * ceremony that comes with it. Each setting is also mirrored into a [StateFlow] so Compose
 * recomposes the moment one changes.
 */
class AppSettings private constructor(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)

    // Keep public settings/state APIs stable while moving credential-bearing strings out of
    // legacy plaintext preferences. Migration verifies ciphertext before deleting old values.
    private val privateSettings = PrivateSettingsVault(context).apply {
        migrateFrom(prefs, setOf(KEY_SMB_PASS, KEY_TMDB_KEY, KEY_STREMIO_ADDONS))
    }

    private val addonJson = Json { ignoreUnknownKeys = true }

    /** How the app chooses light vs dark. TV defaults to dark under [ThemeMode.SYSTEM]. */
    enum class ThemeMode { SYSTEM, DARK, LIGHT }

    private val _themeMode = MutableStateFlow(readThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /**
     * How the live-TV channel list is laid out: the scrolling EPG time-[ChannelLayout.GRID], or a
     * compact one-channel-per-row [ChannelLayout.LIST] (logo, name, now/next) for people who find
     * the full grid busy. Defaults to the grid.
     */
    enum class ChannelLayout { GRID, LIST }

    private val _channelLayout = MutableStateFlow(readChannelLayout())
    val channelLayout: StateFlow<ChannelLayout> = _channelLayout.asStateFlow()

    // Full-guide presentation is independent of the longstanding GRID/LIST channel-list choice.
    // Reading only known types makes older/newer or malformed preference files recover safely.
    private val _guideLayout = MutableStateFlow(readGuideEnum(KEY_GUIDE_LAYOUT, GuideLayout.STANDARD))
    val guideLayout: StateFlow<GuideLayout> = _guideLayout.asStateFlow()
    private val _guideDensity = MutableStateFlow(readGuideEnum(KEY_GUIDE_DENSITY, GuideTimelineDensity.COMFORTABLE))
    val guideDensity: StateFlow<GuideTimelineDensity> = _guideDensity.asStateFlow()
    private val _guideDefaultFilter = MutableStateFlow(readGuideEnum(KEY_GUIDE_FILTER, GuideChannelFilter.ALL))
    val guideDefaultFilter: StateFlow<GuideChannelFilter> = _guideDefaultFilter.asStateFlow()
    private val _guideSort = MutableStateFlow(readGuideEnum(KEY_GUIDE_SORT, GuideChannelSort.PROVIDER_ORDER))
    val guideSort: StateFlow<GuideChannelSort> = _guideSort.asStateFlow()
    private val _showGuideArtwork = MutableStateFlow(readGuideBoolean(KEY_GUIDE_ARTWORK, true))
    val showGuideArtwork: StateFlow<Boolean> = _showGuideArtwork.asStateFlow()
    private val _showGuideProgress = MutableStateFlow(readGuideBoolean(KEY_GUIDE_PROGRESS, true))
    val showGuideProgress: StateFlow<Boolean> = _showGuideProgress.asStateFlow()
    private val _rememberGuidePosition = MutableStateFlow(readGuideBoolean(KEY_GUIDE_REMEMBER, true))
    val rememberGuidePosition: StateFlow<Boolean> = _rememberGuidePosition.asStateFlow()
    private val _guideFilterGroupId = MutableStateFlow(readGuideId(KEY_GUIDE_GROUP))
    val guideFilterGroupId: StateFlow<Long?> = _guideFilterGroupId.asStateFlow()
    private val _guideFilterProviderId = MutableStateFlow(readGuideId(KEY_GUIDE_PROVIDER))
    val guideFilterProviderId: StateFlow<Long?> = _guideFilterProviderId.asStateFlow()
    private val _lastGuidePosition = MutableStateFlow(readGuidePosition())
    val lastGuidePosition: StateFlow<GuidePosition?> = _lastGuidePosition.asStateFlow()

    /** Persists the guide layout preference and updates its observable value. */
    fun setGuideLayout(layout: GuideLayout) {
        prefs.edit().putString(KEY_GUIDE_LAYOUT, layout.name).apply()
        _guideLayout.value = layout
    }

    /** Persists the guide density preference and updates its observable value. */
    fun setGuideDensity(density: GuideTimelineDensity) {
        prefs.edit().putString(KEY_GUIDE_DENSITY, density.name).apply()
        _guideDensity.value = density
    }

    /** Also used by portable cloud preferences; it never changes device-local filter targets. */
    fun setGuideDefaultFilter(filter: GuideChannelFilter) {
        prefs.edit().putString(KEY_GUIDE_FILTER, filter.name).apply()
        _guideDefaultFilter.value = filter
    }

    /** Keep the last group/provider selection available when switching to another filter. */
    fun setGuideFilter(filter: GuideChannelFilter, groupId: Long? = null, providerId: Long? = null) {
        val editor = prefs.edit().putString(KEY_GUIDE_FILTER, filter.name)
        val group = groupId?.takeIf { it > 0 }
        val provider = providerId?.takeIf { it > 0 }
        group?.let { editor.putLong(KEY_GUIDE_GROUP, it) }
        provider?.let { editor.putLong(KEY_GUIDE_PROVIDER, it) }
        editor.apply()
        group?.let { _guideFilterGroupId.value = it }
        provider?.let { _guideFilterProviderId.value = it }
        _guideDefaultFilter.value = filter
    }

    /** Persists the guide sort preference and updates its observable value. */
    fun setGuideSort(sort: GuideChannelSort) {
        prefs.edit().putString(KEY_GUIDE_SORT, sort.name).apply()
        _guideSort.value = sort
    }

    /** Persists the show guide artwork preference and updates its observable value. */
    fun setShowGuideArtwork(visible: Boolean) {
        prefs.edit().putBoolean(KEY_GUIDE_ARTWORK, visible).apply()
        _showGuideArtwork.value = visible
    }

    /** Persists the show guide progress preference and updates its observable value. */
    fun setShowGuideProgress(visible: Boolean) {
        prefs.edit().putBoolean(KEY_GUIDE_PROGRESS, visible).apply()
        _showGuideProgress.value = visible
    }

    /** Persists the remember guide position preference and updates its observable value. */
    fun setRememberGuidePosition(remember: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_GUIDE_REMEMBER, remember)
        if (!remember) editor.remove(KEY_GUIDE_POSITION_CHANNEL).remove(KEY_GUIDE_POSITION_TIME)
        editor.apply()
        _rememberGuidePosition.value = remember
        if (!remember) _lastGuidePosition.value = null
    }

    /** Focus movement never changes the saved live playback channel. */
    fun setGuidePosition(channelId: Long, timeMs: Long) {
        if (!_rememberGuidePosition.value || channelId <= 0 || timeMs < 0) return
        val position = GuidePosition(channelId, timeMs)
        if (_lastGuidePosition.value == position) return
        prefs.edit().putLong(KEY_GUIDE_POSITION_CHANNEL, channelId)
            .putLong(KEY_GUIDE_POSITION_TIME, timeMs).apply()
        _lastGuidePosition.value = position
    }

    /** Reads an enum preference, returning [fallback] for missing or invalid storage. */
    private inline fun <reified T : Enum<T>> readGuideEnum(key: String, fallback: T): T {
        val saved = prefs.all[key] as? String
        return enumValues<T>().firstOrNull { it.name == saved } ?: fallback
    }

    /** Reads a guide boolean with a safe fallback for invalid storage. */
    private fun readGuideBoolean(key: String, fallback: Boolean): Boolean = prefs.all[key] as? Boolean ?: fallback
    /** Reads a valid saved guide row ID, returning null for absent or malformed values. */
    private fun readGuideId(key: String): Long? = (prefs.all[key] as? Long)?.takeIf { it > 0 }

    /** Restores a valid remembered channel and time position from guide settings. */
    private fun readGuidePosition(): GuidePosition? {
        if (!_rememberGuidePosition.value) return null
        val channel = readGuideId(KEY_GUIDE_POSITION_CHANNEL) ?: return null
        val time = (prefs.all[KEY_GUIDE_POSITION_TIME] as? Long)?.takeIf { it >= 0 } ?: return null
        return GuidePosition(channel, time)
    }

    // Multi-View choices are device-local and do not alter account connection policies.
    // The additive v1 preference namespace leaves existing AppSettings and Room data intact.
    private val _multiViewPreferences = MutableStateFlow(readMultiViewPreferences())
    val multiViewPreferences: StateFlow<MultiViewPreferences> = _multiViewPreferences.asStateFlow()

    fun setMultiViewPreferences(value: MultiViewPreferences) {
        val next = value.validated()
        prefs.edit().putInt("multiview_preferences_version", 1)
            .putBoolean("multiview_enabled", next.enabled)
            .putString("multiview_audio_mode", next.audioMode.name)
            .putInt("multiview_focus_delay_ms", next.focusDelayMs)
            .putString("multiview_fullscreen_restoration", next.fullscreenRestoration.name)
            .putBoolean("multiview_buffering_suggestions", next.bufferingSuggestions)
            .putBoolean("multiview_compact_guide", next.compactGuide)
            .putBoolean("multiview_show_labels", next.showLabels)
            .putBoolean("multiview_save_presentation", next.savePresentationPreferences)
            .putString("multiview_recovery", next.recovery.name).apply()
        _multiViewPreferences.value = next
    }

    private fun readMultiViewPreferences(): MultiViewPreferences {
        val defaults = MultiViewPreferences()
        // Unknown versions and malformed values never grant automatic restoration.
        val version = prefs.all["multiview_preferences_version"]
        if (version != null && version != 1) return defaults
        return MultiViewPreferences(
            enabled = readGuideBoolean("multiview_enabled", defaults.enabled),
            audioMode = readGuideEnum("multiview_audio_mode", defaults.audioMode),
            focusDelayMs = (prefs.all["multiview_focus_delay_ms"] as? Int)
                ?.takeIf { it in 0..2_000 } ?: defaults.focusDelayMs,
            fullscreenRestoration = readGuideEnum("multiview_fullscreen_restoration", defaults.fullscreenRestoration),
            bufferingSuggestions = readGuideBoolean("multiview_buffering_suggestions", defaults.bufferingSuggestions),
            compactGuide = readGuideBoolean("multiview_compact_guide", defaults.compactGuide),
            showLabels = readGuideBoolean("multiview_show_labels", defaults.showLabels),
            savePresentationPreferences = readGuideBoolean("multiview_save_presentation", defaults.savePresentationPreferences),
            recovery = readGuideEnum("multiview_recovery", defaults.recovery),
        )
    }

    // Local-only diagnostics preferences. Live measurements never enter cloud records.
    private val _diagnosticsEnabled = MutableStateFlow(prefs.getBoolean("diagnostics_enabled", true))
    val diagnosticsEnabled = _diagnosticsEnabled.asStateFlow()
    private val _diagnosticsDetailed = MutableStateFlow(prefs.getBoolean("diagnostics_detailed", false))
    val diagnosticsDetailed = _diagnosticsDetailed.asStateFlow()
    private val _diagnosticsTechnical = MutableStateFlow(prefs.getBoolean("diagnostics_technical", true))
    val diagnosticsTechnical = _diagnosticsTechnical.asStateFlow()
    /** Persists the diagnostics enabled preference and updates its observable value. */
    fun setDiagnosticsEnabled(value: Boolean) {
        prefs.edit().putBoolean("diagnostics_enabled", value).apply(); _diagnosticsEnabled.value = value
    }
    /** Persists the diagnostics detailed preference and updates its observable value. */
    fun setDiagnosticsDetailed(value: Boolean) {
        prefs.edit().putBoolean("diagnostics_detailed", value).apply(); _diagnosticsDetailed.value = value
    }
    /** Persists the diagnostics technical preference and updates its observable value. */
    fun setDiagnosticsTechnical(value: Boolean) {
        prefs.edit().putBoolean("diagnostics_technical", value).apply(); _diagnosticsTechnical.value = value
    }

    /** Whether embedded subtitles/closed captions are shown when a stream carries them. */
    private val _subtitlesEnabled = MutableStateFlow(prefs.getBoolean(KEY_SUBTITLES, true))
    val subtitlesEnabled: StateFlow<Boolean> = _subtitlesEnabled.asStateFlow()

    /** Whether the selected channel plays live inside the guide's preview pane. */
    private val _guidePreviewVideo = MutableStateFlow(prefs.getBoolean(KEY_PREVIEW_VIDEO, true))
    val guidePreviewVideo: StateFlow<Boolean> = _guidePreviewVideo.asStateFlow()

    /** Whether the guide preview plays sound (off by default — quieter while browsing). */
    private val _guidePreviewSound = MutableStateFlow(prefs.getBoolean(KEY_PREVIEW_SOUND, false))
    val guidePreviewSound: StateFlow<Boolean> = _guidePreviewSound.asStateFlow()

    /** Retain the active live player while navigating within this Activity's app session. */
    private val _persistentLivePlayback = MutableStateFlow(prefs.getBoolean(KEY_PERSISTENT_LIVE, true))
    val persistentLivePlayback: StateFlow<Boolean> = _persistentLivePlayback.asStateFlow()

    /** Display the retained live session while browsing; this never starts a new stream. */
    private val _livePreview = MutableStateFlow(prefs.getBoolean(KEY_LIVE_PREVIEW, true))
    val livePreview: StateFlow<Boolean> = _livePreview.asStateFlow()

    /** In-app browsing audio only; background playback remains governed by lifecycle/PiP. */
    private val _continueAudioWhileBrowsing = MutableStateFlow(prefs.getBoolean(KEY_BROWSING_AUDIO, true))
    val continueAudioWhileBrowsing: StateFlow<Boolean> = _continueAudioWhileBrowsing.asStateFlow()

    private val _automaticEpg = MutableStateFlow(prefs.getBoolean(KEY_AUTO_EPG, true))
    val automaticEpg: StateFlow<Boolean> = _automaticEpg.asStateFlow()

    /** Persists the automatic epg preference and updates its observable value. */
    fun setAutomaticEpg(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_EPG, enabled).apply()
        _automaticEpg.value = enabled
    }

    /** Checks whether automatic discovery was locally suppressed for this guide URL. */
    fun isEpgFeedSuppressed(url: String): Boolean =
        epgFeedKey(url) in prefs.getStringSet(KEY_EPG_SUPPRESSED, emptySet()).orEmpty()

    /** Adds or removes the URL digest used to suppress automatic guide discovery. */
    fun suppressEpgFeed(url: String, suppressed: Boolean) {
        val urls = prefs.getStringSet(KEY_EPG_SUPPRESSED, emptySet()).orEmpty().toMutableSet()
        val key = epgFeedKey(url)
        if (suppressed) urls.add(key) else urls.remove(key)
        prefs.edit().putStringSet(KEY_EPG_SUPPRESSED, urls).apply()
    }

    /** Hashes a guide URL so suppression storage does not retain credentials or signed query strings. */
    // Custom guide URLs can contain passwords or signed query strings. Persist only a digest.
    private fun epgFeedKey(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }


    /** The profile whose watch history is active. Defaults to the built-in profile (id 1). */
    private val _activeProfileId = MutableStateFlow(prefs.getLong(KEY_ACTIVE_PROFILE, 1L))
    val activeProfileId: StateFlow<Long> = _activeProfileId.asStateFlow()

    /** Persists the active profile preference and updates its observable value. */
    fun setActiveProfile(id: Long) {
        prefs.edit().putLong(KEY_ACTIVE_PROFILE, id).apply()
        _activeProfileId.value = id
    }

    // ---- Parental controls -------------------------------------------------------------------

    /** Whether a parental PIN is set. The PIN itself is only ever stored as a salted hash. */
    private val _pinIsSet = MutableStateFlow(prefs.getString(KEY_PIN_HASH, null) != null)
    val pinIsSet: StateFlow<Boolean> = _pinIsSet.asStateFlow()

    /** Category group keys the user has marked adult/hidden. */
    private val _hiddenCategories =
        MutableStateFlow(prefs.getStringSet(KEY_HIDDEN_CATS, emptySet())!!.toSet())
    val hiddenCategories: StateFlow<Set<String>> = _hiddenCategories.asStateFlow()

    /**
     * Session unlock. Deliberately *not* persisted: revealing hidden categories lasts until the
     * app is next launched, so a child restarting the app is back behind the lock.
     */
    private val _hiddenUnlocked = MutableStateFlow(false)
    val hiddenUnlocked: StateFlow<Boolean> = _hiddenUnlocked.asStateFlow()

    /** Stores the parental PIN hash and publishes that a PIN is configured. */
    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
        _pinIsSet.value = true
    }

    /** Removes the stored parental PIN and resets the unlocked state. */
    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
        _pinIsSet.value = false
        _hiddenUnlocked.value = true
    }

    /** Checks the supplied PIN against its saved hash; succeeds when no PIN is configured. */
    fun verifyPin(pin: String): Boolean {
        val stored = prefs.getString(KEY_PIN_HASH, null) ?: return true
        return stored == hashPin(pin)
    }

    /** Persists the hidden categories preference and updates its observable value. */
    fun setHiddenCategories(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_CATS, keys).apply()
        _hiddenCategories.value = keys.toSet()
    }

    /** Updates the in-memory parental unlock state for the current process. */
    fun setHiddenUnlocked(unlocked: Boolean) {
        _hiddenUnlocked.value = unlocked
    }

    /** Computes the stored digest representation of a parental PIN. */
    private fun hashPin(pin: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("opentv-pin::$pin".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Persists the theme mode preference and updates its observable value. */
    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    /** Persists the channel layout preference and updates its observable value. */
    fun setChannelLayout(layout: ChannelLayout) {
        prefs.edit().putString(KEY_CHANNEL_LAYOUT, layout.name).apply()
        _channelLayout.value = layout
    }

    /** Persists the subtitles enabled preference and updates its observable value. */
    fun setSubtitlesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUBTITLES, enabled).apply()
        _subtitlesEnabled.value = enabled
    }

    /** Persists the guide preview video preference and updates its observable value. */
    fun setGuidePreviewVideo(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_VIDEO, enabled).apply()
        _guidePreviewVideo.value = enabled
    }

    /** Persists the guide preview sound preference and updates its observable value. */
    fun setGuidePreviewSound(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREVIEW_SOUND, enabled).apply()
        _guidePreviewSound.value = enabled
    }

    /** Persists the persistent live playback preference and updates its observable value. */
    fun setPersistentLivePlayback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PERSISTENT_LIVE, enabled).apply()
        _persistentLivePlayback.value = enabled
    }

    /** Persists the live preview preference and updates its observable value. */
    fun setLivePreview(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_PREVIEW, enabled).apply()
        _livePreview.value = enabled
    }

    /** Persists the continue audio while browsing preference and updates its observable value. */
    fun setContinueAudioWhileBrowsing(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BROWSING_AUDIO, enabled).apply()
        _continueAudioWhileBrowsing.value = enabled
    }

    /** Whether launching the app jumps straight back to the last channel you watched. */
    private val _resumeLastChannel = MutableStateFlow(prefs.getBoolean(KEY_RESUME_LAST, false))
    val resumeLastChannel: StateFlow<Boolean> = _resumeLastChannel.asStateFlow()

    /** Persists the resume last channel preference and updates its observable value. */
    fun setResumeLastChannel(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESUME_LAST, enabled).apply()
        _resumeLastChannel.value = enabled
    }

    // ---- Recording behaviour -----------------------------------------------------------------

    /** Minutes to start a scheduled recording early, so a late start isn't clipped. */
    private val _recordPadStartMinutes = MutableStateFlow(prefs.getInt(KEY_PAD_START, 2))
    val recordPadStartMinutes: StateFlow<Int> = _recordPadStartMinutes.asStateFlow()

    /** Minutes to keep recording past the listed end, so an overrun (sport/news) isn't cut off. */
    private val _recordPadEndMinutes = MutableStateFlow(prefs.getInt(KEY_PAD_END, 5))
    val recordPadEndMinutes: StateFlow<Int> = _recordPadEndMinutes.asStateFlow()

    /** Persists the record padding preference and updates its observable value. */
    fun setRecordPadding(startMinutes: Int, endMinutes: Int) {
        val s = startMinutes.coerceIn(0, 30)
        val e = endMinutes.coerceIn(0, 60)
        prefs.edit().putInt(KEY_PAD_START, s).putInt(KEY_PAD_END, e).apply()
        _recordPadStartMinutes.value = s
        _recordPadEndMinutes.value = e
    }

    /**
     * When a scheduled recording starts, switch the box to it (playing from the growing file, so no
     * extra provider connection). Optional: account admission now releases only conflicting
     * viewing, so background recording leaves unrelated playback running by default.
     */
    private val _recordAutoSwitch = MutableStateFlow(prefs.getBoolean(KEY_REC_AUTOSWITCH, false))
    val recordAutoSwitch: StateFlow<Boolean> = _recordAutoSwitch.asStateFlow()

    /** Persists the record auto switch preference and updates its observable value. */
    fun setRecordAutoSwitch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REC_AUTOSWITCH, enabled).apply()
        _recordAutoSwitch.value = enabled
    }

    /**
     * Pause & rewind live TV via a rolling on-disk buffer of the current channel. Experimental and
     * heavier on weak boxes, so it's opt-in (off by default); when off, playback is unchanged.
     */
    private val _livePauseEnabled = MutableStateFlow(prefs.getBoolean(KEY_LIVE_PAUSE, false))
    val livePauseEnabled: StateFlow<Boolean> = _livePauseEnabled.asStateFlow()

    /** Persists the live pause enabled preference and updates its observable value. */
    fun setLivePauseEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LIVE_PAUSE, enabled).apply()
        _livePauseEnabled.value = enabled
    }

    // ---- Content types -----------------------------------------------------------------------

    /**
     * Which content types the user wants synced and shown. Turning one off skips fetching that
     * type's catalogue on the next sync (the speed-up) and hides its tab; already-synced rows are
     * left in place, so turning it back on and refreshing brings everything straight back.
     */
    private val _liveEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_LIVE, true))
    val liveEnabled: StateFlow<Boolean> = _liveEnabled.asStateFlow()

    private val _moviesEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_MOVIES, true))
    val moviesEnabled: StateFlow<Boolean> = _moviesEnabled.asStateFlow()

    private val _seriesEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTENT_SERIES, true))
    val seriesEnabled: StateFlow<Boolean> = _seriesEnabled.asStateFlow()

    /** Persists the live enabled preference and updates its observable value. */
    fun setLiveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONTENT_LIVE, enabled).apply()
        _liveEnabled.value = enabled
    }

    /** Persists the movies enabled preference and updates its observable value. */
    fun setMoviesEnabled(enabled: Boolean) {
        // Also reset the VOD freshness stamp so the next Movies/Shows open re-syncs immediately
        // rather than waiting out the cache TTL — turning a content type on should show it now.
        prefs.edit()
            .putBoolean(KEY_CONTENT_MOVIES, enabled)
            .putLong(KEY_VOD_SYNCED_AT, 0L)
            .apply()
        _moviesEnabled.value = enabled
    }

    /** Persists the series enabled preference and updates its observable value. */
    fun setSeriesEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CONTENT_SERIES, enabled)
            .putLong(KEY_VOD_SYNCED_AT, 0L)
            .apply()
        _seriesEnabled.value = enabled
    }

    /**
     * UI language override. Blank = follow the device; otherwise a BCP-47 tag ("en", "es").
     * Applied at [android.content.ContextWrapper.attachBaseContext] time so the whole app —
     * including notifications built off the app context — picks it up.
     */
    private val _languageTag = MutableStateFlow(prefs.getString(KEY_LANGUAGE, "").orEmpty())
    val languageTag: StateFlow<String> = _languageTag.asStateFlow()

    /** Persists the language tag preference and updates its observable value. */
    fun setLanguageTag(tag: String) {
        prefs.edit().putString(KEY_LANGUAGE, tag).apply()
        _languageTag.value = tag
    }

    /** The last channel played, for boot-to-last-channel. Not a flow — only read once at launch. */
    var lastChannelId: Long
        get() = prefs.getLong(KEY_LAST_CHANNEL, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_CHANNEL, value).apply() }

    /**
     * Video scaling in the player, as an [androidx.media3.ui.AspectRatioFrameLayout] RESIZE_MODE_*
     * constant (0 = Fit). Persisted so the choice survives leaving the player, which testers asked
     * for — picking Fill every single time you open a channel gets old fast.
     */
    private val _playerResizeMode = MutableStateFlow(prefs.getInt(KEY_RESIZE_MODE, 0))
    val playerResizeMode: StateFlow<Int> = _playerResizeMode.asStateFlow()

    /** Persists the player resize mode preference and updates its observable value. */
    fun setPlayerResizeMode(mode: Int) {
        prefs.edit().putInt(KEY_RESIZE_MODE, mode).apply()
        _playerResizeMode.value = mode
    }

    /** Reads the saved theme mode with a default for unsupported values. */
    private fun readThemeMode(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)

    /** Reads the saved channel layout with a default for unsupported values. */
    private fun readChannelLayout(): ChannelLayout =
        runCatching { ChannelLayout.valueOf(prefs.getString(KEY_CHANNEL_LAYOUT, null) ?: "") }
            .getOrDefault(ChannelLayout.GRID)

    // ---- Recording ---------------------------------------------------------------------------

    /**
     * Where recordings are written: this box's internal storage, a NAS over SMB, or a plugged-in
     * USB / external drive addressed through the Storage Access Framework (a granted tree URI —
     * the only way to write removable storage on modern Android without a raw filesystem path).
     */
    enum class RecordingTarget { INTERNAL, SMB, USB }

    private val _recordingTarget = MutableStateFlow(readRecordingTarget())
    val recordingTarget: StateFlow<RecordingTarget> = _recordingTarget.asStateFlow()

    /**
     * The SAF tree URI the user granted for USB recordings (a `content://` document-tree URI), or
     * null if none has been picked. Persisted as a string; a matching persistable permission is
     * taken when it is chosen, so the grant survives restarts.
     */
    private val _usbTreeUri = MutableStateFlow(prefs.getString(KEY_USB_TREE, null))
    val usbTreeUri: StateFlow<String?> = _usbTreeUri.asStateFlow()

    /** A friendly name for the chosen USB folder (e.g. the DocumentFile's name), for display. */
    private val _usbFolderLabel = MutableStateFlow(prefs.getString(KEY_USB_LABEL, null))
    val usbFolderLabel: StateFlow<String?> = _usbFolderLabel.asStateFlow()

    /** Save (or clear, with null) the granted USB tree URI and its display label together. */
    fun setUsbTree(treeUri: String?, label: String?) {
        prefs.edit()
            .putString(KEY_USB_TREE, treeUri)
            .putString(KEY_USB_LABEL, label)
            .apply()
        _usbTreeUri.value = treeUri
        _usbFolderLabel.value = label
    }

    /** SMB / NAS connection. Stored on-device only, exactly like provider credentials. */
    private val _smbHost = MutableStateFlow(prefs.getString(KEY_SMB_HOST, "").orEmpty())
    val smbHost: StateFlow<String> = _smbHost.asStateFlow()

    private val _smbShare = MutableStateFlow(prefs.getString(KEY_SMB_SHARE, "").orEmpty())
    val smbShare: StateFlow<String> = _smbShare.asStateFlow()

    /** Sub-folder within the share, e.g. `Recordings`. Blank = share root. */
    private val _smbFolder = MutableStateFlow(prefs.getString(KEY_SMB_FOLDER, "Nuvix").orEmpty())
    val smbFolder: StateFlow<String> = _smbFolder.asStateFlow()

    private val _smbUser = MutableStateFlow(prefs.getString(KEY_SMB_USER, "").orEmpty())
    val smbUser: StateFlow<String> = _smbUser.asStateFlow()

    private val _smbPassword = MutableStateFlow(privateSettings.getString(KEY_SMB_PASS, "").orEmpty())
    val smbPassword: StateFlow<String> = _smbPassword.asStateFlow()

    /** Persists the recording target preference and updates its observable value. */
    fun setRecordingTarget(target: RecordingTarget) {
        prefs.edit().putString(KEY_REC_TARGET, target.name).apply()
        _recordingTarget.value = target
    }

    /** Saves NAS connection fields, encrypts the password and publishes the updated local configuration. */
    fun setSmbConfig(host: String, share: String, folder: String, user: String, password: String) {
        privateSettings.putString(KEY_SMB_PASS, password)
        prefs.edit()
            .putString(KEY_SMB_HOST, host.trim())
            .putString(KEY_SMB_SHARE, share.trim())
            .putString(KEY_SMB_FOLDER, folder.trim())
            .putString(KEY_SMB_USER, user)
            .apply()
        _smbHost.value = host.trim()
        _smbShare.value = share.trim()
        _smbFolder.value = folder.trim()
        _smbUser.value = user
        _smbPassword.value = password
    }

    /** Reads the saved recording destination with a default for unsupported values. */
    private fun readRecordingTarget(): RecordingTarget =
        runCatching { RecordingTarget.valueOf(prefs.getString(KEY_REC_TARGET, null) ?: "") }
            .getOrDefault(RecordingTarget.INTERNAL)

    // ---- NAS ("cloud") sync ------------------------------------------------------------------

    /**
     * A stable, random id for this install. It names this device's bundle file in the NAS sync
     * folder, so every device writes its own file and reads the others'. Generated once, on first
     * read, then persisted — distinct per device without needing any hardware identifier.
     */
    val syncDeviceId: String
        get() = prefs.getString(KEY_SYNC_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit().putString(KEY_SYNC_DEVICE_ID, it).apply() }

    /** Whether to run a NAS sync automatically each time the app is opened. Off by default. */
    private val _nasAutoSync = MutableStateFlow(prefs.getBoolean(KEY_NAS_AUTO_SYNC, false))
    val nasAutoSync: StateFlow<Boolean> = _nasAutoSync.asStateFlow()

    /** Persists the nas auto sync preference and updates its observable value. */
    fun setNasAutoSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NAS_AUTO_SYNC, enabled).apply()
        _nasAutoSync.value = enabled
    }

    // ---- Catalogue freshness & TMDB ----------------------------------------------------------

    /**
     * When the VOD (movies + series) catalogue was last fetched from the provider, in epoch
     * millis; 0 = never. A provider's 40k-title VOD list is expensive to re-download and
     * re-write, so [app.opentv.ui.VodViewModel.ensureVodLoaded] uses this to skip the sync on a
     * warm launch and show the already-stored rows instantly — the fetch only runs on first load
     * or once this goes stale. Not a flow: it is read once when Movies/Shows is first opened.
     */
    var vodSyncedAtMillis: Long
        get() = prefs.getLong(KEY_VOD_SYNCED_AT, 0L)
        set(value) { prefs.edit().putLong(KEY_VOD_SYNCED_AT, value).apply() }

    /**
     * The user's own TMDB API key (v3 auth), stored on-device only exactly like provider
     * credentials, used to fill in artwork/metadata a provider left blank. Blank = the TMDB
     * fallback is off. Each user brings their own key, so no single key carries everyone's traffic.
     */
    private val _tmdbApiKey = MutableStateFlow(privateSettings.getString(KEY_TMDB_KEY, "").orEmpty())
    val tmdbApiKey: StateFlow<String> = _tmdbApiKey.asStateFlow()

    /** Persists the tmdb api key preference and updates its observable value. */
    fun setTmdbApiKey(key: String) {
        val trimmed = key.trim()
        privateSettings.putString(KEY_TMDB_KEY, trimmed)
        _tmdbApiKey.value = trimmed
    }

    // ---- Stremio add-ons ---------------------------------------------------------------------

    /**
     * The user's Stremio add-ons, stored on-device as JSON. OpenTV ships with none; each entry is a
     * manifest URL the user pasted (a debrid key, if any, is baked into that URL on the add-on's own
     * site — never entered here). Empty = the whole feature stays inert.
     */
    private val _stremioAddons = MutableStateFlow(readStremioAddons())
    val stremioAddons: StateFlow<List<StremioAddon>> = _stremioAddons.asStateFlow()

    /** Adds an add-on to the saved list and publishes the updated selection. */
    fun addStremioAddon(addon: StremioAddon) {
        val deduped = _stremioAddons.value.filterNot { it.manifestUrl.equals(addon.manifestUrl, ignoreCase = true) }
        persistStremioAddons(deduped + addon)
    }

    /** Removes the add-on identified by its manifest URL. */
    fun removeStremioAddon(manifestUrl: String) {
        persistStremioAddons(_stremioAddons.value.filterNot { it.manifestUrl == manifestUrl })
    }

    /** Stores the serialized add-on list in the private settings vault. */
    private fun persistStremioAddons(list: List<StremioAddon>) {
        privateSettings.putString(KEY_STREMIO_ADDONS, addonJson.encodeToString(list))
        _stremioAddons.value = list
    }

    /** Decodes saved add-ons, using an empty list when none can be read. */
    private fun readStremioAddons(): List<StremioAddon> {
        // A corrupt JSON value can be retained for recovery; authentication/key failures must
        // propagate instead of silently replacing a user's encrypted add-ons with an empty list.
        val serialized = privateSettings.getString(KEY_STREMIO_ADDONS, null) ?: return emptyList()
        return runCatching { addonJson.decodeFromString<List<StremioAddon>>(serialized) }.getOrNull() ?: emptyList()
    }

    companion object {
        private const val KEY_AUTO_EPG = "automatic_epg"
        private const val KEY_EPG_SUPPRESSED = "epg_suppressed_feed_hashes"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_CHANNEL_LAYOUT = "channel_layout"
        private const val KEY_GUIDE_LAYOUT = "guide_layout"
        private const val KEY_GUIDE_DENSITY = "guide_density"
        private const val KEY_GUIDE_FILTER = "guide_default_filter"
        private const val KEY_GUIDE_SORT = "guide_sort"
        private const val KEY_GUIDE_ARTWORK = "guide_show_artwork"
        private const val KEY_GUIDE_PROGRESS = "guide_show_progress"
        private const val KEY_GUIDE_REMEMBER = "guide_remember_position"
        private const val KEY_GUIDE_GROUP = "guide_filter_group_id"
        private const val KEY_GUIDE_PROVIDER = "guide_filter_provider_id"
        private const val KEY_GUIDE_POSITION_CHANNEL = "guide_position_channel_id"
        private const val KEY_GUIDE_POSITION_TIME = "guide_position_time_ms"
        private const val KEY_SUBTITLES = "subtitles_enabled"
        private const val KEY_PREVIEW_VIDEO = "guide_preview_video"
        private const val KEY_PREVIEW_SOUND = "guide_preview_sound"
        private const val KEY_PERSISTENT_LIVE = "persistent_live_playback"
        private const val KEY_LIVE_PREVIEW = "live_preview"
        private const val KEY_BROWSING_AUDIO = "continue_audio_while_browsing"
        private const val KEY_PIN_HASH = "parental_pin_hash"
        private const val KEY_HIDDEN_CATS = "hidden_categories"
        private const val KEY_ACTIVE_PROFILE = "active_profile_id"
        private const val KEY_RESUME_LAST = "resume_last_channel"
        private const val KEY_CONTENT_LIVE = "content_live"
        private const val KEY_CONTENT_MOVIES = "content_movies"
        private const val KEY_CONTENT_SERIES = "content_series"
        private const val KEY_LAST_CHANNEL = "last_channel_id"
        private const val KEY_RESIZE_MODE = "player_resize_mode"
        private const val KEY_LANGUAGE = "language_tag"

        /**
         * Reads the saved language tag straight from prefs, for use in attachBaseContext before
         * the settings singleton (or anything else) is initialised. Blank = follow the device.
         */
        fun savedLanguageTag(context: Context): String =
            context.getSharedPreferences("opentv_settings", Context.MODE_PRIVATE)
                .getString(KEY_LANGUAGE, "").orEmpty()
        private const val KEY_REC_TARGET = "recording_target"
        private const val KEY_SMB_HOST = "smb_host"
        private const val KEY_SMB_SHARE = "smb_share"
        private const val KEY_SMB_FOLDER = "smb_folder"
        private const val KEY_SMB_USER = "smb_user"
        private const val KEY_SMB_PASS = "smb_password"
        private const val KEY_USB_TREE = "usb_tree_uri"
        private const val KEY_USB_LABEL = "usb_folder_label"
        private const val KEY_SYNC_DEVICE_ID = "sync_device_id"
        private const val KEY_NAS_AUTO_SYNC = "nas_auto_sync"
        private const val KEY_VOD_SYNCED_AT = "vod_synced_at"
        private const val KEY_TMDB_KEY = "tmdb_api_key"
        private const val KEY_STREMIO_ADDONS = "stremio_addons"
        private const val KEY_PAD_START = "rec_pad_start_min"
        private const val KEY_PAD_END = "rec_pad_end_min"
        private const val KEY_REC_AUTOSWITCH = "rec_auto_switch"
        private const val KEY_LIVE_PAUSE = "live_pause_enabled"

        @Volatile private var instance: AppSettings? = null

        /** Returns the process-wide application settings store. */
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
    }
}
