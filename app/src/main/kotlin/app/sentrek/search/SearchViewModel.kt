package app.sentrek.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.CoordinateParser
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.OverlayPoi
import app.sentrek.geo.overlay.OverlayPoiSource
import app.sentrek.geo.place.Place
import app.sentrek.geo.search.TextMatch
import app.sentrek.record.MapName
import app.sentrek.record.MapNames
import app.sentrek.record.PlaceRepository
import app.sentrek.record.SavedTrack
import app.sentrek.record.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One thing found on the map: a hiking point (hut, spring…) or a name of the map's own tiles. */
sealed interface MapHit {
    val name: String
    val position: LatLon

    data class Hiking(val poi: OverlayPoi) : MapHit {
        override val name get() = poi.name.orEmpty()
        override val position get() = poi.position
    }

    data class Named(val place: MapName) : MapHit {
        override val name get() = place.name
        override val position get() = place.position
    }
}

/** What a search found, each group best first. */
data class SearchResults(
    val places: List<Place> = emptyList(),
    val tracks: List<SavedTrack> = emptyList(),
    /** Everything named on the map: the hiking details' points and the map's own names, together. */
    val points: List<MapHit> = emptyList(),
    /** The text read as a position: pasted from a text message, say. */
    val position: LatLon? = null,
) {
    val isEmpty: Boolean get() = places.isEmpty() && tracks.isEmpty() && points.isEmpty() && position == null
}

/** One search over the saved places, the tracks and the named points of the downloaded areas. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    placeRepository: PlaceRepository,
    private val trackRepository: TrackRepository,
    private val pois: OverlayPoiSource,
    private val mapNames: MapNames,
) : ViewModel() {
    val query = MutableStateFlow("")

    /** Ranked against this: the middle of the map the user came from. */
    private val near = MutableStateFlow<LatLon?>(null)

    /** The named points of every downloaded area, with their names normalized; read once per visit. */
    private val points = MutableStateFlow<List<Pair<String, OverlayPoi>>>(emptyList())

    /** The towns and mountains of the map's own tiles, with their names normalized; read once per visit. */
    private val names = MutableStateFlow<List<Pair<String, MapName>>>(emptyList())

    /** False while the downloaded areas are being read. */
    val pointsReady = MutableStateFlow(false)

    @OptIn(FlowPreview::class)
    val results: StateFlow<SearchResults> = combine(
        // Typing fast with gloves: search once the fingers pause.
        query.debounce(DEBOUNCE_MS),
        placeRepository.places,
        trackRepository.tracks,
        combine(points, names, ::Pair),
        near,
    ) { q, places, tracks, (points, names), near ->
        val position = CoordinateParser.parse(q)
        if (position != null) return@combine SearchResults(position = position)
        val words = TextMatch.words(q)
        if (words.isEmpty()) return@combine SearchResults()
        // Hiking points first where both have the same thing: theirs is the richer card.
        val hiking = points.mapNotNull { (name, poi) -> TextMatch.score(name, words)?.let { Triple(it, MapHit.Hiking(poi) as MapHit, poi.position) } }
        val named = names.mapNotNull { (name, place) -> TextMatch.score(name, words)?.let { Triple(it, MapHit.Named(place) as MapHit, place.position) } }
            .filter { (_, hit, at) ->
                hiking.none { (_, h, p) -> h.name.equals(hit.name, ignoreCase = true) && Geo.distance(p, at) < SAME_PLACE_M }
            }
        SearchResults(
            places = places
                .mapNotNull { p -> score(words, p.name, p.note)?.let { it to p } }
                .sortedWith(compareBy({ it.first }, { near?.let { n -> Geo.distance(n, it.second.position) } ?: 0.0 }))
                .take(MAX_EACH).map { it.second },
            tracks = tracks
                .mapNotNull { t -> TextMatch.score(TextMatch.normalize(t.name), words)?.let { it to t } }
                .sortedWith(compareBy<Pair<Int, SavedTrack>> { it.first }.thenByDescending { it.second.startTime })
                .take(MAX_EACH).map { it.second },
            points = (hiking + named)
                .sortedWith(compareBy({ it.first }, { near?.let { n -> Geo.distance(n, it.third) } ?: 0.0 }))
                .take(MAX_EACH).map { it.second },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /** Called when the screen opens: areas may have been downloaded since the last visit. */
    fun open(near: LatLon?) {
        this.near.value = near
        pointsReady.value = false
        viewModelScope.launch(Dispatchers.Default) {
            points.value = runCatching { pois.pois(WORLD) }.getOrDefault(emptyList())
                .mapNotNull { poi -> poi.name?.takeIf { it.isNotBlank() }?.let { TextMatch.normalize(it) to poi } }
                // Overlapping areas list the same point twice.
                .distinctBy { (name, poi) -> Triple(name, (poi.position.lat * 1e4).toLong(), (poi.position.lon * 1e4).toLong()) }
            names.value = mapNames.all().map { TextMatch.normalize(it.name) to it }
            pointsReady.value = true
        }
    }

    /** Draws the track on the map; its bounds to fly to. */
    suspend fun show(track: SavedTrack): BoundingBox? = trackRepository.show(track)

    private fun score(words: List<String>, name: String, note: String): Int? =
        TextMatch.score(TextMatch.normalize(name), words)
            // Found only in the note: after every match on a name.
            ?: TextMatch.score(TextMatch.normalize(note), words)?.let { it + NOTE_PENALTY }

    private companion object {
        const val DEBOUNCE_MS = 200L
        const val MAX_EACH = 30
        const val NOTE_PENALTY = 10

        /** A town or peak this close to a point of the same name is that point. */
        const val SAME_PLACE_M = 1_000.0
        val WORLD = BoundingBox(-90.0, -180.0, 90.0, 180.0)
    }
}
