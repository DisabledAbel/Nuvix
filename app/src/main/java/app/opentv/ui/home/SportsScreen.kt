/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.opentv.data.model.Channel
import app.opentv.data.model.shownName
import app.opentv.sports.*
import app.opentv.ui.channels.OnScreenKeyboard
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The existing Sports destination owns both hub and details; fullscreen playback returns here. */
@Composable
fun SportsScreen(onPlayChannel: (Channel) -> Unit, model: SportsViewModel = viewModel(key = "sports_hub"),
    onAddToMultiView: ((Channel) -> Unit)? = null, onReplaceMultiViewPanel: ((Channel) -> Unit)? = null) {
    val state by model.state.collectAsState()
    val query by model.query.collectAsState()
    val selectedId by model.selectedId.collectAsState()
    val filter by model.filter.collectAsState()
    val reminders by model.reminders.collectAsState()
    val context = LocalContext.current
    var search by rememberSaveable { mutableStateOf(false) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var league by rememberSaveable { mutableStateOf<String?>(null) }
    var team by rememberSaveable { mutableStateOf<String?>(null) }
    var sport by rememberSaveable { mutableStateOf<String?>(null) }
    val hubContentState = rememberSaveableStateHolder()
    var restoreCard by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(league, team, sport) { model.browse(league, team, sport) }
    LaunchedEffect(state.message) {
        if (state.message != null) { delay(5_000); model.consumeMessage() }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) selectedId?.let(model::remind)
        else model.notify("Notifications are disabled. Enable Nuvix notifications in device settings to receive reminders.")
    }
    fun remind(id: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            model.notify("Notifications are disabled. Enable Nuvix notifications in device settings to receive reminders.")
        } else model.remind(id)
    }
    BackHandler(enabled = selectedId != null || search || settings || league != null || team != null || sport != null) {
        when {
            settings -> settings = false
            search -> search = false
            selectedId != null -> model.select(null)
            else -> { league = null; team = null; sport = null }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.message != null) Text(state.message!!, color = MaterialTheme.colorScheme.primary)
        when {
            settings -> SportsSettings(state, { settings = false }, model::settings, model::clearCache, model::favorite, model::move)
            selectedId != null -> {
                val event = state.selectedEvent
                if (event == null) {
                    SportsAction("Back to Sports") { model.select(null) }
                    Text("Game unavailable or no longer visible. Return to Sports or refresh the guide.")
                } else SportsDetails(state, event, sportsReminderForEvent(reminders, event.id, state.streams)?.fired == false,
                    onBack = { model.select(null) }, onPlay = { stream -> model.play(stream, onPlayChannel) }, onRemind = { remind(event.id) },
                    onCancelReminder = { model.cancelReminder(event.id) }, onFavorite = model::favorite,
                    onFavoriteChannel = model::favoriteChannel, onDecide = model::decide, onUndo = model::undo,
                    onPrefer = model::prefer, onOpenEvent = model::select, onStreamPage = model::streamsPage,
                    onAddToMultiView = onAddToMultiView?.let { callback ->
                        { stream -> model.chooseBroadcast(stream, SportsPlaybackAction.ADD_TO_MULTIVIEW) { _, channel -> callback(channel) }; Unit }
                    },
                    onReplaceMultiViewPanel = onReplaceMultiViewPanel?.let { callback ->
                        { stream -> model.chooseBroadcast(stream, SportsPlaybackAction.REPLACE_MULTIVIEW_PANEL) { _, channel -> callback(channel) }; Unit }
                    })
            }
            search -> {
                SportsAction("Done searching") { search = false }
                Text(query.ifEmpty { "Search teams, leagues, sports, games or available channels" }, style = MaterialTheme.typography.titleMedium)
                OnScreenKeyboard(onKey = { model.search(query + it) }, onSpace = { model.search("$query ") },
                    onBackspace = { model.search(query.dropLast(1)) }, onClear = { model.search("") })
                Text("${state.cards.size} games in the current schedule window")
            }
            else -> hubContentState.SaveableStateProvider("hub") {
              Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The best seat in the house.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { SportsAction("Search${query.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}") { search = true } }
                    item { SportsAction("Sports settings") { settings = true } }
                    item { SportsAction("Refresh local schedules") { model.refresh() } }
                    if (league != null || team != null || sport != null || query.isNotEmpty()) item {
                        SportsAction("Clear search and filters") { league = null; team = null; sport = null; model.search("") }
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SportsFilter.entries) { choice -> SportsAction(choice.label, selected = choice == filter) { model.filter(choice) } }
                }
                if (state.loading) Text("Organizing provider guide data…")
                if (state.partial) Text("Partial schedule: a feed exceeded the 50,000-program indexing limit.")
                if (state.cards.size >= 200) Text("Showing a bounded schedule window. Search to narrow results or load more games below.")
                val filtered = remember(state.cards, state.favorites, state.now, filter, league, team, sport) {
                    filterSportsCards(state.cards, filter, state.favorites, state.now, league = league, team = team, sport = sport)
                }
                val cards = filtered.filter { it.event.homeTeamId != null || it.event.verified }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                    val mySports = cards.filter { card -> state.favorites.any { f ->
                        f.kind == "league" && f.id == card.event.leagueId || f.kind == "team" && f.id in listOf(card.event.homeTeamId, card.event.awayTeamId) ||
                            f.kind == "sport" && f.id == card.event.sportId
                    } }
                    item(key = "my_sports") {
                        GameShelf("My Sports", mySports, state, restoreCard,
                            "Follow teams, leagues or sports below to build your schedule.",
                            onFocus = { restoreCard = it }, onOpen = model::select)
                    }
                    item(key = "live_games") {
                        GameShelf("Live Games", cards.filter { it.event.isFresh(state.now) && state.now in it.event.startUtcMillis until it.event.endUtcMillis &&
                            it.event.status !in setOf("FINAL", "CANCELED", "POSTPONED") }, state, restoreCard,
                            "No current games found in your provider guide.", { restoreCard = it }, model::select)
                    }
                    item(key = "upcoming_games") {
                        GameShelf("Upcoming Games", cards.filter { it.event.startUtcMillis > state.now }, state, restoreCard,
                            "No upcoming games in this guide window.", { restoreCard = it }, model::select)
                    }
                    if (filter !in listOf(SportsFilter.ALL, SportsFilter.LIVE, SportsFilter.UPCOMING) || query.isNotEmpty()) item(key = "results") {
                        GameShelf("Filtered Games", cards, state, restoreCard, "No games match these filters.", { restoreCard = it }, model::select)
                    }
                    item(key = "teams") {
                        Text("Favorite Teams", style = MaterialTheme.typography.titleLarge)
                        val discovered = buildList {
                            addAll(state.favorites.filter { it.kind == "team" })
                            state.cards.forEach { c ->
                                val e = c.event
                                if (e.leagueId.isNotEmpty()) {
                                    e.homeTeamId?.let { id -> e.homeName?.let { add(SportsFavorite("team", id, e.leagueId, it, 0)) } }
                                    e.awayTeamId?.let { id -> e.awayName?.let { add(SportsFavorite("team", id, e.leagueId, it, 0)) } }
                                }
                            }
                        }.distinctBy { it.id }.filter { query.isBlank() || it.name.contains(query, true) || it.leagueId.contains(query, true) }
                        if (discovered.isEmpty()) Text("Teams appear when the EPG supplies a named matchup and competition. Search uses your cached schedules.")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(discovered, key = { it.id }) { f ->
                                FavoriteChoice(f, state.favorites.any { it.kind == "team" && it.id == f.id },
                                    onToggle = { model.favorite("team", f.id, f.leagueId, f.name) },
                                    onBrowse = { team = f.id; league = null; model.filter(SportsFilter.ALL) }, onMove = { model.move(f, it) })
                            }
                        }
                    }
                    item(key = "leagues") {
                        Text("Favorite Leagues", style = MaterialTheme.typography.titleLarge)
                        val order = state.favorites.filter { it.kind == "league" }.map { it.id }
                        val leagues = SportsLeagues.all.sortedBy { order.indexOf(it.id).takeIf { n -> n >= 0 } ?: Int.MAX_VALUE }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(leagues, key = { it.id }) { l ->
                                FavoriteChoice(SportsFavorite("league", l.id, l.id, l.name, 0), l.id in order,
                                    onToggle = { model.favorite("league", l.id, l.id, l.name) },
                                    onBrowse = { league = l.id; team = null; model.filter(SportsFilter.ALL) },
                                    onMove = { model.move(SportsFavorite("league", l.id, l.id, l.name, 0), it) })
                            }
                        }
                    }
                    item(key = "all_sports") {
                        Text("All Sports", style = MaterialTheme.typography.titleLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(SportsRepository.SPORT_IDS.toList()) { id ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SportsAction("Browse $id", selected = sport == id) { sport = id; model.filter(SportsFilter.ALL) }
                                    val followed = state.favorites.any { it.kind == "sport" && it.id == id }
                                    SportsAction(if (followed) "Unfollow $id" else "Follow $id") { model.favorite("sport", id, "", id) }
                                }
                            }
                        }
                    }
                    item(key = "unmatched_programs") {
                        GameShelf("Unmatched sports programs", filtered.filter { it.event.homeTeamId == null && !it.event.verified },
                            state, restoreCard, "No unmatched sports programs in this window.", { restoreCard = it }, model::select)
                    }
                    item(key = "sports_channels") {
                        Text("Sports Channels", style = MaterialTheme.typography.titleLarge)
                        Text("Provider categories identify these channels; a channel listing alone does not confirm a game broadcast.")
                        if (state.channels.isEmpty()) Text("No sports channels yet. Add or refresh a provider in Settings.")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.channels.distinctBy { it.sourceId to it.streamId }, key = { it.id }) { c ->
                                SportsAction(c.shownName) { onPlayChannel(c) }
                            }
                        }
                    }
                    item { SportsAction("Load more games (up to 2,000)") { model.more() } }
                    item { Text("Schedules cover the next seven days when EPG data exists. EPG-derived events are inferred; scores require a permitted, configured source.") }
                }
              }
            }
        }
    }
}

