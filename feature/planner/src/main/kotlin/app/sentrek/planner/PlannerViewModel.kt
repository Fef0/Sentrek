package app.sentrek.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.plan.PlanEditor
import app.sentrek.geo.plan.PlanGeometry
import app.sentrek.geo.route.HikingTime
import app.sentrek.geo.route.Route
import app.sentrek.geo.track.TrackPoint
import app.sentrek.record.SavedTrack
import app.sentrek.record.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** The route being planned, as drawn and edited on the map. [selected]: index of the tapped point. */
data class PlanState(
    val active: Boolean = false,
    val points: List<LatLon> = emptyList(),
    val selected: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

/**
 * Totals for the planned route. Without terrain data (offline, area never seen) [hasElevation]
 * is false and the time counts distance only.
 */
data class PlanStats(
    val distance: Double,
    val ascent: Double,
    val descent: Double,
    val millis: Long,
    val hasElevation: Boolean,
    /** Distance along the route (m) to elevation (m), for the profile. */
    val profile: List<Pair<Double, Double>>,
    /** The legs every ~30 m with terrain heights: what gets saved. */
    val dense: List<LatLon>,
)

@HiltViewModel
class PlannerViewModel @Inject constructor(
    private val elevation: ElevationRepository,
    private val tracks: TrackRepository,
) : ViewModel() {
    private val editor = PlanEditor()
    private val _state = MutableStateFlow(PlanState())
    val state: StateFlow<PlanState> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<PlanStats?> = _state
        .map { it.points }
        .distinctUntilChanged()
        .mapLatest { points -> if (points.size < 2) null else stats(points) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun start() {
        _state.value = _state.value.copy(active = true)
        // Terrain that couldn't be loaded last time (offline) may load now.
        viewModelScope.launch { elevation.retryMissing() }
    }

    /** Leaves the planner; the plan is kept until [discard] or a save. */
    fun close() {
        _state.value = _state.value.copy(active = false, selected = null)
    }

    fun discard() {
        editor.clear()
        publish(selected = null, active = false)
    }

    fun add(p: LatLon) {
        editor.add(p)
        publish(selected = null)
    }

    fun select(index: Int?) {
        _state.value = _state.value.copy(selected = index?.takeIf { it in editor.points.indices })
    }

    fun move(index: Int, p: LatLon) {
        editor.move(index, p)
        publish(selected = index)
    }

    /** A point inserted on leg [leg] (between points leg and leg + 1). */
    fun insert(leg: Int, p: LatLon) {
        editor.insert(leg + 1, p)
        publish(selected = leg + 1)
    }

    /** Removes the selected point, or the last one when none is selected. */
    fun removePoint() {
        val index = _state.value.selected ?: editor.points.lastIndex
        editor.remove(index)
        publish(selected = null)
    }

    fun undo() {
        editor.undo()
        publish(selected = null)
    }

    fun redo() {
        editor.redo()
        publish(selected = null)
    }

    /** Saves the plan as a route in the track list and leaves the planner. */
    fun save(name: String, onSaved: (SavedTrack) -> Unit) {
        viewModelScope.launch {
            val points = _state.value.points
            if (points.size < 2) return@launch
            val dense = stats.value?.dense?.takeIf { it.size >= 2 } ?: PlanGeometry.densify(points)
            val track = tracks.save(listOf(dense.map { TrackPoint(it, 0) }), name, imported = true, smoothElevation = true)
            tracks.show(track)
            discard()
            onSaved(track)
        }
    }

    fun defaultName(): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())

    private fun publish(selected: Int?, active: Boolean = _state.value.active) {
        _state.value = PlanState(active, editor.points, selected, editor.canUndo, editor.canRedo)
    }

    private suspend fun stats(points: List<LatLon>): PlanStats {
        val dense = elevation.withElevation(PlanGeometry.densify(points))
        // Partly missing terrain would make up a climb from nothing: all or none.
        val complete = dense.all { it.ele != null }
        val route = Route(if (complete) dense else dense.map { it.copy(ele = null) })
        var along = 0.0
        val profile = if (complete) {
            dense.mapIndexed { i, p ->
                if (i > 0) along += Geo.distance(dense[i - 1], p)
                along to p.ele!!
            }
        } else {
            emptyList()
        }
        return PlanStats(
            distance = route.length,
            ascent = route.ascent,
            descent = route.descent,
            millis = HikingTime.millis(route.length, route.ascent, route.descent),
            hasElevation = complete,
            profile = profile,
            dense = route.points,
        )
    }
}
