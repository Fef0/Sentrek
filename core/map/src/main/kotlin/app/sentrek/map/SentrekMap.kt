package app.sentrek.map

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** Whether maps use night colours; set once for the whole app (see the map theme setting). */
val LocalNightMap = staticCompositionLocalOf { false }

/** Frames per second the maps may draw at most; 0 for as many as the screen shows (battery saver sets it). */
val LocalMapMaxFps = staticCompositionLocalOf { 0 }

/**
 * MapLibre map bound to the composition's lifecycle. [onMapReady] runs once the style has loaded.
 * [onTouch] sees every touch first; returning true keeps it from the map (e.g. to drag a point).
 * [night] uses dark map colours; changing it rebuilds the map, and [onMapReady] runs again.
 */
@SuppressLint("ClickableViewAccessibility") // Taps still reach the map unless onTouch takes them.
@Composable
fun SentrekMap(
    style: MapStyle,
    modifier: Modifier = Modifier,
    night: Boolean = LocalNightMap.current,
    onMapReady: (MapLibreMap, Style) -> Unit = { _, _ -> },
    onTouch: ((MotionEvent) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnTouch = rememberUpdatedState(onTouch)
    val maxFps = LocalMapMaxFps.current

    val mapView = remember(night, maxFps) {
        // Black while the style loads, not a light grey flash.
        val options = MapLibreMapOptions.createFromAttributes(context)
            .apply { if (night) foregroundLoadColor(Color.BLACK) }
        MapView(context, options).apply {
            onCreate(null)
            if (maxFps > 0) setMaximumFps(maxFps)
            setOnTouchListener { _, event -> currentOnTouch.value?.invoke(event) ?: false }
            val density = context.resources.displayMetrics.density
            getMapAsync { map ->
                // Route waymarks are drawn as the map meets them: there are thousands of kinds.
                addOnStyleImageMissingListener { id ->
                    RouteMarks.image(id, density)?.let { map.style?.addImage(id, it) }
                }
                map.uiSettings.apply {
                    // One-finger zoom (double-tap and drag): pinching with gloves is hard.
                    isQuickZoomGesturesEnabled = true
                    isCompassEnabled = true
                    isLogoEnabled = false
                    // The credit is drawn by the screens (MapCredit), without the "i" button.
                    isAttributionEnabled = false
                }
                map.setStyle(Style.Builder().fromUri(style.uri)) { loaded ->
                    if (night) NightStyle.apply(loaded)
                    MapIcons.install(context, loaded)
                    currentOnMapReady.value(map, loaded)
                }
            }
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    key(mapView) {
        AndroidView(factory = { mapView }, modifier = modifier)
    }
}
