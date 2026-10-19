/* Nuvix modifications Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv

import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.collect

import com.disabledabel.nuvix.R

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.opentv.player.LivePlaybackSession
import app.opentv.player.LivePresentation
import app.opentv.ui.player.LivePlaybackPreview
import app.opentv.core.findActivity
import app.opentv.core.AppSettings
import app.opentv.core.ServiceLocator
import app.opentv.data.parser.displayTitle
import app.opentv.ui.MainScreen
import app.opentv.ui.ProfilesViewModel
import app.opentv.ui.SourcesViewModel
import app.opentv.ui.channels.ChannelManagerScreen
import app.opentv.ui.channels.ProgrammeDetailsDialog
import app.opentv.ui.ChannelsViewModel
import app.opentv.ui.channels.SearchScreen
import app.opentv.ui.VodViewModel
import app.opentv.ui.onboarding.AddSourceScreen
import app.opentv.ui.player.PlayerScreen
import app.opentv.ui.settings.AboutScreen
import app.opentv.ui.settings.AppSettingsScreen
import app.opentv.ui.settings.EpgSettingsScreen
import app.opentv.ui.settings.ParentalControlsScreen
import app.opentv.ui.settings.ProfilesScreen
import app.opentv.ui.settings.ProvidersScreen
import app.opentv.ui.settings.StremioAddonsScreen
import app.opentv.ui.settings.RecordingSettingsScreen
import app.opentv.ui.settings.SyncScreen
import app.opentv.ui.settings.SettingsHubScreen
import app.opentv.ui.settings.WebManagerScreen
import app.opentv.ui.theme.OpenTvTheme
import app.opentv.ui.vod.MovieDetailScreen
import app.opentv.ui.vod.PersonScreen
import app.opentv.ui.vod.SeriesDetailScreen
import app.opentv.ui.vod.VodPlayerScreen
import app.opentv.update.UpdateGate

/** Hosts TV navigation, live playback, locale selection and picture-in-picture transitions. */
class MainActivity : ComponentActivity() {
    private var homeGeneration by mutableIntStateOf(0)
    private var liveSession: LivePlaybackSession? = null


