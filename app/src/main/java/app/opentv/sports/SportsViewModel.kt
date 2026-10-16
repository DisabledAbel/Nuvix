/* Copyright (C) 2026 Nuvix Contributors. GPL-3.0-or-later. */
package app.opentv.sports

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import app.opentv.core.ServiceLocator
import app.opentv.data.model.Channel
import app.opentv.reminders.ReminderScheduler
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SportsHubState(
    val cards: List<SportsEventCard> = emptyList(), val favorites: List<SportsFavorite> = emptyList(),
    val channels: List<Channel> = emptyList(), val now: Long = System.currentTimeMillis(),
    val loading: Boolean = true, val message: String? = null, val partial: Boolean = false,
    val selectedEvent: SportsEvent? = null, val streams: List<SportsStream> = emptyList(),
    val preferences: SportsPreferences = SportsPreferences(), val preferred: SportsPreferred? = null,
    val streamPage: Int = 0, val hasMoreStreams: Boolean = false, val relatedEvents: List<SportsEvent> = emptyList(),
)

/** Calendar-day boundaries, including 23/25-hour DST days; persisted timestamps are always UTC. */
fun sportsDayWindow(now: Long, zone: ZoneId, tomorrow: Boolean = false): LongRange {
    val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(if (tomorrow) 1 else 0)
    return date.atStartOfDay(zone).toInstant().toEpochMilli() until date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

fun filterSportsCards(cards: List<SportsEventCard>, filter: SportsFilter, favorites: List<SportsFavorite>, now: Long,
    zone: ZoneId = ZoneId.systemDefault(), league: String? = null, team: String? = null, sport: String? = null): List<SportsEventCard> {
    val teams = favorites.filter { it.kind == "team" }.map { it.id }.toSet()
    val leagues = favorites.filter { it.kind == "league" }.map { it.id }.toSet()
    return cards.filter { card ->
        val e = card.event
        (league == null || e.leagueId == league) && (team == null || e.homeTeamId == team || e.awayTeamId == team) &&
            (sport == null || e.sportId == sport) && when (filter) {
                SportsFilter.ALL -> true
                SportsFilter.LIVE -> e.isFresh(now) && now in e.startUtcMillis until e.endUtcMillis &&
                    e.status !in setOf("CANCELED", "POSTPONED", "FINAL")
                SportsFilter.TODAY -> e.startUtcMillis in sportsDayWindow(now, zone)
                SportsFilter.TOMORROW -> e.startUtcMillis in sportsDayWindow(now, zone, true)
                SportsFilter.UPCOMING -> e.startUtcMillis > now
                SportsFilter.FAVORITE_TEAMS -> e.homeTeamId in teams || e.awayTeamId in teams
                SportsFilter.FAVORITE_LEAGUES -> e.leagueId in leagues
                SportsFilter.WITH_STREAMS -> card.streamCount > 0
            }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SportsViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val graph = ServiceLocator.get(application)
    val repository = graph.sportsRepository
    private val store = SportsPreferencesStore(application)
    val query = saved.getStateFlow("sports_query", "")
    val selectedId = saved.getStateFlow<String?>("sports_event", null)
    val filter = saved.getStateFlow("sports_filter", store.preferences.value.defaultView)
    private val limit = MutableStateFlow(200)
    private val streamPage = MutableStateFlow(0)
    private data class Browse(val league: String?, val team: String?, val sport: String?)
    private data class CardQuery(val query: String, val limit: Int, val filter: SportsFilter, val browse: Browse)
    private val browse = MutableStateFlow(Browse(null, null, null))
    private val clock = MutableStateFlow(System.currentTimeMillis())
    private val mutableState = MutableStateFlow(SportsHubState(preferences = store.preferences.value))
    val state = mutableState.asStateFlow()
    val reminders = graph.reminderRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val hiddenCategories = combine(graph.catalogRepository.observeCategories(app.opentv.data.model.StreamKind.LIVE),
        graph.settings.hiddenCategories, graph.settings.hiddenUnlocked) { categories, hidden, unlocked ->
        if (unlocked) emptySet() else categories.filter {
            app.opentv.data.parser.ChannelNameNormalizer.normalize(it.name).groupKey.ifEmpty { it.id } in hidden
        }.map { it.sourceId to it.id }.toSet()
    }.shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)
    init {
        viewModelScope.launch {
            while (true) { clock.value = System.currentTimeMillis(); delay(30_000) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            combine(repository.dao.observeRevisions().distinctUntilChanged(), clock.map { it / SportsRepository.REINDEX_INTERVAL }.distinctUntilChanged()) { revision, _ -> revision }.collect {
                action { val partial = repository.reconcile(clock.value); mutableState.update { it.copy(loading = false, partial = partial) } }
            }
        }
        viewModelScope.launch {
            val input = combine(query, limit, filter, browse) { q, max, f, b -> CardQuery(q, max, f, b) }
            combine(input, clock, hiddenCategories) { inputValue, now, hidden ->
                val day = sportsDayWindow(now, ZoneId.systemDefault(), inputValue.filter == SportsFilter.TOMORROW)
                val from = if (inputValue.filter in listOf(SportsFilter.TODAY, SportsFilter.TOMORROW)) day.first else now
                repository.dao.observeCards(from, now + SportsRepository.HORIZON, pattern(inputValue.query), inputValue.limit,
                    app.opentv.data.repo.epgExcludedCategories(hidden), inputValue.filter.name, now, day.first, day.last + 1,
                    inputValue.browse.league, inputValue.browse.team, inputValue.browse.sport)
            }.flatMapLatest { it }.collect { cards -> mutableState.update { it.copy(cards = cards, loading = false) } }
        }
        viewModelScope.launch { repository.favorites.collect { f -> mutableState.update { it.copy(favorites = f) } } }
        viewModelScope.launch { store.preferences.collect { p -> mutableState.update { it.copy(preferences = p) } } }
        viewModelScope.launch { clock.collect { n -> mutableState.update { it.copy(now = n) } } }
        viewModelScope.launch {
            graph.database.channels().observeSportsChannels().combine(graph.database.sources().observeAll()) { channels, sources ->
                channels.filter { c -> sources.any { it.id == c.sourceId && it.enabled } }
            }.combine(hiddenCategories) { channels, hidden -> channels.filter { (it.sourceId to it.categoryId) !in hidden } }.collect { c -> mutableState.update { it.copy(channels = c) } }
        }
        viewModelScope.launch {
            combine(selectedId, hiddenCategories, streamPage) { id, hidden, page -> Triple(id, hidden, page) }.flatMapLatest { (id, hidden, page) ->
                if (id == null) flowOf(Triple<SportsEvent?, List<SportsStream>, SportsPreferred?>(null, emptyList(), null))
                else combine(repository.dao.observeEvent(id), repository.streams(id, app.opentv.data.repo.epgExcludedCategories(hidden), page * 200), repository.dao.observePreferred(id)) { event, streams, preferred ->
                    Triple(event, streams, preferred)
                }
            }.combine(clock) { data, now -> data to now }.flatMapLatest { (data, now) ->
                val (event, streams, preferred) = data
                graph.epgRepository.observeWindowForChannels(streams.flatMap { it.channel.epgCandidates }, now, now + 1)
                    .map { programmes ->
                        val current = programmes.groupBy { it.epgChannelId }
                        Triple(event, streams.map { stream -> stream.copy(currentProgramme = stream.channel.epgCandidates
                            .firstNotNullOfOrNull { key -> current[key]?.firstOrNull()?.title }) }, preferred)
                    }
            }.collect { (event, streams, preferred) ->
                val visible = event?.takeIf { it.verified || streams.isNotEmpty() || streamPage.value > 0 }
                mutableState.update { it.copy(selectedEvent = visible, streams = streams, preferred = preferred, streamPage = streamPage.value, hasMoreStreams = streams.size >= 200) }
            }
        }
        viewModelScope.launch {
            combine(selectedId, hiddenCategories) { id, hidden -> id to hidden }.flatMapLatest { (id, hidden) ->
                if (id == null) flowOf(emptyList())
                else repository.dao.observeEvent(id).flatMapLatest { event ->
                    if (event == null) flowOf(emptyList())
                    else repository.dao.observeCards(event.startUtcMillis, event.endUtcMillis, "%", 200,
                        app.opentv.data.repo.epgExcludedCategories(hidden)).map { cards -> cards.map { it.event }.filter { it.id != id } }
                }
            }.collect { related -> mutableState.update { it.copy(relatedEvents = related) } }
        }
    }

    fun browse(league: String?, team: String?, sport: String?) { browse.value = Browse(league, team, sport) }
    fun search(value: String) { saved["sports_query"] = value.take(120) }
    fun select(id: String?) {
        if (id != selectedId.value) mutableState.update { it.copy(selectedEvent = null, streams = emptyList(), preferred = null) }
        streamPage.value = 0; saved["sports_event"] = id
    }
    fun streamsPage(delta: Int) { streamPage.value = (streamPage.value + delta.coerceIn(-1, 1)).coerceAtLeast(0) }
    fun filter(value: SportsFilter) { saved["sports_filter"] = value }
    fun more() { limit.value = (limit.value + 200).coerceAtMost(2000) }
    fun settings(value: SportsPreferences) = viewModelScope.launch(Dispatchers.IO) { action { store.set(value) } }
    fun refresh() = viewModelScope.launch(Dispatchers.IO) { action {
        mutableState.update { it.copy(loading = true) }
        val partial = repository.reconcile(clock.value)
        mutableState.update { it.copy(loading = false, partial = partial, message = "Local sports schedules refreshed") }
    } }
    fun clearCache() = viewModelScope.launch(Dispatchers.IO) { action { repository.clearCache(); repository.reconcile(clock.value) } }
    fun favorite(kind: String, id: String, leagueId: String, name: String) = viewModelScope.launch(Dispatchers.IO) {
        action { repository.toggleFavorite(kind, id, leagueId, name) }
    }
    fun move(favorite: SportsFavorite, delta: Int) = viewModelScope.launch(Dispatchers.IO) {
        action { repository.moveFavorite(favorite.kind, favorite.id, delta) }
    }
    fun favoriteChannel(channel: Channel) = viewModelScope.launch(Dispatchers.IO) {
        action { graph.database.channels().setFavourite(channel.id, !channel.favourite) }
    }
    fun play(stream: SportsStream, onPlay: (Channel) -> Unit) = viewModelScope.launch { action {
        val id = selectedId.value ?: return@action
        val channel = repository.playable(id, stream)
        if (channel != null && (channel.sourceId to channel.categoryId) !in hiddenCategories.first()) onPlay(channel)
        else notify("This viewing choice is no longer available. Choose another stream or refresh the local schedule.")
    } }
    fun decide(eventId: String, stream: SportsStream, accepted: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        action { repository.decide(eventId, stream.programme.key, stream.channel.id, accepted) }
    }
    fun undo(stream: SportsStream) = viewModelScope.launch(Dispatchers.IO) { action {
        selectedId.value?.let { repository.dao.undoEventDecision(it, stream.channel.sourceId, stream.channel.streamId) }
    } }
    fun prefer(eventId: String, stream: SportsStream) = viewModelScope.launch(Dispatchers.IO) { action { repository.prefer(eventId, stream) } }
    fun remind(eventId: String) = viewModelScope.launch(Dispatchers.IO) { action {
        require(state.value.preferences.reminders)
        val reminder = repository.remind(eventId,
            orderSportsStreams(state.value.streams, state.value.preferred, state.value.preferences.streamSort), System.currentTimeMillis())
        ReminderScheduler.set(getApplication(), reminder.id, reminder.startUtcMillis)
        mutableState.update { it.copy(message = "Game reminder set") }
    } }
    fun cancelReminder(eventId: String) = viewModelScope.launch(Dispatchers.IO) { action {
        graph.database.reminders().forSportsEvent(eventId)?.let {
            ReminderScheduler.cancel(getApplication(), it.id); graph.reminderRepository.delete(it.id)
        }
    } }
    fun notify(message: String) { mutableState.update { it.copy(message = message) } }
    private suspend fun action(block: suspend () -> Unit) {
        try { block() } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
        } catch (_: Exception) { mutableState.update { it.copy(loading = false, message = "Sports action could not be completed. Cached schedules remain available.") } }
    }
    override fun onCleared() { store.close(); super.onCleared() }
    companion object {
        fun pattern(value: String): String {
            val trimmed = value.trim().take(120)
            val league = SportsLeagues.all.firstOrNull { l -> l.aliases.any { it == SportsNames.normalize(trimmed) } }
            return "%" + (league?.id ?: trimmed).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
    }
}