/** Displays a titled horizontal shelf of sports events. */
@Composable
private fun GameShelf(title: String, cards: List<SportsEventCard>, state: SportsHubState, restore: String?, empty: String,
    onFocus: (String) -> Unit, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (cards.isEmpty()) Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { it.event.id }) { card ->
                val key = "$title:${card.event.id}"
                SportsGameCard(card, state.now, state.favorites.any { it.id in listOf(card.event.leagueId, card.event.homeTeamId, card.event.awayTeamId) },
                    showScores = state.preferences.showScores, restore = restore == key, onFocus = { onFocus(key) }, onClick = { onOpen(card.event.id) })
            }
        }
    }
}

/** Displays an event's schedule and available-stream summary without tuning on focus. */
@Composable
internal fun SportsGameCard(card: SportsEventCard, now: Long, favorite: Boolean, showScores: Boolean = true, restore: Boolean = false,
    onFocus: () -> Unit = {}, onClick: () -> Unit) {
    val e = card.event
    val requester = remember { FocusRequester() }
    SportsSurface(Modifier.width(300.dp).focusRequester(requester), onFocus, onClick) {
        Text((if (favorite) "★ " else "") + (SportsLeagues.byId(e.leagueId)?.name ?: "Unspecified league") + " · ${e.sportId}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            e.homeLogo?.let { AsyncImage(it, e.homeName, Modifier.size(40.dp)) }
            e.awayLogo?.let { AsyncImage(it, e.awayName, Modifier.size(40.dp)) }
        }
        Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(sportsTime(e.startUtcMillis))
        Text(e.displayStatus(now))
        if (showScores && e.verified && e.homeScore != null && e.awayScore != null) {
            Text("${e.homeScore} – ${e.awayScore} ${e.period.orEmpty()}")
            Text("Score updated: ${sportsTime(e.updatedAtMillis)}")
        }
        Text("${card.streamCount} available streams")
        Text(if (e.verified) "Verified event · ${e.source}" else if (e.homeTeamId == null) "Unmatched sports program" else "EPG-derived event · inferred")
    }
    LaunchedEffect(restore) { if (restore) requester.requestFocus() }
}

