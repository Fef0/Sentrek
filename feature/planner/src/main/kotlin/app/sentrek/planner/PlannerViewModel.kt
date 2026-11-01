package app.sentrek.planner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.feature.planner.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.plan.PlanEditor
import app.sentrek.geo.plan.PlanGeometry
import app.sentrek.geo.plan.PlanLeg
import app.sentrek.geo.plan.PlanPoint
import app.sentrek.geo.plan.SavedPlan
import app.sentrek.geo.plan.TrailGraph
import app.sentrek.geo.plan.TrailLine
import app.sentrek.geo.route.HikingTime
import app.sentrek.geo.route.Route
import app.sentrek.geo.track.TrackPoint
import app.sentrek.record.SavedTrack
import app.sentrek.record.ElevationRepository
import app.sentrek.record.FollowRepository
import app.sentrek.record.RecordingRepository
import app.sentrek.record.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.max

/** What keeps the planner closed: see [PlannerViewModel.blockedBy]. */
enum class PlanBlock(val message: Int) {
    Following(R.string.plan_blocked_following),
    Recording(R.string.plan_blocked_recording),
    Both(R.string.plan_blocked_both),
}

/** Where the planner finds paths to follow: the lines of the map around a box. */
interface TrailSource {
    suspend fun lines(box: BoundingBox): List<TrailLine>

    /** How far from a tap a path still counts as meant, in metres (depends on the zoom). */
    fun snapRadiusM(): Double
}

/**
 * The route being planned, as edited on the map. [selected]: index of the tapped point.
 * [followTrails]: the mode for new legs.
 */
