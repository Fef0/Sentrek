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

    /** Keeps the camera on the user until they pan the map. */
    fun follow(map: MapLibreMap, onDismissed: () -> Unit) {
        val component = map.locationComponent
        if (!component.isLocationComponentActivated) return
        // A separate zoomWhileTracking() is cancelled by the mode transition, so zoom in the same move.
        val zoom = FOLLOW_ZOOM.takeIf { map.cameraPosition.zoom < FOLLOW_ZOOM - 2 }
        component.setCameraMode(CameraMode.TRACKING, TRANSITION_MS, zoom, null, null, null)
        component.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() {
                component.removeOnCameraTrackingChangedListener(this)
                onDismissed()
            }

            override fun onCameraTrackingChanged(currentMode: Int) = Unit
        })
    }

    fun lastLocation(map: MapLibreMap): Location? =
        map.locationComponent.takeIf { it.isLocationComponentActivated }?.lastKnownLocation
}