/** Displays a focusable team or league favorite action. */
@Composable
private fun FavoriteChoice(f: SportsFavorite, selected: Boolean, onToggle: () -> Unit, onBrowse: () -> Unit, onMove: (Int) -> Unit) {
    Column(Modifier.widthIn(min = 200.dp, max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SportsAction("${f.name} · ${SportsLeagues.byId(f.leagueId)?.name.orEmpty()}", selected) { onBrowse() }
        SportsAction(if (selected) "Unfollow ${f.name}" else "Follow ${f.name}") { onToggle() }
        if (selected) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SportsAction("Move earlier") { onMove(-1) }; SportsAction("Move later") { onMove(1) }
        }
    }
}

/** Displays selected-event details, stream choices and explicit matching or reminder actions. */
@Composable
private fun SportsDetails(state: SportsHubState, event: SportsEvent, reminderSet: Boolean, onBack: () -> Unit,
    onPlay: (SportsStream) -> Unit, onRemind: () -> Unit, onCancelReminder: () -> Unit,
    onFavorite: (String, String, String, String) -> Unit, onFavoriteChannel: (Channel) -> Unit,
    onDecide: (String, SportsStream, Boolean) -> Unit, onUndo: (SportsStream) -> Unit,
    onPrefer: (String, SportsStream) -> Unit, onOpenEvent: (String?) -> Unit, onStreamPage: (Int) -> Unit,
    onAddToMultiView: ((SportsStream) -> Unit)? = null, onReplaceMultiViewPanel: ((SportsStream) -> Unit)? = null) {
    val streams = remember(state.streams, state.preferred, state.preferences.streamSort) {
        orderSportsStreams(state.streams, state.preferred, state.preferences.streamSort)
    }
    var assigning by remember(event.id) { mutableStateOf<SportsStream?>(null) }
    var choosingGameBroadcast by remember(event.id) { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { SportsAction("Back to Sports", onClick = onBack) }
        item {
            Text(event.title, style = MaterialTheme.typography.headlineMedium)
            Text("${SportsLeagues.byId(event.leagueId)?.name ?: "Unspecified league"} · ${event.sportId} · ${sportsTime(event.startUtcMillis)}")
            Text("${event.displayStatus(state.now)} · ${if (event.verified) "Verified event" else "EPG-derived event · inferred"}")
            Text("Last successful source update: ${if (event.updatedAtMillis > 0) sportsTime(event.updatedAtMillis) else "unknown"}")
            if (state.preferences.showScores && event.verified && event.homeScore != null && event.awayScore != null) {
                Text("${event.homeName}: ${event.homeScore} · ${event.awayName}: ${event.awayScore} ${event.period.orEmpty()}")
            }
        }
        if (event.leagueId.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val following = state.favorites.any { it.kind == "league" && it.id == event.leagueId }
                    SportsAction("${if (following) "Unfollow" else "Follow"} ${SportsLeagues.byId(event.leagueId)?.name.orEmpty()}") {
                        onFavorite("league", event.leagueId, event.leagueId, SportsLeagues.byId(event.leagueId)?.name ?: event.leagueId)
                    }
                }
                listOf(event.homeTeamId to event.homeName, event.awayTeamId to event.awayName).forEach { (id, name) ->
                    if (id != null && name != null) item {
                        val following = state.favorites.any { it.kind == "team" && it.id == id }
                        SportsAction("${if (following) "Unfollow" else "Follow"} $name") { onFavorite("team", id, event.leagueId, name) }
                    }
                }
            }
        }
        if (event.startUtcMillis > state.now && event.status !in setOf("CANCELED", "POSTPONED", "FINAL") && state.preferences.reminders) item {
            if (reminderSet) SportsAction("Cancel game reminder", onClick = onCancelReminder)
            else if (streams.any { it.available }) SportsAction("Remind me about this game", onClick = onRemind)
        }
        item {
            Text("Available Streams", style = MaterialTheme.typography.titleLarge)
            Text("Confirmed viewing choices use your enabled provider catalogs. Select a broadcast to watch fullscreen or add one stream to Multi-View.")
            if (streams.none { it.available }) Text("No confirmed streams. Review suggestions or use manual association below.")
        }
        if (onAddToMultiView != null && streams.any { it.available }) item {
            SportsAction("Add Game to Multi-View") { choosingGameBroadcast = !choosingGameBroadcast }
        }
        if (choosingGameBroadcast && onAddToMultiView != null) item {
            SportsBroadcastChooser(streams, onChoose = { stream ->
                choosingGameBroadcast = false
                onAddToMultiView(stream)
            }, onCancel = { choosingGameBroadcast = false })
        }
        items(streams.filter { it.available }, key = { "available:${it.channel.id}" }) { stream ->
            SportsStreamCard(stream, preferred = state.preferred?.let { it.sourceId == stream.channel.sourceId && it.streamId == stream.channel.streamId } == true,
                onPlay = { onPlay(stream) }, onFavorite = { onFavoriteChannel(stream.channel) },
                onConfirm = { onDecide(event.id, stream, true) }, onReject = { onDecide(event.id, stream, false) },
                onPrefer = { onPrefer(event.id, stream) }, onAssign = { assigning = stream },
                onAddToMultiView = onAddToMultiView?.let { callback -> { callback(stream) } },
                onReplaceMultiViewPanel = onReplaceMultiViewPanel?.let { callback -> { callback(stream) } })
        }
        item { Text("Suggested Matches", style = MaterialTheme.typography.titleLarge) }
        items(streams.filter { !it.rejected && !it.available && (state.preferences.showPossible || it.confidence != MatchConfidence.POSSIBLE) },
            key = { "suggested:${it.channel.id}" }) { stream ->
            SportsStreamCard(stream, false, {}, { onFavoriteChannel(stream.channel) }, { onDecide(event.id, stream, true) },
                { onDecide(event.id, stream, false) }, {}, { assigning = stream })
        }
        if (streams.any { it.rejected }) item {
            Text("Removed associations")
            streams.filter { it.rejected }.forEach { stream -> SportsAction("Restore ${stream.providerName} · ${stream.channel.shownName}") { onUndo(stream) } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.streamPage > 0) SportsAction("Previous streams page") { onStreamPage(-1) }
                Text("Stream page ${state.streamPage + 1}")
                if (state.hasMoreStreams) SportsAction("Next streams page") { onStreamPage(1) }
            }
        }
        val otherEvents = state.relatedEvents.filter { it.id != event.id && it.startUtcMillis < event.endUtcMillis && it.endUtcMillis > event.startUtcMillis }
        if (assigning != null) item {
            Text("Select the correct game for ${assigning!!.channel.shownName}. The original guide is retained.")
            SportsAction("Cancel assignment") { assigning = null }
        }
        if (assigning != null) items(otherEvents, key = { "assign:${it.id}" }) { other ->
            SportsAction("Associate with ${other.title} · ${sportsTime(other.startUtcMillis)}") {
                assigning?.let { onDecide(other.id, it, true) }; assigning = null
            }
        }
        if (otherEvents.isNotEmpty()) item {
            Text("Manual organization / possible duplicates", style = MaterialTheme.typography.titleLarge)
            Text("Open an overlapping event or unmatched sports program, choose Select game on its stream, then choose the correct event. Events are never silently merged.")
        }
        items(otherEvents.take(50), key = { "related:${it.id}" }) { other ->
            SportsAction("${other.title} · ${sportsTime(other.startUtcMillis)}") { onOpenEvent(other.id) }
        }
    }
}