data class PlanState(
    val active: Boolean = false,
    val points: List<PlanPoint> = emptyList(),
    val selected: Int? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val followTrails: Boolean = true,
    /** The saved route being edited; null for a new one. */
    val editing: SavedTrack? = null,
) {
    val positions: List<LatLon> get() = points.map { it.position }
}

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
    recording: RecordingRepository,
    following: FollowRepository,
) : ViewModel() {
    /**
     * Why a plan can't be opened now, or null when it can. Out on the trail, planning would
     * cover the map with a second line and take the Stop button out of the bar: it waits.
     */
    val blockedBy: StateFlow<PlanBlock?> = combine(recording.state, following.state) { r, f ->
        when {
            r != null && f != null -> PlanBlock.Both
            r != null -> PlanBlock.Recording
            f != null -> PlanBlock.Following
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val editor = PlanEditor<PlanPoint>()
    private val _state = MutableStateFlow(PlanState())
    val state: StateFlow<PlanState> = _state.asStateFlow()

    /** Set by the map screen while the map is there. */
    var trails: TrailSource? = null

    /** Edits wait for each other: a tap's point is snapped to a path before the next one lands. */
    private val edits = Mutex()

    /** Routed legs by their ends; null: no way along the paths. */
    private val routes = HashMap<Pair<LatLon, LatLon>, PlanLeg?>()

    /** Straight or already known legs at once, then the routed ones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val legs: StateFlow<List<PlanLeg>> = _state
        .map { it.points }
        .distinctUntilChanged()
        .transformLatest { points ->
            emit(points.zipWithNext { a, b -> knownLeg(a, b) })
            if (points.zipWithNext().any { (a, b) -> b.onTrails && (a.position to b.position) !in routes }) {
                emit(points.zipWithNext { a, b -> leg(a, b) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<PlanStats?> = legs
        .mapLatest { legs -> if (legs.isEmpty()) null else stats(join(legs)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun start() {
        if (blockedBy.value != null) return
        _state.value = _state.value.copy(active = true)
        // Terrain that couldn't be loaded last time (offline) may load now.
        viewModelScope.launch { elevation.retryMissing() }
    }

    /** Leaves the planner; the plan is kept until [discard] or a save. */
    fun close() {
        _state.value = _state.value.copy(active = false, selected = null)
    }

    fun discard() {
        editor.reset(emptyList())
        _state.value = _state.value.copy(editing = null)
        publish(selected = null, active = false)
    }

    /**
     * Opens a saved route in the planner, with its points and legs as they were. False (and
     * nothing changes) while another plan with points is open, for a recorded track, or while
     * [blockedBy] says not now.
     */
    fun editRoute(track: SavedTrack, onOpened: (Boolean) -> Unit) {
        if (blockedBy.value != null || (_state.value.active && _state.value.points.isNotEmpty())) {
            onOpened(false)
            return
        }
        viewModelScope.launch {
            val plan = tracks.plan(track)
            if (plan == null || plan.points.size < 2) {
                onOpened(false)
                return@launch
            }
            // Legs keep their saved shape until a point at either end moves.
            plan.points.zipWithNext().zip(plan.legs) { (a, b), leg ->
                if (b.onTrails && leg.onTrails) routes[a.position to b.position] = leg
            }
            edits.withLock {
                editor.reset(plan.points)
                _state.value = _state.value.copy(active = true, editing = track, followTrails = true)
                publish(selected = null)
            }
            onOpened(true)
        }
    }

    fun add(p: LatLon) = edit {
        val follow = _state.value.followTrails
        editor.add(PlanPoint(if (follow) snap(p) else p, follow))
        null
    }

    fun select(index: Int?) {
        _state.value = _state.value.copy(selected = index?.takeIf { it in editor.points.indices })
    }

    fun move(index: Int, p: LatLon) = edit {
        val point = editor.points.getOrNull(index) ?: return@edit null
        val next = editor.points.getOrNull(index + 1)
        val onPath = point.onTrails || next?.onTrails == true
        editor.move(index, point.copy(position = if (onPath) snap(p) else p))
        index
    }

    /** A point inserted on leg [leg] (between points leg and leg + 1), in that leg's mode. */
    fun insert(leg: Int, p: LatLon) = edit {
        val follow = editor.points.getOrNull(leg + 1)?.onTrails ?: return@edit null
        editor.insert(leg + 1, PlanPoint(if (follow) snap(p) else p, follow))
        leg + 1
    }

    /** Removes the selected point, or the last one when none is selected. */
    fun removePoint() = edit {
        editor.remove(_state.value.selected ?: editor.points.lastIndex)
        null
    }

    /**
     * Switches between following paths and straight lines: for new legs, and for the leg
     * arriving at the selected point.
     */
    fun toggleTrails() = edit {
        val follow = !_state.value.followTrails
        _state.value = _state.value.copy(followTrails = follow)
        val selected = _state.value.selected
        val point = selected?.takeIf { it > 0 }?.let { editor.points.getOrNull(it) }
        if (point != null && point.onTrails != follow) editor.move(selected, point.copy(onTrails = follow))
        selected
    }

    /**
     * Turns the route around: the same legs walked from the end to the start, at once (the
     * paths go both ways, so nothing is routed again). The totals and the profile follow.
     */
    fun reverse() = edit {
        val points = editor.points
        if (points.size < 2) return@edit _state.value.selected
        points.zipWithNext().zip(legs.value) { (a, b), leg ->
            // Legs still being routed (or from before the last edit) are routed again instead.
            val same = leg.points.firstOrNull() == a.position && leg.points.lastOrNull() == b.position
            if (b.onTrails && leg.onTrails && same) routes[b.position to a.position] = leg.reversed()
        }
        editor.replace(SavedPlan.reversed(points))
        _state.value.selected?.let { points.lastIndex - it }
    }

    fun undo() = edit {
        editor.undo()
        null
    }

    fun redo() = edit {
        editor.redo()
        null
    }

    /** The leg from [a] to [b] as it is known now, for drawing while a point is dragged. */
    fun knownLeg(a: LatLon, b: LatLon, onTrails: Boolean): PlanLeg = knownLeg(PlanPoint(a, false), PlanPoint(b, onTrails))

    /** Saves the plan as a route in the track list and leaves the planner. */
    /** Saves the plan as a route (or over the route being edited) and leaves the planner. */
    fun save(name: String, onSaved: (SavedTrack) -> Unit) {
        viewModelScope.launch {
            val legs = legs.value
            if (legs.isEmpty()) return@launch
            val dense = stats.value?.dense?.takeIf { it.size >= 2 } ?: PlanGeometry.densify(join(legs))
            val segments = listOf(dense.map { TrackPoint(it, 0) })
            val plan = SavedPlan(_state.value.points, legs)
            val editing = _state.value.editing
            val track = if (editing != null) {
                val replaced = tracks.replace(editing, segments, plan) ?: return@launch
                if (name.isNotBlank() && name.trim() != replaced.name) tracks.rename(replaced, name)
                tracks.find(replaced.id) ?: replaced
            } else {
                tracks.save(segments, name, imported = true, smoothElevation = true, plan = plan).also { tracks.show(it) }
            }
            discard()
            onSaved(track)
        }
    }

    /** The name the save dialog starts with: the edited route's, or today's date. */
    fun defaultName(): String =
        _state.value.editing?.name ?: DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())

    /** Runs [change] after earlier edits; it returns the point to select afterwards. */
    private fun edit(change: suspend () -> Int?) {
        viewModelScope.launch {
            edits.withLock { publish(selected = change()) }
        }
    }

    private fun publish(selected: Int?, active: Boolean = _state.value.active) {
        _state.value = _state.value.copy(
            active = active,
            points = editor.points,
            selected = selected?.takeIf { it in editor.points.indices },
            canUndo = editor.canUndo,
            canRedo = editor.canRedo,
        )
    }

    private fun knownLeg(a: PlanPoint, b: PlanPoint): PlanLeg {
        if (!b.onTrails) return PlanLeg(listOf(a.position, b.position), false)
        return routes[a.position to b.position] ?: PlanLeg(listOf(a.position, b.position), false)
    }

    private suspend fun leg(a: PlanPoint, b: PlanPoint): PlanLeg {
        if (b.onTrails) {
            val key = a.position to b.position
            if (key !in routes) routes[key] = route(a.position, b.position)
        }
        return knownLeg(a, b)
    }

    /**
     * The way along the paths from [a] to [b]. An end off the paths (dropped where there is
     * none) joins the nearest one within [CONNECT_M] in a straight line.
     */
    private suspend fun route(a: LatLon, b: LatLon): PlanLeg? {
        val source = trails ?: return null
        val span = Geo.distance(a, b)
        val lines = source.lines(box(listOf(a, b), max(MIN_MARGIN_M, span * 0.5)))
        val path = withContext(Dispatchers.Default) { TrailGraph(lines).route(a, b, CONNECT_M) } ?: return null
        val offStart = Geo.distance(a, path.first()) > ON_PATH_M
        val offEnd = Geo.distance(b, path.last()) > ON_PATH_M
        val points = listOfNotNull(a.takeIf { offStart }) + path + listOfNotNull(b.takeIf { offEnd })
        return PlanLeg(points, onTrails = true, offPathStart = offStart, offPathEnd = offEnd)
    }

    /** [p] moved onto the nearest path within reach, or [p] itself. */
    private suspend fun snap(p: LatLon): LatLon {
        val source = trails ?: return p
        val radius = source.snapRadiusM()
        val lines = source.lines(box(listOf(p), radius + 50))
        return withContext(Dispatchers.Default) { TrailGraph(lines).snap(p, radius) } ?: p
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

    companion object {
        /** Points are on a path already; this only absorbs rounding. */
        private const val ON_PATH_M = 5.0

        /** How far an off-path point looks for a path to join. */
        private const val CONNECT_M = 300.0
        private const val MIN_MARGIN_M = 500.0

        /** The legs end to end, without repeating the shared points. */
        fun join(legs: List<PlanLeg>): List<LatLon> =
            legs.flatMapIndexed { i, leg -> if (i == 0) leg.points else leg.points.drop(1) }

        /** A box around [points], [marginM] wider on each side. */
        fun box(points: List<LatLon>, marginM: Double): BoundingBox {
            val dLat = marginM / (Geo.EARTH_RADIUS_M * Math.toRadians(1.0))
            val dLon = dLat / cos(Math.toRadians(points.first().lat)).coerceAtLeast(0.01)
            return BoundingBox(
                (points.minOf { it.lat } - dLat).coerceAtLeast(-90.0),
                (points.minOf { it.lon } - dLon).coerceAtLeast(-180.0),
                (points.maxOf { it.lat } + dLat).coerceAtMost(90.0),
                (points.maxOf { it.lon } + dLon).coerceAtMost(180.0),
            )
        }
    }
}
