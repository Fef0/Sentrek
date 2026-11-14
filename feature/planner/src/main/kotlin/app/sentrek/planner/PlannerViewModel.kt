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
import app.sentrek.geo.plan.RouteOptions
import app.sentrek.geo.plan.SavedPlan
import app.sentrek.geo.plan.SectionTotals
import app.sentrek.geo.plan.Sections
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
    /** The lines the map has loaded in [box]: quick, but only about what's on screen. */
    suspend fun lines(box: BoundingBox): List<TrailLine>

    /**
     * Every path and track of the downloaded areas in [box], read from their files: slower,
     * for when the map's own lines have no way through.
     */
    suspend fun areaLines(box: BoundingBox): List<TrailLine> = emptyList()

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
    /** How legs along the paths pick their way; saved with the route. */
    val options: RouteOptions = RouteOptions(),
    /** The options or a leg's way were changed since the plan was opened: there's something to save. */
    val optionsChanged: Boolean = false,
    /** Other ways for one leg, to pick from; null when none are asked for. */
    val choices: LegChoices? = null,
    /** Goes up when a leg is swapped for another way, so the legs are drawn again. */
    val routing: Int = 0,
) {
    val positions: List<LatLon> get() = points.map { it.position }

    /** The point the section button marks: the selected one, or the last; never the start. */
    val sectionTarget: Int? get() = (selected ?: points.lastIndex).takeIf { it > 0 }

    /**
     * The leg "other ways" works on: the one arriving at the selected point, or the last leg
     * when none is selected; only when it follows paths.
     */
    val choiceLeg: Int? get() = (selected ?: points.lastIndex).takeIf { it > 0 && points.getOrNull(it)?.onTrails == true }?.let { it - 1 }
}

/**
 * Other ways for leg [leg] (from point [leg] to the next), the best first; [loading] while
 * they're looked for. [shown]: the one looked at, drawn as the plan's own line.
 */
data class LegChoices(val leg: Int, val ways: List<Way>, val loading: Boolean = false, val shown: Int = 0) {
    /** One way: the leg as it would be drawn, and its totals. */
    data class Way(val leg: PlanLeg, val distance: Double, val ascent: Double, val descent: Double, val millis: Long, val hasElevation: Boolean)
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
    /** Each section's totals, when the route is cut into sections; empty otherwise. */
    val sections: List<SectionTotals> = emptyList(),
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

    private val _routingLegs = MutableStateFlow(false)

    /** Legs are being worked out along the paths: the totals and profile are about to change. */
    val routingLegs: StateFlow<Boolean> = _routingLegs.asStateFlow()

