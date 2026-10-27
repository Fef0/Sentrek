package app.sentrek.map

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * MapLibre map bound to the composition's lifecycle. [onMapReady] runs once the style has loaded.
 * [onTouch] sees every touch first; returning true keeps it from the map (e.g. to drag a point).
 */
@SuppressLint("ClickableViewAccessibility") // Taps still reach the map unless onTouch takes them.
@Composable
fun SentrekMap(
    style: MapStyle,
    modifier: Modifier = Modifier,
    onMapReady: (MapLibreMap, Style) -> Unit = { _, _ -> },
    onTouch: ((MotionEvent) -> Boolean)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnMapReady = rememberUpdatedState(onMapReady)
    val currentOnTouch = rememberUpdatedState(onTouch)

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            setOnTouchListener { _, event -> currentOnTouch.value?.invoke(event) ?: false }
            getMapAsync { map ->
                map.uiSettings.apply {
                    // One-finger zoom (double-tap and drag): pinching with gloves is hard.
                    isQuickZoomGesturesEnabled = true
                    isCompassEnabled = true
                    isLogoEnabled = false
                }
                map.setStyle(Style.Builder().fromUri(style.uri)) { loaded ->
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

    AndroidView(factory = { mapView }, modifier = modifier)
}