    /** Applies the saved UI locale before Android inflates activity resources. */
    // Apply the chosen UI language before any view or resource is resolved. A language change in
    // settings calls recreate(), which re-runs this with the new tag.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(app.opentv.core.LocaleUtils.wrap(newBase))
    }

    /** Initializes the Compose host and handles any incoming playback request. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handlePlayIntent(intent)
        val isTelevision = isRunningOnTelevision(this)

        setContent {
            val settings by androidx.compose.runtime.produceState<AppSettings?>(initialValue = null) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ServiceLocator.get(this@MainActivity).settings }.getOrNull()
                }
            }
            val themeMode = settings?.themeMode?.collectAsState()?.value ?: AppSettings.ThemeMode.DARK
            val darkTheme = when (themeMode) {
                AppSettings.ThemeMode.DARK -> true
                AppSettings.ThemeMode.LIGHT -> false
                // A living-room screen defaults to dark; a phone/tablet follows the system.
                AppSettings.ThemeMode.SYSTEM -> isTelevision || isSystemInDarkTheme()
            }
            OpenTvTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        NuvixNavigation(
                            isTelevision = isTelevision, homeGeneration = homeGeneration,
                            restoringNavigation = savedInstanceState != null,
                            onLiveSession = { session ->
                                if (liveSession !== session) {
                                    liveSession = session
                                    session.setForeground(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
                                }
                            },
                        )
                        // Available even if protected local data cannot open after a bad update.
                        UpdateGate()
                    }
                }
            }
        }
    }

    /** Marks the activity as foreground for live playback policy. */
    override fun onStart() {
        super.onStart()
        liveSession?.setForeground(true)
    }

    /** Updates live playback policy when the activity leaves the foreground. */
    override fun onStop() {
        // A retained ViewModel keeps the same player during configuration changes. Process death
        // creates a new owner and only the existing startup preference may request a new stream.
        if (!isChangingConfigurations) liveSession?.setForeground(false)
        super.onStop()
    }

    /** Passes Android memory pressure to the shared live playback session. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        liveSession?.onMemoryPressure(level)
    }

    /** Releases optional live resources when Android reports low memory. */
    override fun onLowMemory() {
        super.onLowMemory()
        liveSession?.onMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** Detaches the activity from the shared live playback session. */
    override fun onDestroy() {
        val session = liveSession
        // A device that rejected surface cleanup cannot retain a View into a destroyed Activity.
        if (session != null && !session.surfaceOwner.clear()) session.stop()
        if (session != null && !session.multiView.clearSurfaces()) session.multiView.stop()
        liveSession = null
        super.onDestroy()
    }

    /** Handles playback and home intents delivered to the existing activity. */
    // singleTask: a reminder tapped while the app is already running arrives here, not a fresh
    // onCreate. Either way the requested channel is handed to the nav graph via [PlayRequests].
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayIntent(intent)
    }

    /** Consumes an explicit channel request from an intent and forwards it to navigation. */
    private fun handlePlayIntent(intent: Intent?) {
        app.opentv.update.UpdateScheduler.handleIntent(this, intent)
        if (app.opentv.launcher.LauncherController.isHomeIntent(intent)) homeGeneration++
        val id = intent?.getLongExtra(EXTRA_PLAY_CHANNEL, 0L) ?: 0L
        if (id != 0L) app.opentv.core.PlayRequests.request(id)
        val recId = intent?.getLongExtra(EXTRA_WATCH_RECORDING, 0L) ?: 0L
        if (recId != 0L) {
            app.opentv.core.RecordingSignals.requestWatch(recId, System.currentTimeMillis())
        }
        val reviewId = intent?.getLongExtra(EXTRA_REVIEW_RECORDING, 0L) ?: 0L
        if (reviewId > 0) app.opentv.core.RecordingSignals.requestReview(reviewId)
    }

    // ---- Picture-in-picture ------------------------------------------------------------------

    /** Home pressed while a programme is playing → shrink to a floating window instead of stopping. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeEnterPip()
    }

    /** Checks platform and device support for picture-in-picture. */
    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Attempts picture-in-picture only when playback and device state allow it. */
    fun maybeEnterPip() {
        if (!app.opentv.core.PipState.eligible || !app.opentv.core.PipState.isPlaying) return
        enterPipNow()
    }

    /** Explicit request from the player's PiP button — no eligibility guard, the user asked. */
    fun enterPipNow() {
        if (!supportsPip()) return
        val multiView = liveSession?.multiView
        val hasMultiView = multiView?.state?.value?.panels?.isNotEmpty() == true
        val previousFullscreen = multiView?.state?.value?.fullscreenPanelId
        if (hasMultiView) multiView?.setInPip(true)
        val entered = runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build(),
            )
        }.getOrDefault(false)
        if (!entered && hasMultiView) {
            multiView?.setInPip(false)
            if (previousFullscreen == null) multiView?.leaveFullscreen()
        }
    }

    /** Exposes device picture-in-picture availability to Compose controls. */
    fun pipSupported(): Boolean = supportsPip()

    /** Synchronizes system picture-in-picture state with playback presentation. */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        app.opentv.core.PipState.setInPip(isInPictureInPictureMode)
        liveSession?.setInPip(isInPictureInPictureMode)
    }

    companion object {
        /** A reminder notification carries the channel to tune to in this extra. */
        const val EXTRA_PLAY_CHANNEL = "opentv.play_channel"

        /** A recording auto-switch notification carries the recording to watch in this extra. */
        const val EXTRA_WATCH_RECORDING = "opentv.watch_recording"
        const val EXTRA_REVIEW_RECORDING = "nuvix.review_recording"
    }
}

/** Defines navigation destinations and builders for parameterized routes. */
object Routes {
    const val HOME = "home"
    const val ADD_SOURCE = "add-source"
    const val PLAYER = "player/{channelId}"
    const val SEARCH = "search"
    const val MULTIVIEW = "multiview"
    const val EPG_SETTINGS = "epg-settings"
    const val APP_SETTINGS = "app-settings"
    const val SETTINGS_HUB = "settings"
    const val PROVIDERS = "providers"
    const val ADDONS = "addons"
    const val CHANNELS = "channels"
    const val WEB_MANAGER = "web-manager"
    const val PROFILES = "profiles"
    const val PARENTAL = "parental"
    const val SYNC = "sync"
    const val REC_SETTINGS = "recording-settings"
    const val ABOUT = "about"
    const val LAUNCHER_SETTINGS = "launcher-settings"
    const val CLOUD_SYNC = "cloud-sync"
    const val BUFFER_SETTINGS = "buffer-settings"
    const val UPDATE_SETTINGS = "update-settings"
    const val SERIES_DETAIL = "series/{seriesId}"
    const val MOVIE_DETAIL = "movie/{movieId}"
    const val EDIT_SOURCE = "edit-source/{sourceId}"

    // A person's name goes in a query arg, URL-encoded, so spaces and punctuation survive the round
    // trip — the same inline-encode/decode approach as the VOD player below.
    const val PERSON = "person?name={name}"

    // VOD plays carry the stream inline; a movie/episode is a one-off URL, not a stored id
    // the player can look up the way a channel is.
    const val VOD_PLAYER = "vod?key={key}&url={url}&title={title}&ua={ua}"

