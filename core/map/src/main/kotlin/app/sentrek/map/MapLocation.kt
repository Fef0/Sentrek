package app.sentrek.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import org.maplibre.android.location.CompassListener
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.abs

/**
 * The user's position on the map: arrow pointing where the phone faces (compass), plus the
 * GPS accuracy circle. Fixes are pushed in by the app (one GPS source for map and recording),
 * so MapLibre's own location engine stays off.
 */
object MapLocation {
    /** Zoom used when starting to follow from far out. */
    private const val FOLLOW_ZOOM = 15.0
    private const val TRANSITION_MS = 750L
    private const val MAX_ANIMATION_FPS = 20
    private const val SAVER_ANIMATION_FPS = 10

    /**
     * While the camera follows the walk, each step moves the whole map: a few frames per step
     * are enough at walking speed.
     */
    private const val FOLLOW_ANIMATION_FPS = 10
    private const val SAVER_FOLLOW_ANIMATION_FPS = 5

    /** The dot slides to each new fix in a couple of steps instead of gliding. */
    private const val MINIMAL_ANIMATION_FPS = 2

    /** How much the position marker may cost. */
    enum class Power { Normal, Saver, Minimal }

    /**
     * Shows the position. [Power.Saver] turns the arrow less often; [Power.Minimal] drops the
     * compass (a dot instead of an arrow, the sensor off). Call [hide] when the map is covered
     * by another screen: the compass sensor and the redraws stop with it.
     */
    @SuppressLint("MissingPermission") // No engine: MapLibre never touches the GPS itself.
    fun activate(context: Context, map: MapLibreMap, style: Style, power: Power = Power.Normal) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    // No greying out after 30 s without a new fix: standing still, fixes
                    // that change nothing are skipped (see update).
                    // The accuracy circle jumps to its new size: animating it redraws the map for nothing.
                    .locationComponentOptions(
                        LocationComponentOptions.builder(context).enableStaleState(false).accuracyAnimationEnabled(false).build(),
                    )
                    .build(),
            )
        }
        val compass = component.compassEngine as? SteadyCompass
            ?: component.compassEngine?.let(::SteadyCompass)?.also { component.compassEngine = it }
        compass?.minIntervalMs = if (power == Power.Normal) SteadyCompass.NORMAL_INTERVAL_MS else SteadyCompass.SAVER_INTERVAL_MS
        component.isLocationComponentEnabled = true
        component.renderMode = if (power == Power.Minimal) RenderMode.NORMAL else RenderMode.COMPASS
        this.power = power
        animationFps = 0
        setAnimationFps(component)
    }

    private var power = Power.Normal
    private var animationFps = 0

    /** Each turn of the arrow and each step of the camera is animated, and each frame redraws the whole map. */
    private fun setAnimationFps(component: LocationComponent) {
        val following = component.cameraMode != CameraMode.NONE
        val fps = when (power) {
            Power.Normal -> if (following) FOLLOW_ANIMATION_FPS else MAX_ANIMATION_FPS
            Power.Saver -> if (following) SAVER_FOLLOW_ANIMATION_FPS else SAVER_ANIMATION_FPS
            Power.Minimal -> MINIMAL_ANIMATION_FPS
        }
        if (fps != animationFps) {
            animationFps = fps
            component.setMaxAnimationFps(fps)
        }
    }

    /** Hidden under another screen: no arrow, so no compass and no redraws for it. */
    fun hide(map: MapLibreMap) {
        val component = map.locationComponent
        if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
    }

    /**
     * Moves the marker to [location]: each move is animated, and each frame of it redraws the
     * whole map. A fix that changes nothing visible (standing still: the same place within a
     * couple of metres, the same accuracy, the same course) is skipped, so a break with the
     * screen on costs no drawing at all. (MapLibre's greying of a marker without news is off:
     * the GPS indicator says when the fix is lost.)
     */
    fun update(map: MapLibreMap, location: Location) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) return
        val last = shown
        val still = maxOf(STILL_M, STILL_PX * map.projection.getMetersPerPixelAtLatitude(location.latitude).toFloat())
        if (last != null && component.lastKnownLocation != null && isSame(last, location, still)) return
        // A wild fix (rock walls, a gorge) would throw the followed view far off and back: a
        // few in a row are believed, a real jump (out of a car) shows a few seconds late.
        if (last != null && isWild(last, location) && wild < WILD_IN_A_ROW) {
            wild++
            return
        }
        wild = 0
        shown = Location(location)
        setAnimationFps(component)
        component.forceLocationUpdate(location)
    }

    /** The last fix handed to the marker. */
    private var shown: Location? = null

    /** Wild fixes skipped in a row (see [isWild]). */
    private var wild = 0

    /**
     * Too far for the time since [last] (faster than anyone walks, cycles or rides a lift), or
     * suddenly much worse than a good fix a moment ago.
     */
    private fun isWild(last: Location, next: Location): Boolean {
        val seconds = maxOf(1.0, abs(next.time - last.time) / 1000.0)
        if (last.distanceTo(next) / seconds > WILD_SPEED_MPS) return true
        return next.hasAccuracy() && next.accuracy > POOR_ACCURACY_M &&
            last.hasAccuracy() && last.accuracy <= GOOD_ACCURACY_M && seconds < RECENT_S
    }

    private fun isSame(a: Location, b: Location, stillM: Float): Boolean =
        a.distanceTo(b) < stillM &&
            abs(a.accuracy - b.accuracy) < STILL_M &&
            (!b.hasBearing() || !a.hasBearing() || angle(a.bearing, b.bearing) < STILL_TURN_DEGREES)

    private fun angle(a: Float, b: Float): Float {
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    private const val WILD_SPEED_MPS = 70.0
    private const val WILD_IN_A_ROW = 3
    private const val POOR_ACCURACY_M = 100f
    private const val GOOD_ACCURACY_M = 30f
    private const val RECENT_S = 30.0

    /** Closer than this, a new fix moves the marker by a pixel or two at hiking zooms. */
    private const val STILL_M = 2f

    /** Zoomed out, a fix that moves the marker less than this many dp is skipped too. */
    private const val STILL_PX = 2f
    private const val STILL_TURN_DEGREES = 10f

    /**
     * Keeps the camera on the user: north up, or turned to the direction of travel
     * ([FollowMode.Heading], from the GPS course). [FollowMode.Off] lets the camera go.
     */
    fun follow(map: MapLibreMap, mode: FollowMode) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) return
        if (mode == FollowMode.Off) {
            if (component.cameraMode != CameraMode.NONE) component.cameraMode = CameraMode.NONE
            setAnimationFps(component)
            return
        }
        // A separate zoomWhileTracking() is cancelled by the mode transition, so zoom in the same move.
        val zoom = FOLLOW_ZOOM.takeIf { map.cameraPosition.zoom < FOLLOW_ZOOM - 2 }
        val camera = if (mode == FollowMode.Heading) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
        component.setCameraMode(camera, TRANSITION_MS, zoom, null, null, null)
        setAnimationFps(component)
    }

    /**
     * Reports how the camera follows the user, also when that changes without being asked:
     * panning stops following ([FollowMode.Off]), turning the map keeps only the position
     * ([FollowMode.North] with the map left as turned). Returns the way to stop listening.
     */
    fun observeFollow(map: MapLibreMap, onChange: (FollowMode) -> Unit): () -> Unit {
        val component = map.locationComponent
        val listener = object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() = onChange(FollowMode.Off)

            override fun onCameraTrackingChanged(currentMode: Int) = onChange(
                when (currentMode) {
                    CameraMode.TRACKING_GPS -> FollowMode.Heading
                    CameraMode.TRACKING, CameraMode.TRACKING_GPS_NORTH -> FollowMode.North
                    else -> FollowMode.Off
                },
            )
        }
        component.addOnCameraTrackingChangedListener(listener)
        return { component.removeOnCameraTrackingChangedListener(listener) }
    }

    /**
     * The phone's compass heading (degrees from north) as it changes, while the position is
     * shown; nothing without a compass. Returns the way to stop listening.
     */
    fun observeHeading(map: MapLibreMap, onHeading: (Float) -> Unit): () -> Unit {
        val compass = map.locationComponent.takeIf { it.isLocationComponentActivated }?.compassEngine ?: return {}
        val listener = object : CompassListener {
            override fun onCompassChanged(userHeading: Float) = onHeading(userHeading)
            override fun onCompassAccuracyChange(compassStatus: Int) = Unit
        }
        compass.addCompassListener(listener)
        return { compass.removeCompassListener(listener) }
    }

    fun lastLocation(map: MapLibreMap): Location? =
        map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
}

/** How the map follows the user's position. */
enum class FollowMode { Off, North, Heading }