    /** Straight or already known legs at once, then the routed ones. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val legs: StateFlow<List<PlanLeg>> = _state
        .map { Triple(it.points, it.options, it.routing) }
        .distinctUntilChanged()
        .transformLatest { (points, _, _) ->
            emit(points.zipWithNext { a, b -> knownLeg(a, b) })
            if (points.zipWithNext().any { (a, b) -> b.onTrails && (a.position to b.position) !in routes }) {
                _routingLegs.value = true
                try {
                    emit(points.zipWithNext { a, b -> leg(a, b) })
                } finally {
                    _routingLegs.value = false
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val stats: StateFlow<PlanStats?> = combine(legs, _state.map { it.points }.distinctUntilChanged(), ::Pair)
        .mapLatest { (legs, points) -> if (legs.isEmpty()) null else stats(join(legs)).copy(sections = sections(points, legs)) }
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
        routes.clear()
        _state.value = _state.value.copy(editing = null, options = RouteOptions(), optionsChanged = false, choices = null)
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
                _state.value = _state.value.copy(active = true, editing = track, followTrails = true, options = plan.options, optionsChanged = false)
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

    /**
     * Back to the start: a last point on the first one, in the mode new legs are drawn in, so
     * the route ends where it began. Nothing when it already does.
     */
    fun closeLoop(): Boolean {
        val points = editor.points
        if (points.size < 2 || points.last().position == points.first().position) return false
        edit {
            editor.add(PlanPoint(editor.points.first().position, _state.value.followTrails))
            null
        }
        return true
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

    /**
     * Ends a section at the selected point, or at the last one when none is selected (the
     * route goes on from there), or takes that end away again.
     */
    fun toggleSection() = edit {
        val selected = _state.value.selected
        val index = selected ?: editor.points.lastIndex
        val point = editor.points.getOrNull(index)?.takeIf { index > 0 } ?: return@edit selected
        editor.move(index, point.copy(sectionEnd = !point.sectionEnd))
        selected
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

    /** How legs along the paths pick their way: they're all routed again. */
    fun setOptions(options: RouteOptions) = edit {
        if (options != _state.value.options) {
            routes.clear()
            _state.value = _state.value.copy(options = options, optionsChanged = true, choices = null)
        }
        _state.value.selected
    }

    /** Closes the path at [p] (moved onto the nearest path in reach): no leg may pass there. */
    fun avoid(p: LatLon) = edit {
        val spot = snap(p)
        val options = _state.value.options
        routes.clear()
        _state.value = _state.value.copy(options = options.copy(avoided = options.avoided + spot), optionsChanged = true, choices = null)
        _state.value.selected
    }

    /** Opens the avoided spot [index] again. */
    fun allow(index: Int) = edit {
        val options = _state.value.options
        if (index in options.avoided.indices) {
            routes.clear()
            _state.value = _state.value.copy(
                options = options.copy(avoided = options.avoided.filterIndexed { i, _ -> i != index }),
                optionsChanged = true,
                choices = null,
            )
        }
        _state.value.selected
    }

    /** Looks for other ways along the paths for the leg arriving at the selected point. */
    fun findOtherWays() {
        val state = _state.value
        val leg = state.choiceLeg ?: return
        val a = state.points[leg].position
        val b = state.points[leg + 1].position
        _state.value = state.copy(choices = LegChoices(leg, emptyList(), loading = true))
        viewModelScope.launch {
            val source = trails
            val ways = if (source == null) {
                emptyList()
            } else {
                val options = state.options
                // As for the leg itself: the wider search when the close one finds nothing.
                var found = emptyList<List<LatLon>>()
                for (wide in listOf(false, true)) {
                    val graph = graph(source, a, b, wide)
                    val heights = heights(graph, options)
                    found = withContext(Dispatchers.Default) { graph.alternatives(a, b, CONNECT_M, options, heights) }
                    if (found.isNotEmpty()) break
                }
                found.map { path ->
                    val way = leg(a, b, path)
                    val s = stats(way.points)
                    LegChoices.Way(way, s.distance, s.ascent, s.descent, s.millis, s.hasElevation)
                }
            }
            // Only for the plan they were asked for: an edit meanwhile drops them.
            if (_state.value.points == state.points && _state.value.choices?.leg == leg) {
                _state.value = _state.value.copy(choices = LegChoices(leg, ways))
            }
        }
    }

    /** Keeps way [index] of the choices for its leg. */
    fun chooseWay(index: Int) {
        val choices = _state.value.choices ?: return
        val way = choices.ways.getOrNull(index) ?: return
        val points = _state.value.points
        val a = points.getOrNull(choices.leg)?.position ?: return
        val b = points.getOrNull(choices.leg + 1)?.position ?: return
        routes[a to b] = way.leg
        _state.value = _state.value.copy(choices = null, routing = _state.value.routing + 1, optionsChanged = true)
    }

    /** Draws way [index] as the leg, to look at it before keeping it. */
    fun showWay(index: Int) {
        val choices = _state.value.choices ?: return
        if (index in choices.ways.indices) _state.value = _state.value.copy(choices = choices.copy(shown = index))
    }

    fun dismissChoices() {
        _state.value = _state.value.copy(choices = null)
    }

    /** The terrain's height at each of [graph]'s points, when [options] count climbing. */
    private suspend fun heights(graph: TrailGraph, options: RouteOptions): DoubleArray? {
        if (!options.needsHeights) return null
        return elevation.withElevation(graph.nodes).map { it.ele ?: Double.NaN }.toDoubleArray()
    }

    /** The leg from [a] to [b] as it is known now, for drawing while a point is dragged. */
    fun knownLeg(a: LatLon, b: LatLon, onTrails: Boolean): PlanLeg = knownLeg(PlanPoint(a, false), PlanPoint(b, onTrails))

    /** Saves the plan as a route (or over the route being edited) and leaves the planner. */
    fun save(name: String, note: String, photos: List<String>, onSaved: (SavedTrack) -> Unit) {
        viewModelScope.launch {
            val legs = legs.value
            if (legs.isEmpty()) return@launch
            val dense = stats.value?.dense?.takeIf { it.size >= 2 } ?: PlanGeometry.densify(join(legs))
            val segments = listOf(dense.map { TrackPoint(it, 0) })
            val plan = SavedPlan(_state.value.points, legs, _state.value.options)
            val editing = _state.value.editing
            val track = if (editing != null) {
                val replaced = tracks.replace(editing, segments, plan) ?: return@launch
                tracks.edit(replaced, name, note, photos) ?: replaced
            } else {
                tracks.save(segments, name, imported = true, smoothElevation = true, plan = plan, note = note, photos = photos)
                    .also { tracks.show(it) }
            }
            discard()
            onSaved(track)
        }
    }

    /** The name the save dialog starts with: the edited route's, or today's date. */
    fun defaultName(): String =
        editedTrack()?.name ?: DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date())

    /** The route being edited as it is saved now (its note may have changed since it was opened). */
    fun editedTrack(): SavedTrack? = _state.value.editing?.let { tracks.find(it.id) ?: it }

    /** Runs [change] after earlier edits; it returns the point to select afterwards. */
    private fun edit(change: suspend () -> Int?) {
        viewModelScope.launch {
            edits.withLock { publish(selected = change()) }
        }
    }

    private fun publish(selected: Int?, active: Boolean = _state.value.active) {
        val points = editor.points
        _state.value = _state.value.copy(
            // Other ways were for the legs as they were.
            choices = _state.value.choices?.takeIf { points == _state.value.points },
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
        val options = _state.value.options
        for (wide in listOf(false, true)) {
            val graph = graph(source, a, b, wide)
            val heights = heights(graph, options)
            val path = withContext(Dispatchers.Default) { graph.route(a, b, CONNECT_M, options, heights) }
            if (path != null) return leg(a, b, path)
        }
        return null
    }

    /**
     * The network around [a] and [b]: the map's lines close around them, or ([wide]) a wider
     * area with the downloaded areas' own paths, for a way round that leaves the screen.
     */
    private suspend fun graph(source: TrailSource, a: LatLon, b: LatLon, wide: Boolean): TrailGraph {
        val span = Geo.distance(a, b)
        val lines = if (wide) {
            val box = box(listOf(a, b), max(WIDE_MARGIN_M, span * 1.5))
            source.lines(box) + source.areaLines(box)
        } else {
            source.lines(box(listOf(a, b), max(MIN_MARGIN_M, span * 0.5)))
        }
        return withContext(Dispatchers.Default) { TrailGraph(lines) }
    }

    /** [path] as a leg from [a] to [b]: ends off the path join it in a straight line. */
    private fun leg(a: LatLon, b: LatLon, path: List<LatLon>): PlanLeg {
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

    /** The totals of each section; empty while the legs don't match the points (being routed). */
    private suspend fun sections(points: List<PlanPoint>, legs: List<PlanLeg>): List<SectionTotals> {
        if (legs.size != points.size - 1) return emptyList()
        return Sections.legRanges(points).map { range ->
            val s = stats(join(legs.subList(range.first, range.last + 1)))
            SectionTotals(s.distance, s.ascent, s.descent, s.millis, s.hasElevation, s.profile.firstOrNull()?.second, s.profile.lastOrNull()?.second)
        }
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

        /** The wider search's margin around a leg, at least. */
        private const val WIDE_MARGIN_M = 3_000.0

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
