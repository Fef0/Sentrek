package app.sentrek.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.CoordinateParser
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.NamedLine
import app.sentrek.geo.overlay.NamedLineSource
import app.sentrek.geo.overlay.NamedLines
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
    /** What was typed when these were found: the screen shows it's busy until they match. */
    val query: String = "",
    val places: List<Place> = emptyList(),
    val tracks: List<SavedTrack> = emptyList(),
    /** Everything named on the map: the hiking details' points and the map's own names, together. */
    val points: List<MapHit> = emptyList(),
    /** Via ferratas, waymarked routes and named paths. */
    val paths: List<NamedLine> = emptyList(),
    /** The text read as a position: pasted from a text message, say. */
    val position: LatLon? = null,
) {
    val isEmpty: Boolean get() = places.isEmpty() && tracks.isEmpty() && points.isEmpty() && paths.isEmpty() && position == null
}

/** One search over the saved places, the tracks and the named points of the downloaded areas. */
@HiltViewModel
class SearchViewModel @Inject constructor(
    placeRepository: PlaceRepository,
    private val trackRepository: TrackRepository,
    private val pois: OverlayPoiSource,
    private val mapNames: MapNames,
    private val namedLines: NamedLineSource,
) : ViewModel() {
    val query = MutableStateFlow("")

    /** Ranked against this: the middle of the map the user came from. */
    private val near = MutableStateFlow<LatLon?>(null)

    /** The named points of every downloaded area, with their names normalized; read once per visit. */
    private val points = MutableStateFlow<List<Pair<String, OverlayPoi>>>(emptyList())

    /** The towns and mountains of the map's own tiles, with their names normalized; read once per visit. */
    private val names = MutableStateFlow<List<Pair<String, MapName>>>(emptyList())

    /** Via ferratas, routes and named paths, with their names normalized; read once per visit. */
    private val lines = MutableStateFlow<List<Pair<String, NamedLine>>>(emptyList())

    /** False while the downloaded areas are being read. */
    val pointsReady = MutableStateFlow(false)

    @OptIn(FlowPreview::class)
    val results: StateFlow<SearchResults> = combine(
        // Typing fast with gloves: search once the fingers pause.
        query.debounce(DEBOUNCE_MS),
        placeRepository.places,
        trackRepository.tracks,
        combine(points, names, lines, ::Triple),
        near,
    ) { q, places, tracks, (points, names, lines), near ->
        val position = CoordinateParser.parse(q)
        if (position != null) return@combine SearchResults(q, position = position)
        val words = TextMatch.words(q)
        if (words.isEmpty()) return@combine SearchResults(q)
        // Hiking points first where both have the same thing: theirs is the richer card.
        val hiking = points.mapNotNull { (name, poi) -> TextMatch.score(name, words)?.let { Triple(it, MapHit.Hiking(poi) as MapHit, poi.position) } }
        val named = names.mapNotNull { (name, place) -> TextMatch.score(name, words)?.let { Triple(it, MapHit.Named(place) as MapHit, place.position) } }
            .filter { (_, hit, at) ->
                hiking.none { (_, h, p) -> h.name.equals(hit.name, ignoreCase = true) && Geo.distance(p, at) < SAME_PLACE_M }
            }
        SearchResults(
            query = q,
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
            paths = lines
                .mapNotNull { (text, line) ->
                    // "via ferrata" finds every via ferrata, after those named so.
                    (TextMatch.score(text, words) ?: if (line.kind == NamedLine.Kind.Ferrata) TextMatch.score("$FERRATA $text", words)?.plus(KIND_PENALTY) else null)
                        ?.let { it to line }
                }
                .sortedWith(compareBy({ it.first }, { near?.let { n -> Geo.distance(n, it.second.middle) } ?: 0.0 }))
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
            val special = NamedLines.withoutRepeats(runCatching { namedLines.namedLines() }.getOrDefault(emptyList()))
            // A path the map names that is a via ferrata or a route already found: that one, richer.
            val paths = mapNames.paths().filter { p ->
                special.none { it.name.equals(p.name, ignoreCase = true) && NamedLines.close(it.box, p.box) }
            }
            lines.value = (special + paths).map { TextMatch.normalize(it.searchText) to it }
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
        const val KIND_PENALTY = 5
        const val FERRATA = "via ferrata"

        /** A town or peak this close to a point of the same name is that point. */
        const val SAME_PLACE_M = 1_000.0
        val WORLD = BoundingBox(-90.0, -180.0, 90.0, 180.0)
    }
}