/** Displays one provider stream choice with explicit play and preference actions. */
@Composable
internal fun SportsStreamCard(stream: SportsStream, preferred: Boolean, onPlay: () -> Unit, onFavorite: () -> Unit,
    onConfirm: () -> Unit, onReject: () -> Unit, onPrefer: () -> Unit, onAssign: () -> Unit,
    onAddToMultiView: (() -> Unit)? = null, onReplaceMultiViewPanel: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            stream.channel.logoUrl?.let { AsyncImage(it, "${stream.channel.shownName} logo", Modifier.size(44.dp)) }
            Column {
                Text("${stream.providerName} — ${stream.channel.shownName}${if (preferred) " · Preferred" else ""}", style = MaterialTheme.typography.titleMedium)
                Text("${stream.confidence.label}${if (stream.userConfirmed) " · Confirmed by you" else " · EPG evidence"}")
                Text("Event program: ${stream.programme.title} · ${sportsTime(stream.programme.startUtcMillis)}")
                Text("Current EPG: ${stream.currentProgramme ?: "not supplied"}")
                if (stream.channel.qualityLabel.isNotBlank()) Text("Provider quality label (unverified): ${stream.channel.qualityLabel}")
                Text("Language: not supplied")
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stream.available) item { SportsAction("Watch Fullscreen", onClick = onPlay) }
            else item { SportsAction("Confirm match", onClick = onConfirm) }
            if (stream.available && onAddToMultiView != null) item { SportsAction("Add Broadcast to Multi-View", onClick = onAddToMultiView) }
            if (stream.available && onReplaceMultiViewPanel != null) item { SportsAction("Replace Multi-View Panel", onClick = onReplaceMultiViewPanel) }
            item { SportsAction(if (stream.channel.favourite) "Unfavorite channel" else "Favorite channel", onClick = onFavorite) }
            if (stream.available) item { SportsAction("Prefer this broadcast", onClick = onPrefer) }
            item { SportsAction("Remove match", onClick = onReject) }
            item { SportsAction("Select game", onClick = onAssign) }
        }
    }
}

