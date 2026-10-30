package app.sentrek.record

import android.content.Context
import android.text.format.DateFormat
import android.util.Log
import app.sentrek.feature.record.R
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.weather.Hourly
import app.sentrek.geo.weather.WetAhead
import app.sentrek.map.MapEngine
import app.sentrek.ui.Format
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When and where the walk gets wet: the hourly forecast along the rest of the followed route,
 * each point at the time the user will be there (or here, without a route). One small request
 * an hour while there's a connection; without one, the last forecast still counts for a while.
 * A storm on the way buzzes once.
 */
@Singleton
class WetWatch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val follow: FollowRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** A forecast for a point of the way: [along] the route (0 without one). */
    private class Point(val along: Double, val position: LatLon, val forecast: Hourly)

    private var points: List<Point> = emptyList()
    private var fetchedAt = 0L
    private var fetchedFor: String? = null
    private var fetchedWhere: LatLon? = null
    private var triedAt = 0L
    private var fetching: Job? = null
    private var stormWarned = false

    private val _state = MutableStateFlow<WetAhead.Found?>(null)

    /** The first wet spot on the way, if any; null while dry, unknown, or not walking. */
    val state: StateFlow<WetAhead.Found?> = _state.asStateFlow()

    /** Every minute from [TrackingService]: [at] the last position known. */
    fun check(at: LatLon?, following: Following?) {
        val now = System.currentTimeMillis()
        val key = following?.let { "${it.trackId} ${it.reversed}" }
        val stale = now - fetchedAt > REFRESH_MS || key != fetchedFor ||
            (key == null && at != null && fetchedWhere?.let { Geo.distance(it, at) > MOVED_M } != false)
        if (stale && now - triedAt > RETRY_MS && fetching?.isActive != true && (key != null || at != null)) fetch(at, key)
        if (now - fetchedAt > KEEP_MS || key != fetchedFor) {
            _state.value = null
            return
        }
        val found = if (key != null) {
            val here = follow.alongNow ?: 0.0
            val samples = points.filter { it.along >= here - STEP_M / 2 }.mapNotNull { p ->
                follow.timeTo(p.along)?.let { WetAhead.Sample(maxOf(0.0, p.along - here), now + it, p.position.ele, p.forecast) }
            }
            WetAhead.first(samples, now)
        } else {
            points.firstOrNull()?.let { WetAhead.here(it.forecast, now) }
        }
        _state.value = found
        if (found?.wet == Hourly.Wet.Storm && !stormWarned && found.from - now <= STORM_WARN_MS) {
            stormWarned = true
            val time = DateFormat.getTimeFormat(context).format(Date(found.from))
            val text = if (key != null) {
                context.getString(R.string.storm_alert_route, time, Format.distance(found.ahead))
            } else {
                context.getString(R.string.storm_alert_here, time)
            }
            TrailAlerts.ring(context, STORM_NOTIFICATION_ID, context.getString(R.string.storm_alert_title), text, STORM_PATTERN)
        }
    }

    fun stop() {
        fetching?.cancel()
        points = emptyList()
        fetchedAt = 0
        fetchedFor = null
        stormWarned = false
        _state.value = null
    }

    private fun fetch(at: LatLon?, key: String?) {
        triedAt = System.currentTimeMillis()
        val way = if (key != null) follow.wayAhead(STEP_M, MAX_POINTS) else listOfNotNull(at?.let { 0.0 to it })
        if (way.isEmpty()) return
        fetching = scope.launch {
            val forecasts = withContext(Dispatchers.IO) {
                runCatching {
                    val url = Hourly.url(way.map { it.second.lat to it.second.lon })
                    MapEngine.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body?.charStream()?.let(Hourly::parse)
                    }
                }.onFailure { Log.d(TAG, "No forecast: ${it.message}") }.getOrNull()
            }
            if (forecasts == null || forecasts.size != way.size) return@launch
            points = way.zip(forecasts) { (along, position), forecast -> Point(along, position, forecast) }
            fetchedAt = System.currentTimeMillis()
            fetchedFor = key
            fetchedWhere = at
        }
    }

    private companion object {
        const val TAG = "WetWatch"
        const val STEP_M = 2_000.0
        const val MAX_POINTS = 12
        const val REFRESH_MS = 60 * 60_000L
        const val RETRY_MS = 10 * 60_000L

        /** Offline, the last forecast still says something for this long. */
        const val KEEP_MS = 6 * 60 * 60_000L

        /** Without a route, a new forecast once the user is this far from the last one's place. */
        const val MOVED_M = 5_000.0

        /** A storm this close in time is worth a buzz. */
        const val STORM_WARN_MS = 3 * 60 * 60_000L
        const val STORM_NOTIFICATION_ID = 8

        /** Three long, one short: a storm. */
        val STORM_PATTERN = longArrayOf(0, 700, 250, 700, 250, 700, 250, 200)
    }
}
