package app.sentrek.record

import app.sentrek.geo.dem.Panorama
import app.sentrek.geo.route.Shade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sun and shade on the rest of the followed route, at the user's pace: worked out from the
 * terrain every few minutes by [TrackingService], offline. A fraction of a second each time.
 */
@Singleton
class ShadeWatch @Inject constructor(
    private val follow: FollowRepository,
    private val elevation: ElevationRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var doneAt = 0L
    private var doneFor: String? = null

    private val _state = MutableStateFlow<Shade.Stretch?>(null)

    /** The next long stretch in the sun, or the one the user is in; null: none soon, or not following. */
    val state: StateFlow<Shade.Stretch?> = _state.asStateFlow()

    /** Every minute; works it out again every [EVERY_MS], or at once for another route. */
    fun check(following: Following?) {
        val key = following?.let { "${it.trackId} ${it.reversed}" }
        if (key == null) return stop()
        val now = System.currentTimeMillis()
        if (key == doneFor && now - doneAt < EVERY_MS) return
        if (job?.isActive == true) return
        doneAt = now
        doneFor = key
        job = scope.launch {
            val way = follow.wayAhead(STEP_M, MAX_POINTS)
            val points = way.mapNotNull { (along, position) ->
                val eta = follow.timeTo(along)?.let { now + it } ?: return@mapNotNull null
                // The terrain's own height: a route's may be from another source, a little off.
                Shade.Point(along - (follow.alongNow ?: 0.0), eta, position.copy(ele = null))
            }.filter { it.eta - now <= Shade.HORIZON_MS + Shade.LONG_MS }
            _state.value = withContext(Dispatchers.Default) {
                Shade.nextSunny(Shade.stretches(points, Panorama(elevation)), now)
            }
        }
    }

    fun stop() {
        job?.cancel()
        doneFor = null
        _state.value = null
    }

    private companion object {
        const val STEP_M = 200.0
        const val MAX_POINTS = 150
        const val EVERY_MS = 10 * 60_000L
    }
}
