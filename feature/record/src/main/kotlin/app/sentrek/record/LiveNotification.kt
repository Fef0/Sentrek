package app.sentrek.record

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import app.sentrek.feature.record.R
import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import app.sentrek.geo.overlay.PoiKind
import app.sentrek.geo.place.PlaceKind
import app.sentrek.geo.route.RoutePlace
import app.sentrek.geo.route.UpcomingPlace
import app.sentrek.ui.Format
import java.util.Date
import java.util.Locale

/**
 * The recording / following notification, readable from the lock screen without unlocking:
 * on Android 16+ a Live Update (pinned on the lock screen, a chip in the status bar, a
 * progress bar along the route with the places ahead), elsewhere an expanded notification
 * with the same lines.
 */
internal class LiveNotification(private val context: Context, private val channel: String) {
    /** What the user asked of the Pause/Resume button: a first tap only arms it. */
    var armed = false

    fun build(r: Recording?, f: Following?, at: LatLon?, now: Long, openApp: PendingIntent?, toggle: PendingIntent): Notification {
        val offRoute = f?.offRoute == true
        val title = when {
            offRoute -> context.getString(R.string.follow_notification_off_route, Format.distance(f?.offset ?: 0.0))
            f != null -> context.getString(R.string.follow_notification_left, Format.distance(f.remaining.distance), Format.estimate(f.remaining.millis))
            r?.isPaused == true -> context.getString(R.string.record_notification_paused)
            else -> context.getString(R.string.record_notification_title)
        }
        // Place names stay off a lock screen that hides private content (see the public version).
        val numbers = buildList {
            if (f != null && !offRoute && f.hasElevation && f.remaining.ascent >= MIN_CLIMB_M) {
                add(context.getString(R.string.live_climb, Format.elevation(f.remaining.ascent)))
            }
            if (offRoute && f != null) add(context.getString(R.string.follow_notification_title, f.name))
            if (r != null && f == null) add(context.getString(R.string.record_notification_text, Format.distance(r.distance), Format.elevation(r.ascent)))
            sunset(at, f?.remaining?.millis?.takeIf { !offRoute }, now)?.let(::add)
        }
        val next = f?.takeIf { !offRoute }?.upcoming?.firstOrNull()?.let { ahead ->
            context.getString(R.string.live_next, name(ahead), Format.distance(ahead.distance))
        }
        // A walk ending in the dark comes first: the collapsed view shows only one line.
        val text = numbers.partition { it.startsWith(WARNING) }.let { (warnings, rest) -> warnings + listOfNotNull(next) + rest }
        val chip = when {
            offRoute -> WARNING + Format.shortDistance(f?.offset ?: 0.0)
            f != null -> Format.distance(f.remaining.distance)
            r?.isPaused == true -> Format.distance(r.distance)
            // While recording, the chip shows the running clock.
            else -> null
        }

        fun builder(lines: List<String>) = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(lines.joinToString("\n").ifEmpty { null })
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (f != null) NotificationCompat.CATEGORY_NAVIGATION else NotificationCompat.CATEGORY_WORKOUT)
            .apply {
                val running = r != null && !r.isPaused
                // The chronometer can't skip pauses: it counts from a start time shifted by them.
                setUsesChronometer(running)
                setWhen(if (r != null) now - r.activeMillis(now) else now)
                setShowWhen(running)
                setRequestPromotedOngoing(true)
                chip?.let(::setShortCriticalText)
                if (r != null) {
                    val label = when {
                        r.isPaused && armed -> R.string.live_resume_confirm
                        r.isPaused -> R.string.live_resume
                        armed -> R.string.live_pause_confirm
                        else -> R.string.live_pause
                    }
                    addAction(0, context.getString(label), toggle)
                }
                val progress = f?.let(::progress)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && progress != null) {
                    setStyle(progress.setProgress(((f.here ?: f.along) ?: 0.0).toInt()))
                } else {
                    if (lines.size > 1) setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
                    if (f != null) {
                        val total = total(f)
                        if (total > 0) setProgress(total, ((f.along ?: 0.0).toInt()).coerceIn(0, total), false)
                    }
                }
            }

        val public = builder(numbers).build()
        return builder(text).setPublicVersion(public).build()
    }

    /** The route as a bar, with the places ahead as dots coloured by what they are. */
    private fun progress(f: Following): NotificationCompat.ProgressStyle? {
        val total = total(f)
        if (total <= 0) return null
        val at = (f.along ?: 0.0)
        return NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(IconCompat.createWithResource(context, R.drawable.ic_stat_record))
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(total).setColor(ROUTE_COLOR)))
            .setProgressPoints(
                f.upcoming.take(MAX_POINTS).mapNotNull { ahead ->
                    val position = (at + ahead.distance).toInt()
                    if (position in 1 until total) NotificationCompat.ProgressStyle.Point(position).setColor(color(ahead.place.kind)) else null
                },
            )
    }

    private fun total(f: Following): Int = ((f.along ?: 0.0) + f.remaining.distance).toInt()

    /**
     * The coming dark, as on the map: a warning when the walk ends after sunset, the time of
     * sunset in the last hours of daylight, nothing otherwise.
     */
    private fun sunset(at: LatLon?, remainingMillis: Long?, now: Long): String? {
        val here = at ?: return null
        val sunset = Sun.day(now, here.lat, here.lon)?.sunset ?: return null
        if (now >= sunset) return null
        val time = DateFormat.getTimeFormat(context)
        val arrival = remainingMillis?.let { now + it }
        return when {
            arrival != null && arrival > sunset ->
                WARNING + context.getString(R.string.live_sunset_late, time.format(Date(sunset)), time.format(Date(arrival)))
            sunset - now <= SOON_MS -> context.getString(R.string.live_sunset_in, time.format(Date(sunset)), hoursMinutes(sunset - now))
            else -> null
        }
    }

    private fun name(ahead: UpcomingPlace) = ahead.place.name ?: context.getString(routePlaceLabel(ahead.place.kind))

    private fun color(kind: RoutePlace.Kind): Int = when (kind) {
        is RoutePlace.Kind.Saved -> if (kind.kind == PlaceKind.Water) WATER_COLOR else kind.color
        RoutePlace.Kind.Waypoint -> OTHER_COLOR
        is RoutePlace.Kind.Osm -> when (kind.kind) {
            PoiKind.Spring, PoiKind.DrinkingWater -> WATER_COLOR
            PoiKind.AlpineHut, PoiKind.WildernessHut, PoiKind.Shelter, PoiKind.Camping -> SHELTER_COLOR
            else -> OTHER_COLOR
        }
    }

    /** Changes when anything shown changes, so the notification is only sent again then. */
    fun summary(r: Recording?, f: Following?, at: LatLon?, now: Long): String = listOf(
        r?.let { "${it.isPaused} ${it.distance.toInt() / 10} ${it.ascent.toInt()}" },
        f?.let { "${it.offRoute} ${it.remaining.distance.toInt() / 10} ${it.remaining.millis / 60_000} ${it.upcoming.firstOrNull()?.distance?.toInt()?.div(10)}" },
        sunset(at, f?.remaining?.millis, now),
        armed,
    ).joinToString()

    private fun hoursMinutes(millis: Long): String {
        val minutes = (millis / 60_000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "%d:%02d h", minutes / 60, minutes % 60)
    }

    private companion object {
        /** Less climb than this left is noise, not worth a line. */
        const val MIN_CLIMB_M = 10.0
        const val MAX_POINTS = 6
        const val WARNING = "⚠ "
        const val SOON_MS = 2 * 3_600_000L
        const val ROUTE_COLOR = 0xFFFF8A3D.toInt()
        const val WATER_COLOR = 0xFF1E88E5.toInt()
        const val SHELTER_COLOR = 0xFFD84315.toInt()
        const val OTHER_COLOR = 0xFF9E9E9E.toInt()
    }
}