    /** Builds the fullscreen live player route for a channel row ID. */
    fun player(channelId: Long) = "player/$channelId"
    /** Builds the detail route for a provider series row ID. */
    fun seriesDetail(seriesId: Long) = "series/$seriesId"
    /** Builds the detail route for a provider movie row ID. */
    fun movieDetail(movieId: Long) = "movie/$movieId"
    /** Builds the provider editor route for a source row ID. */
    fun editSource(sourceId: Long) = "edit-source/$sourceId"
    /** Encodes a person identifier for the metadata profile route. */
    fun person(name: String) = "person?name=${java.net.URLEncoder.encode(name, "UTF-8")}"
    /** Encodes media identity, URL, title and user agent into the VOD playback route. */
    fun vodPlayer(key: String, url: String, title: String, ua: String): String {
        fun e(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        return "vod?key=${e(key)}&url=${e(url)}&title=${e(title)}&ua=${e(ua)}"
    }
}

/** Connects navigation destinations to shared view models and the live playback session. */
@Composable
private fun NuvixNavigation(
    isTelevision: Boolean,
    homeGeneration: Int,
    restoringNavigation: Boolean,
    onLiveSession: (LivePlaybackSession) -> Unit,
) {
    val startupContext = androidx.compose.ui.platform.LocalContext.current
    val initialHomeGeneration = remember { homeGeneration }
    val recoveryUpdater: app.opentv.update.UpdateViewModel = viewModel()
    var databaseReady by remember { mutableStateOf(false) }
    var databaseFailure by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val graph = ServiceLocator.get(startupContext)
                graph.settings
                graph.database.openHelper.writableDatabase
            }
        }
        databaseReady = result.isSuccess
        databaseFailure = result.isFailure
    }
    if (!databaseReady) {
        if (databaseFailure) {
            androidx.compose.foundation.layout.Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
            ) {
                Text("Your local data could not be opened", style = MaterialTheme.typography.headlineMedium)
                Text("Nuvix has retained the existing database. Check available device storage and restart the app. If the device encryption key was lost, contact support before resetting or uninstalling.")
                androidx.compose.material3.OutlinedButton(onClick = {
                    runCatching { startupContext.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                }) { Text("Open device settings") }
                androidx.compose.material3.OutlinedButton(onClick = recoveryUpdater::checkNow) { Text("Check for a recovery update") }
                androidx.compose.material3.OutlinedButton(onClick = { startupContext.findActivity()?.finish() }) { Text("Close Nuvix") }
            }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // Created outside NavHost: the Activity owns this ViewModel, not a player back-stack entry.
    val liveSession: LivePlaybackSession = viewModel()
    SideEffect { onLiveSession(liveSession) }
    val connectionPrompt by liveSession.connectionPrompt.collectAsState()
    connectionPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = { liveSession.answerConnectionPrompt(false) },
            title = { Text("Open an additional connection?") },
            text = { Text("The simultaneous connection allowance for this account is unknown. Open ${prompt.channelName} for this request? Other devices may also be using this account. Extra capacity is not guaranteed.") },
            confirmButton = { TextButton(onClick = { liveSession.answerConnectionPrompt(true) }) { Text("Open once") } },
            dismissButton = { TextButton(onClick = { liveSession.answerConnectionPrompt(false) }) { Text("Cancel") } },
        )
    }
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val multiView = liveSession.multiView
    val multiViewState by multiView.state.collectAsState()
    val recordingWarning by liveSession.recordingConflicts.warning.collectAsState()
    val durationRecording by app.opentv.core.RecordingSignals.duration.collectAsState()
    durationRecording?.let { id -> app.opentv.ui.recordings.RecordingDurationDialog(id, app.opentv.core.RecordingSignals::consumeDuration) }
    val recordingReview by app.opentv.core.RecordingSignals.review.collectAsState()
    recordingReview?.let { id ->
        val reviewContext = LocalContext.current
        var recording by remember(id) { mutableStateOf<app.opentv.data.model.Recording?>(null) }
        LaunchedEffect(id) {
            ServiceLocator.get(reviewContext).recordingRepository.observeAll().collect { rows -> recording = rows.firstOrNull { it.id == id } }
        }
        AlertDialog(onDismissRequest = app.opentv.core.RecordingSignals::consumeReview,
            title = { Text(recording?.title ?: "Recording details") },
            text = { Text(recording?.let { "${it.channelName} · ${it.status}\nRequested padded start: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.scheduledStartMillis))}\nThe effective start may change under recording conflict policy.\nProtect Recording: ${it.protectedRecording} · Lock Padding: ${it.lockPadding}\nScheduled recordings retain their existing priority at the deadline." } ?: "This recording is no longer available.") },
            dismissButton = { if (recording?.status == app.opentv.data.model.RecordingStatus.RECORDING) TextButton(onClick = {
                app.opentv.core.RecordingSignals.consumeReview()
                app.opentv.core.RecordingSignals.requestDuration(id)
            }) { Text("Recording Duration") } },
            confirmButton = { TextButton(onClick = app.opentv.core.RecordingSignals::consumeReview) { Text("Close") } })
    }
    if (recordingReview == null) app.opentv.ui.recordings.RecordingConflictHost(liveSession.recordingConflicts, multiView)
    var guideRequestGeneration by rememberSaveable { mutableIntStateOf(0) }
    var mainLivePresentation by rememberSaveable { mutableStateOf(LivePresentation.BROWSING) }
    var guideVisible by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(navController, liveSession) {
        val listener = androidx.navigation.NavController.OnDestinationChangedListener { _, destination, _ ->
            when (destination.route) {
                Routes.PLAYER -> liveSession.setPresentation(LivePresentation.FULLSCREEN)
                Routes.HOME -> liveSession.setPresentation(mainLivePresentation)
                Routes.SEARCH -> liveSession.setPresentation(LivePresentation.BROWSING)
                Routes.VOD_PLAYER -> liveSession.stop() // release provider connection before VOD allocates
                else -> liveSession.setPresentation(LivePresentation.HIDDEN)
            }
            if (destination.route != Routes.HOME) multiView.setGuideOpen(false)
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }
    fun openLive(id: Long) {
        if (multiView.state.value.panels.isNotEmpty()) {
            multiView.watchFullscreen(id)
            navController.navigate(Routes.MULTIVIEW) { launchSingleTop = true }
            return
        }
        // Accept the request before navigation, cancelling obsolete lookups and guide preview.
        liveSession.setPresentation(LivePresentation.FULLSCREEN)
        liveSession.openChannel(id)
        val replacingFullscreen = navController.currentDestination?.route == Routes.PLAYER
        navController.navigate(Routes.player(id)) {
            launchSingleTop = true
            if (replacingFullscreen) popUpTo(Routes.PLAYER) { inclusive = true }
        }
    }
    fun openVod(route: String) {
        liveSession.stop()
        navController.navigate(route) { launchSingleTop = true }
    }
    fun openMultiView(channel: app.opentv.data.model.Channel? = null) {
        val settings = ServiceLocator.get(startupContext).settings
        if (!settings.multiViewPreferences.value.enabled) return
        val initialId = channel?.id ?: if (multiView.state.value.panels.isEmpty()) liveSession.currentChannel.value?.id else null
        if (initialId != null) multiView.add(initialId)
        navController.navigate(Routes.MULTIVIEW) { launchSingleTop = true }
    }
    val sourcesViewModel: SourcesViewModel = viewModel()
    val vodViewModel: VodViewModel = viewModel()
    val profilesViewModel: ProfilesViewModel = viewModel()
    val sourcesUi by sourcesViewModel.ui.collectAsState()
    val profiles by profilesViewModel.profiles.collectAsState()
    val activeProfileId by profilesViewModel.activeProfileId.collectAsState()
    val activeProfileName = profiles.firstOrNull { it.id == activeProfileId }?.name ?: "Me"

    // Until the saved sources have loaded from the database, we cannot tell a first run from a
    // returning user — and guessing "first run" drops a returning user on the setup screen and
    // asks for their provider again. NavHost locks in its start destination on first
    // composition, so wait for that first load before building it.
    if (!sourcesUi.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // First run goes straight to setup — an empty channel list with no explanation is the
    // worst possible first impression.
    val start = Routes.HOME

    // Boot to last channel: if enabled and we have one, jump straight into the player on launch.
    // Runs once; backing out returns to the guide and doesn't re-trigger.
    val bootContext = androidx.compose.ui.platform.LocalContext.current
    val bootSettings = remember { ServiceLocator.get(bootContext).settings }
    val multiViewPreferences by bootSettings.multiViewPreferences.collectAsState()
    val recoveryStore = remember { app.opentv.multiview.MultiViewPresetStore(bootContext) }
    val interruptedMultiView by recoveryStore.recovery.collectAsState()
    var recoveryOffered by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(recoveryStore) { onDispose { recoveryStore.close() } }
    val recoveryPending = !recoveryOffered && interruptedMultiView != null && multiViewState.panels.isEmpty() && multiViewPreferences.enabled &&
        multiViewPreferences.recovery == app.opentv.multiview.MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE
    SideEffect { liveSession.setRecoveryReviewPending(recoveryPending) }
    if (!recoveryOffered && recoveryPending && currentEntry != null && currentEntry?.destination?.route != Routes.MULTIVIEW &&
        multiViewPreferences.recovery == app.opentv.multiview.MultiViewRecoveryPolicy.ASK_BEFORE_RESTORE) {
        AlertDialog(
            onDismissRequest = { recoveryOffered = true },
            title = { Text("Review interrupted Multi-View?") },
            text = { Text("Your previous layout is available to review. No channels will open until you confirm; provider capacity and device resources will be checked again.") },
            confirmButton = { TextButton(onClick = {
                recoveryOffered = true
                navController.navigate(Routes.MULTIVIEW) { launchSingleTop = true }
            }) { Text("Review & Restore") } },
            dismissButton = { TextButton(onClick = {
                recoveryStore.clearRecovery(); recoveryOffered = true
            }) { Text("Dismiss") } },
        )
    }
    val launcherSettings = remember { app.opentv.launcher.LauncherSettingsStore(bootContext) }
    val restoringAfterProcessDeath = remember { restoringNavigation && !liveSession.startupNavigationHandled }
    DisposableEffect(launcherSettings) { onDispose { launcherSettings.close() } }
    LaunchedEffect(homeGeneration) {
        if (homeGeneration != initialHomeGeneration) navController.navigate(Routes.HOME) {
            launchSingleTop = true
            popUpTo(Routes.HOME) { inclusive = false }
        }
        if (liveSession.startupNavigationHandled) return@LaunchedEffect
        liveSession.startupNavigationHandled = true
        val destination = launcherSettings.preferences.value.startupDestination
        val resumeLast = !recoveryPending && (destination == app.opentv.launcher.StartupDestination.LAST_WATCHED ||
            (destination == app.opentv.launcher.StartupDestination.HOME && bootSettings.resumeLastChannel.value))
        val startupChannel = if (resumeLast && bootSettings.lastChannelId != 0L) {
            ServiceLocator.get(bootContext).catalogRepository.channel(bootSettings.lastChannelId)
        } else null
        if (homeGeneration == initialHomeGeneration && resumeLast && sourcesUi.sources.isNotEmpty() &&
            startupChannel != null && !liveSession.active.value && multiView.state.value.panels.isEmpty()) {
            openLive(startupChannel.id)
        } else if (restoringAfterProcessDeath && !liveSession.active.value &&
            navController.currentDestination?.route == Routes.PLAYER) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    val liveActive by liveSession.active.collectAsState()
    val route = currentEntry?.destination?.route
    val liveRoute = route == Routes.PLAYER
    val previewVideo by bootSettings.livePreview.collectAsState()
    val guideVideo by bootSettings.guidePreviewVideo.collectAsState()
    val previewSuppressed by liveSession.guidePreviewSuppressed.collectAsState()
    val keepAwake = route == Routes.PLAYER || route == Routes.VOD_PLAYER || route == Routes.MULTIVIEW ||
        (multiViewState.panels.isNotEmpty() && route == Routes.HOME) ||
        (previewVideo && ((liveActive && (route == Routes.SEARCH ||
            (route == Routes.HOME && mainLivePresentation == LivePresentation.BROWSING))) ||
            (route == Routes.HOME && guideVisible && guideVideo && !previewSuppressed)))
    DisposableEffect(keepAwake) {
        val window = bootContext.findActivity()?.window
        if (keepAwake) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(liveSession, liveRoute, liveActive, route, multiViewState) {
        val selectedPanel = multiViewState.audioPanelId ?: multiViewState.focusedPanelId
        val player = if (liveRoute && liveActive) liveSession.controller.player
            else if (route == Routes.MULTIVIEW && selectedPanel != null) multiView.player(selectedPanel) else null
        val listener = object : androidx.media3.common.Player.Listener {
            fun update() {
                app.opentv.core.PipState.eligible = player != null
                app.opentv.core.PipState.isPlaying = player?.isPlaying == true
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) = update()
        }
        if (player != null) {
            player.addListener(listener)
            listener.update()
        }
        onDispose {
            player?.removeListener(listener)
            if (player != null) {
                app.opentv.core.PipState.eligible = false
                app.opentv.core.PipState.isPlaying = false
            }
        }
    }
    // Stop/sleep/memory fallback cannot leave an inactive screen that lazily recreates a player.
    LaunchedEffect(liveRoute, liveActive) {
        if (liveRoute && !liveActive && !navController.popBackStack()) {
            navController.navigate(Routes.HOME) { launchSingleTop = true }
        }
    }

    // A tapped reminder notification asks for a specific channel. Consume it so it fires once and
    // never re-triggers on a later launch.
    val playRequest by app.opentv.core.PlayRequests.channelId.collectAsState()
    LaunchedEffect(playRequest) {
        val id = playRequest
        if (id != null && id != 0L) {
            app.opentv.core.PlayRequests.consume()
            openLive(id)
        }
    }

    // Auto-switch: a scheduled recording started, so move the live view onto the recording file
    // (single-connection safe). Ignore a stale request a long-backgrounded app only now sees.
    val watchRequest by app.opentv.core.RecordingSignals.watch.collectAsState()
    LaunchedEffect(watchRequest) {
        val req = watchRequest ?: return@LaunchedEffect
        app.opentv.core.RecordingSignals.consumeWatch()
        val fresh = System.currentTimeMillis() - req.requestedAtMillis < 10 * 60_000L
        if (req.recordingId != 0L && fresh) {
            val rec = runCatching {
                ServiceLocator.get(bootContext).recordingRepository.byId(req.recordingId)
            }.getOrNull() ?: return@LaunchedEffect
            liveSession.stop()
            runCatching {
                navController.navigate(
                    Routes.vodPlayer(
                        key = "rec:${rec.id}",
                        url = "optvrec://${rec.id}",
                        title = rec.title,
                        ua = rec.userAgent,
                    ),
                ) {
                    launchSingleTop = true
                    // Drop the live channel we switched away from off the back stack, so Back from
                    // the recording returns to the home screen — never to a live stream. On a single-
                    // connection line, returning to live TV while still recording would open a second
                    // stream and risk the provider banning the account.
                    popUpTo(Routes.HOME) { inclusive = false }
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(navController = navController, startDestination = start) {
            composable(Routes.ADD_SOURCE) {
                AddSourceScreen(
                    viewModel = sourcesViewModel,
                    onFinished = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ADD_SOURCE) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.EDIT_SOURCE) { entry ->
                val sourceId = entry.arguments?.getString("sourceId")?.toLongOrNull() ?: return@composable
                AddSourceScreen(
                    viewModel = sourcesViewModel,
                    editingSourceId = sourceId,
                    onFinished = { navController.popBackStack() },
                )
            }

            composable(Routes.HOME) {
                MainScreen(
                    isTelevision = isTelevision,
                    liveSession = liveSession,
                    onReturnToLive = { liveSession.currentChannel.value?.id?.let { openLive(it) } },
                    onLivePresentation = { presentation, showingGuide ->
                        mainLivePresentation = presentation
                        guideVisible = showingGuide
                        if (navController.currentDestination?.route == Routes.HOME) liveSession.setPresentation(presentation)
                    },
                    hasSources = sourcesUi.sources.isNotEmpty(),
                    isSyncing = sourcesUi.syncing,
                    onPlayChannel = { channel -> openLive(channel.id) },
                    onOpenMovie = { movie ->
                        navController.navigate(Routes.movieDetail(movie.id))
                    },
                    onOpenSeries = { series ->
                        navController.navigate(Routes.seriesDetail(series.id))
                    },
                    onResume = { key, url, title ->
                        openVod(
                            Routes.vodPlayer(key, url, title, "Nuvix/0.1 (Android)"),
                        )
                    },
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onRefresh = sourcesViewModel::refreshAll,
                    onOpenSearch = { navController.navigate(Routes.SEARCH) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS_HUB) },
                    onOpenProfiles = { navController.navigate(Routes.PROFILES) },
                    onPlayRecording = { rec ->
                        // A NAS recording plays straight off its smb:// locator and a USB one off
                        // its content:// document URI (Media3 reads both); an internal one through
                        // a file:// uri. An internal recording that's still capturing plays through
                        // the tail-following optvrec:// source, so it can be watched as it records
                        // with no second connection to the provider. All go through the VOD player.
                        val stillRecording = rec.status == app.opentv.data.model.RecordingStatus.RECORDING
                        // Internal and NAS in-progress recordings are both tail-followable via the
                        // growing source; USB (content://) isn't.
                        val growable = !app.opentv.recording.RecordingStorage.isContent(rec.filePath) &&
                            !app.opentv.recording.RecordingStorage.isUsbPlaceholder(rec.filePath)
                        val url = when {
                            stillRecording && growable -> "optvrec://${rec.id}"
                            app.opentv.recording.SmbClient.isSmb(rec.filePath) -> rec.filePath
                            app.opentv.recording.RecordingStorage.isContent(rec.filePath) -> rec.filePath
                            else -> android.net.Uri.fromFile(java.io.File(rec.filePath)).toString()
                        }
                        openVod(
                            Routes.vodPlayer(
                                key = "rec:${rec.id}",
                                url = url,
                                title = rec.title,
                                ua = rec.userAgent,
                            ),
                        )
                    },
                    onPlayCatchup = { key, url, title, ua ->
                        // Catch-up is a seekable archive stream — plays through the VOD player.
                        openVod(Routes.vodPlayer(key, url, title, ua))
                    },
                    activeProfileName = activeProfileName,
                    onOpenLauncherSettings = { navController.navigate(Routes.LAUNCHER_SETTINGS) },
                    startupGeneration = homeGeneration,
                    initialStartupGeneration = initialHomeGeneration,
                    multiViewManager = multiView,
                    onOpenMultiView = { openMultiView(it) },
                    guideRequestGeneration = guideRequestGeneration,
                )
            }

            composable(
                Routes.MULTIVIEW,
                enterTransition = { androidx.compose.animation.EnterTransition.None },
                exitTransition = { androidx.compose.animation.ExitTransition.None },
                popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                popExitTransition = { androidx.compose.animation.ExitTransition.None },
            ) {
                val recordingContext = LocalContext.current
                val recordingGraph = remember(recordingContext) { ServiceLocator.get(recordingContext) }
                val activeRecordings by recordingGraph.recordingRepository.observeActive().collectAsState(initial = emptyList())
                app.opentv.ui.multiview.MultiViewScreen(
                    manager = multiView,
                    activeRecordings = activeRecordings,
                    showApproval = recordingWarning == null,
                    onExit = {
                        multiView.stop()
                        navController.navigate(Routes.HOME) { popUpTo(Routes.HOME); launchSingleTop = true }
                    },
                    onOpenGuide = {
                        guideRequestGeneration++
                        navController.navigate(Routes.HOME) { popUpTo(Routes.HOME); launchSingleTop = true }
                    },
                )
            }

            composable(Routes.PROFILES) {
                ProfilesScreen(
                    viewModel = profilesViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.SEARCH) {
                var selectedProgramme by remember { mutableStateOf<Pair<app.opentv.data.model.Channel, app.opentv.data.model.Programme>?>(null) }
                val programmeActionScope = androidx.compose.runtime.rememberCoroutineScope()
                val searchGraph = remember { ServiceLocator.get(bootContext) }
                val searchRecordings by searchGraph.recordingRepository.observeActive().collectAsState(initial = emptyList())
                val searchSources by searchGraph.sourceRepository.observeAll().collectAsState(initial = emptyList())
                selectedProgramme?.let { (channel, programme) ->
                    ProgrammeDetailsDialog(
                        targetRow = ChannelsViewModel.Row(channel, listOf(channel), null, null, listOf(programme)),
                        programme = programme, nowMillis = System.currentTimeMillis(), sources = searchSources,
                        activeRecordings = searchRecordings, liveSession = liveSession, actionScope = programmeActionScope,
                        onWatch = { openLive(it.id) },
                        onPlayCatchup = { key, url, title, ua -> openVod(Routes.vodPlayer(key, url, title, ua)) },
                        onDismiss = { selectedProgramme = null },
                    )
                }
                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                    LivePlaybackPreview(liveSession, onFullscreen = {
                        liveSession.currentChannel.value?.id?.let { openLive(it) }
                    })
                    SearchScreen(
                        onOpenProgramme = { channel, programme -> selectedProgramme = channel to programme },
                        onPlayChannel = { channel -> openLive(channel.id) },
                        onPlayMovie = { movie ->
                            openVod(
                                Routes.vodPlayer(
                                    key = "movie:${movie.id}",
                                    url = movie.streamUrl,
                                    title = movie.displayTitle,
                                    ua = "Nuvix/0.1 (Android)",
                                ),
                            )
                        },
                        onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(Routes.SETTINGS_HUB) {
                SettingsHubScreen(
                    onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                    onOpenAddons = { navController.navigate(Routes.ADDONS) },
                    onOpenGuide = { navController.navigate(Routes.EPG_SETTINGS) },
                    onOpenChannels = { navController.navigate(Routes.CHANNELS) },
                    onOpenWebManager = { navController.navigate(Routes.WEB_MANAGER) },
                    onOpenDisplay = { navController.navigate(Routes.APP_SETTINGS) },
                    onOpenParental = { navController.navigate(Routes.PARENTAL) },
                    onOpenSync = { navController.navigate(Routes.SYNC) },
                    onOpenRecordings = { navController.navigate(Routes.REC_SETTINGS) },
                    onOpenAbout = { navController.navigate(Routes.ABOUT) },
                    onOpenLauncher = { navController.navigate(Routes.LAUNCHER_SETTINGS) },
                    onOpenCloud = { navController.navigate(Routes.CLOUD_SYNC) },
                    onOpenBuffer = { navController.navigate(Routes.BUFFER_SETTINGS) },
                    onOpenUpdates = { navController.navigate(Routes.UPDATE_SETTINGS) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.LAUNCHER_SETTINGS) {
                app.opentv.launcher.LauncherSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.CLOUD_SYNC) {
                app.opentv.cloud.SupabaseCloudScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.BUFFER_SETTINGS) {
                app.opentv.ui.settings.BufferSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.UPDATE_SETTINGS) {
                app.opentv.ui.settings.NuvixUpdateSettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SYNC) {
                SyncScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.REC_SETTINGS) {
                RecordingSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.CHANNELS) {
                ChannelManagerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.WEB_MANAGER) {
                WebManagerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.PARENTAL) {
                ParentalControlsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.PROVIDERS) {
                ProvidersScreen(
                    viewModel = sourcesViewModel,
                    onAddSource = { navController.navigate(Routes.ADD_SOURCE) },
                    onEditSource = { src -> navController.navigate(Routes.editSource(src.id)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.ADDONS) {
                StremioAddonsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.EPG_SETTINGS) {
                EpgSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.APP_SETTINGS) {
                AppSettingsScreen(onBack = { navController.popBackStack() }, onClearDiagnostics = liveSession::clearDiagnostics)
            }

            composable(
                Routes.PLAYER,
                enterTransition = { androidx.compose.animation.EnterTransition.None },
                exitTransition = { androidx.compose.animation.ExitTransition.None },
                popEnterTransition = { androidx.compose.animation.EnterTransition.None },
                popExitTransition = { androidx.compose.animation.ExitTransition.None },
            ) {
                PlayerScreen(
                    liveSession = liveSession,
                    onBack = { navController.popBackStack() },
                    onOpenMultiView = { openMultiView() },
                )
            }

            composable(Routes.SERIES_DETAIL) { entry ->
                val seriesId = entry.arguments?.getString("seriesId")?.toLongOrNull() ?: return@composable
                SeriesDetailScreen(
                    seriesId = seriesId,
                    viewModel = vodViewModel,
                    onPlayEpisode = { key, url, title ->
                        openVod(
                            Routes.vodPlayer(key, url, title, "Nuvix/0.1 (Android)"),
                        )
                    },
                    onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                    onOpenPerson = { name -> navController.navigate(Routes.person(name)) },
                )
            }

            composable(Routes.MOVIE_DETAIL) { entry ->
                val movieId = entry.arguments?.getString("movieId")?.toLongOrNull() ?: return@composable
                MovieDetailScreen(
                    movieId = movieId,
                    viewModel = vodViewModel,
                    onPlay = { movie ->
                        openVod(
                            Routes.vodPlayer(
                                key = "movie:${movie.id}",
                                url = movie.streamUrl,
                                title = movie.displayTitle,
                                ua = "Nuvix/0.1 (Android)",
                            ),
                        )
                    },
                    onPlayUrl = { key, url, title ->
                        openVod(
                            Routes.vodPlayer(key = key, url = url, title = title, ua = "Nuvix/0.1 (Android)"),
                        )
                    },
                    onOpenMovie = { movie -> navController.navigate(Routes.movieDetail(movie.id)) },
                    onOpenPerson = { name -> navController.navigate(Routes.person(name)) },
                )
            }

            composable(Routes.PERSON) { entry ->
                val name = entry.arguments?.getString("name")
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                PersonScreen(
                    name = name,
                    viewModel = vodViewModel,
                    onOpenMovie = { movie -> navController.navigate(Routes.movieDetail(movie.id)) },
                    onOpenSeries = { series -> navController.navigate(Routes.seriesDetail(series.id)) },
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.VOD_PLAYER) { entry ->
                fun arg(name: String) = entry.arguments?.getString(name)
                    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                VodPlayerScreen(
                    mediaKey = arg("key"),
                    liveSession = liveSession,
                    streamUrl = arg("url"),
                    title = arg("title"),
                    userAgent = arg("ua").ifEmpty { "Nuvix/0.1 (Android)" },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        // Sits above the whole nav graph so a found update can prompt from any screen.

        // Optional auto-navigation banner shares the configured warning lead time.
        if (recordingWarning == null) RecordingSwitchBanner()
    }
}

/**
 * A slim banner shown before a scheduled recording takes over the screen (single-
 * connection auto-switch). It gives the viewer a heads-up and a way out: "Keep watching" suppresses
 * the switch for that booking. It clears itself once the switch would have happened.
 */
@Composable
private fun RecordingSwitchBanner() {
    val imminent by app.opentv.core.RecordingSignals.imminent.collectAsState()
    val current = imminent ?: return

    // Auto-clear a little after the switch instant, in case the switch itself never lands.
    LaunchedEffect(current.recordingId) {
        val wait = (current.startAtMillis + 5_000L) - System.currentTimeMillis()
        kotlinx.coroutines.delay(wait.coerceIn(5_000L, 60_000L))
        if (app.opentv.core.RecordingSignals.imminent.value?.recordingId == current.recordingId) {
            app.opentv.core.RecordingSignals.clearImminent()
        }
    }

    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = Color(0xFFE53935),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.rec_switch_banner, current.title, current.channelName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            var focused by remember { mutableStateOf(false) }
            Text(
                stringResource(R.string.rec_switch_keep_watching),
                style = MaterialTheme.typography.labelLarge,
                color = if (focused) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (focused) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .focusable()
                    .clickable { app.opentv.core.RecordingSignals.suppress(current.recordingId) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Detects a ten-foot device.
 *
 * Checked at runtime rather than by shipping a separate leanback build: one APK for phone,
 * tablet, Android TV and Fire TV means one thing to release and one thing to test.
 */
fun isRunningOnTelevision(context: Context): Boolean {
    val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    val packageManager = context.packageManager
    return packageManager.hasSystemFeature("android.software.leanback") ||
        packageManager.hasSystemFeature("android.hardware.type.television")
}