/** Only confirmed options are actionable; users choose one provider broadcast explicitly. */
@Composable
internal fun SportsBroadcastChooser(streams: List<SportsStream>, onChoose: (SportsStream) -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose one broadcast", style = MaterialTheme.typography.titleMedium)
        Text("Provider connection capacity is checked after selection.")
        streams.filter { it.available }.distinctBy { it.channel.sourceId to it.channel.streamId }.forEach { stream ->
            SportsAction("${stream.providerName} · ${stream.channel.shownName} · Confirmed") { onChoose(stream) }
        }
        SportsAction("Cancel broadcast selection", onClick = onCancel)
    }
}

/** Displays saved Sports Hub browsing, suggestion and cache controls. */
@Composable
private fun SportsSettings(state: SportsHubState, onBack: () -> Unit, onChange: (SportsPreferences) -> Unit,
    onClear: () -> Unit, onFavorite: (String, String, String, String) -> Unit, onMove: (SportsFavorite, Int) -> Unit) {
    val p = state.preferences
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        item { SportsAction("Back to Sports", onClick = onBack) }
        item { Text("Sports Hub settings", style = MaterialTheme.typography.headlineMedium) }
        item { SportsAction("Default view: ${p.defaultView.label}") { onChange(p.copy(defaultView = SportsFilter.entries[(p.defaultView.ordinal + 1) % SportsFilter.entries.size])) } }
        item { SportsAction("Stream sorting: ${p.streamSort.name}") { onChange(p.copy(streamSort = SportsStreamSort.entries[(p.streamSort.ordinal + 1) % SportsStreamSort.entries.size])) } }
        item { SportsAction("Show possible matches: ${p.showPossible}") { onChange(p.copy(showPossible = !p.showPossible)) } }
        item { SportsAction("Show scores when supplied: ${p.showScores}") { onChange(p.copy(showScores = !p.showScores)) } }
        item { SportsAction("Offer game reminders: ${p.reminders}") { onChange(p.copy(reminders = !p.reminders)) } }
        item { SportsAction("Clear derived sports cache (keep favorites and corrections)", onClick = onClear) }
        item { Text("Favorite sports, teams and leagues are local to this installation. Reorder or remove them here or from the hub. External schedule and score APIs are not configured.") }
        items(state.favorites, key = { "${it.kind}:${it.id}" }) { f ->
            FavoriteChoice(f, true, { onFavorite(f.kind, f.id, f.leagueId, f.name) }, {}, { onMove(f, it) })
        }
    }
}

/** Explicit focus colors and button semantics support remotes and accessibility services. */
@Composable
internal fun SportsAction(label: String, selected: Boolean = false, onClick: () -> Unit) {
    SportsSurface(Modifier.widthIn(max = 600.dp), onClick = onClick, selected = selected) { Text(label, style = MaterialTheme.typography.bodyLarge) }
}

/** Applies shared focusable card styling to Sports Hub content. */
@Composable
private fun SportsSurface(modifier: Modifier = Modifier, onFocus: () -> Unit = {}, onClick: () -> Unit,
    selected: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val background = if (focused) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
        .background(background, RoundedCornerShape(10.dp))
        .border(if (focused) 3.dp else 1.dp, if (focused || selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            RoundedCornerShape(10.dp))
        .semantics { this.selected = selected }.clickable(role = Role.Button, onClick = onClick).padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = content)
}

/** Formats an event timestamp using the local sports schedule presentation. */
internal fun sportsTime(time: Long): String = DateTimeFormatter.ofPattern("EEE, MMM d · HH:mm z")
    .format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
