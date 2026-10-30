package app.sentrek.record

import android.content.Context
import android.text.format.DateFormat
import app.sentrek.data.TurnaroundMode
import app.sentrek.feature.record.R
import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import app.sentrek.geo.route.Turnaround
import java.util.Calendar
import java.util.Date

/**
 * The turnaround alarm: long buzzes and a notification that pops up when it's time to think
 * about turning back. Off unless the user turns it on. Driven by [TrackingService] every
 * minute, so it rings with the screen off and the phone in a pocket.
 */
class TurnaroundAlarm(private val context: Context) {
    private var turnaround = Turnaround()
    private var route: String? = null

    /**
     * [at]: the last position known, [following] the route if any. A new route starts the
     * "before dark" check over; the set time rings once while the service runs.
     */
    fun check(mode: TurnaroundMode, marginMinutes: Int, minuteOfDay: Int, at: LatLon?, following: Following?) {
        val now = System.currentTimeMillis()
        val key = following?.let { "${it.trackId} ${it.reversed}" }
        if (key != null && key != route) {
            route = key
            turnaround = Turnaround()
        }
        when (mode) {
            TurnaroundMode.Off -> Unit
            TurnaroundMode.BeforeDark -> {
                val sunset = at?.let { Sun.day(now, it.lat, it.lon)?.sunset }
                val arrival = following?.let { now + it.remaining.millis }
                if (turnaround.beforeDark(now, arrival, sunset, marginMinutes * 60_000L)) {
                    ring(
                        context.getString(R.string.turnaround_dark_title),
                        context.getString(R.string.turnaround_dark_text, time(arrival!!), time(sunset!!)),
                    )
                }
            }
            TurnaroundMode.AtTime -> {
                val alarm = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                    set(Calendar.MINUTE, minuteOfDay % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (turnaround.atTime(now, alarm)) {
                    ring(context.getString(R.string.turnaround_time_title), context.getString(R.string.turnaround_time_text, time(alarm)))
                }
            }
        }
    }

    private fun time(millis: Long) = DateFormat.getTimeFormat(context).format(Date(millis))

    private fun ring(title: String, text: String) = TrailAlerts.ring(context, NOTIFICATION_ID, title, text, PATTERN)

    private companion object {
        const val NOTIFICATION_ID = 5

        /** Four long buzzes: unlike anything else the app does. */
        val PATTERN = longArrayOf(0, 1000, 400, 1000, 400, 1000, 400, 1000)
    }
}
