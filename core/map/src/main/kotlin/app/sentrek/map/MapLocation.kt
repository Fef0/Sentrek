package app.sentrek.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

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

    @SuppressLint("MissingPermission") // No engine: MapLibre never touches the GPS itself.
    fun activate(context: Context, map: MapLibreMap, style: Style) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) {
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(false)
                    .build(),
            )
        }
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.COMPASS
        // The compass turns the arrow on every sensor reading, which redraws the whole map:
        // capped, a phone held still in the hand doesn't burn through the battery.
        component.setMaxAnimationFps(MAX_ANIMATION_FPS)
    }

    fun update(map: MapLibreMap, location: Location) {
        val component = map.locationComponent
        if (component.isLocationComponentActivated) component.forceLocationUpdate(location)
    }

    /**
     * Keeps the camera on the user: north up, or turned to the direction of travel
     * ([FollowMode.Heading], from the GPS course). [FollowMode.Off] lets the camera go.
     */
    fun follow(map: MapLibreMap, mode: FollowMode) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) return
        if (mode == FollowMode.Off) {
            if (component.cameraMode != CameraMode.NONE) component.cameraMode = CameraMode.NONE
            return
        }
        // A separate zoomWhileTracking() is cancelled by the mode transition, so zoom in the same move.
        val zoom = FOLLOW_ZOOM.takeIf { map.cameraPosition.zoom < FOLLOW_ZOOM - 2 }
        val camera = if (mode == FollowMode.Heading) CameraMode.TRACKING_GPS else CameraMode.TRACKING_GPS_NORTH
        component.setCameraMode(camera, TRANSITION_MS, zoom, null, null, null)
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

    fun lastLocation(map: MapLibreMap): Location? =
        map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
}

/** How the map follows the user's position. */
enum class FollowMode { Off, North, Heading }
