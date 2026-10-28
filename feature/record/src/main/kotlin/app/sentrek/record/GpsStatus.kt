package app.sentrek.record

import android.location.Location
import android.os.SystemClock

/** What the GPS is doing, as shown next to the location button. */
sealed interface GpsStatus {
    /** Location is switched off in the phone's settings. */
    data object Off : GpsStatus

    /** No recent fix; [satellites] heard so far. */
    data class Searching(val satellites: Int) : GpsStatus

    /** A recent fix, [accuracy] in metres when known. */
    data class Fix(val accuracy: Float?) : GpsStatus {
        val weak: Boolean get() = accuracy == null || accuracy > WEAK_M
    }

    companion object {
        /** Worse than this, the position may well be on the wrong side of a valley. */
        const val WEAK_M = 30f

        /** A fix older than this means the GPS has lost the sky. */
        private const val FRESH_MS = 10_000L

        fun of(gpsOn: Boolean, lastFix: Location?, satellites: Satellites): GpsStatus {
            if (!gpsOn) return Off
            val ageMs = lastFix?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
            if (lastFix == null || ageMs == null || ageMs > FRESH_MS) return Searching(satellites.heard)
            return Fix(lastFix.accuracy.takeIf { lastFix.hasAccuracy() })
        }
    }
}
