package app.sentrek.record

import android.content.Context
import android.net.Uri
import android.util.Log
import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.Gpx
import app.sentrek.geo.gpx.GpxWaypoint
import app.sentrek.geo.place.Place
import app.sentrek.geo.place.PlaceKind
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The user's saved places, newest first, kept in one small `places.json`. */
@Singleton
class PlaceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val photos: PhotoStore,
) {
    private val file = File(context.filesDir, "places.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _places = MutableStateFlow<List<Place>>(emptyList())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    /** One write at a time, in order (see TrackRepository). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fileWrites = Dispatchers.IO.limitedParallelism(1)

    /** Writes wait for this, or a place added at startup would overwrite the file unread. */
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { if (file.exists()) parse(file.readText()) else emptyList() }
                    .onFailure { Log.w(TAG, "Cannot read places", it) }
                    .getOrDefault(emptyList())
            }
            // Keep anything added while the file was being read.
            _places.update { it + saved }
            loaded.complete(Unit)
        }
    }

    fun find(id: String): Place? = _places.value.firstOrNull { it.id == id }

    fun add(
        name: String,
        kind: PlaceKind,
        position: LatLon,
        note: String = "",
        color: Int? = null,
        photos: List<String> = emptyList(),
    ): Place {
        val place = Place(newId(), name.trim(), kind, position, note.trim(), color, System.currentTimeMillis(), photos)
        _places.update { listOf(place) + it }
        save()
        return place
    }

    fun update(place: Place) {
        find(place.id)?.let { old -> photos.delete(old.photos - place.photos.toSet()) }
        val cleaned = place.copy(name = place.name.trim(), note = place.note.trim())
        _places.update { all -> all.map { if (it.id == place.id) cleaned else it } }
        save()
    }

    fun delete(place: Place) {
        _places.update { all -> all.filterNot { it.id == place.id } }
        save()
        photos.delete(place.photos)
    }

    /** Places saved between [start] and [end] (ms), oldest first: those of a recording. */
    fun savedBetween(start: Long, end: Long): List<Place> =
        _places.value.filter { it.time in start..end }.sortedBy { it.time }

    /** Places from a Sentrek file that aren't here yet (by id); those added. */
    suspend fun restore(text: String): List<Place> {
        loaded.await()
        val known = _places.value.map { it.id }.toSet()
        val added = runCatching { parse(text) }.getOrDefault(emptyList()).filter { it.id !in known }
        if (added.isEmpty()) return emptyList()
        _places.update { all -> (all + added).sortedByDescending { it.time } }
        save()
        return added
    }

    /** Waypoints from an imported file; [fallbackName] for those without a name. */
    fun addAll(waypoints: List<GpxWaypoint>, fallbackName: String): List<Place> {
        val now = System.currentTimeMillis()
        val added = waypoints.map { w ->
            Place(
                id = newId(),
                name = w.name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName,
                kind = PlaceKind.guess(w.type),
                position = w.position,
                note = w.note?.trim().orEmpty(),
                time = w.time.takeIf { it > 0 } ?: now,
            )
        }
        _places.update { added + it }
        save()
        return added
    }

    /** All places as GPX waypoints, to a user-chosen document. */
    suspend fun export(target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target)?.bufferedWriter()?.use { out ->
                Gpx.write(out, EXPORT_NAME, emptyList(), CREATOR, _places.value.map(::waypoint))
            } ?: error("No output stream")
        }.onFailure { Log.w(TAG, "Export of places failed", it) }.isSuccess
    }

    private fun save() {
        scope.launch(fileWrites) {
            loaded.await()
            runCatching {
                // Temp file first: a half-written list must never replace a good one.
                val tmp = File(file.path + ".tmp")
                tmp.writeText(json(_places.value))
                if (!tmp.renameTo(file)) {
                    file.delete()
                    tmp.renameTo(file)
                }
            }.onFailure { Log.w(TAG, "Cannot save places", it) }
        }
    }

    private fun newId() = UUID.randomUUID().toString()


    private fun parse(text: String): List<Place> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                Place(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    kind = PlaceKind.entries.firstOrNull { it.id == o.optString("kind") } ?: PlaceKind.Star,
                    position = LatLon(o.getDouble("lat"), o.getDouble("lon"), if (o.has("ele")) o.getDouble("ele") else null),
                    note = o.optString("note"),
                    color = if (o.has("color")) o.getInt("color") else null,
                    time = o.optLong("time"),
                    photos = o.optJSONArray("photos")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(),
                )
            }.getOrNull()
        }
    }

    companion object {
        private const val TAG = "PlaceRepository"
        private const val CREATOR = "Sentrek"
        private const val EXPORT_NAME = "Sentrek places"
        const val EXPORT_FILE_NAME = "Sentrek places.gpx"

        /** The list as stored: in places.json and in Sentrek files. */
        fun json(places: List<Place>): String = JSONArray().apply {
            for (p in places) {
                put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("kind", p.kind.id)
                        .put("lat", p.position.lat)
                        .put("lon", p.position.lon)
                        .apply { p.position.ele?.let { put("ele", it) } }
                        .put("note", p.note)
                        .apply { p.color?.let { put("color", it) } }
                        .put("time", p.time)
                        .apply { if (p.photos.isNotEmpty()) put("photos", JSONArray(p.photos)) },
                )
            }
        }.toString()

        fun waypoint(p: Place) = GpxWaypoint(p.name, p.position, p.note, p.kind.id, p.time)
    }
}
