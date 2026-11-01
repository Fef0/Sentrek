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
import kotlin.math.ceil

/** Whether maps use night colours; set once for the whole app (see the map theme setting). */
val LocalNightMap = staticCompositionLocalOf { false }

/**
 * Frames per second the maps may draw at most; 0 for as many as the screen shows (battery saver
 * sets it). Negative: smart, as many as the screen shows while a finger is on the map and
 * [IDLE_FPS] otherwise (following the walk included).
 */
val LocalMapMaxFps = staticCompositionLocalOf { 0 }

/** Smart frame rate with nothing touching the map: label fades and the follow camera stay smooth enough. */
private const val IDLE_FPS = 30

/** How long the map stays at full speed after the last touch or move: a fling ends within it. */
private const val IDLE_AFTER_MS = 1_500L

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
            val smart = if (maxFps < 0) SmartFps(this) else null
            setOnTouchListener { _, event ->
                smart?.onTouch(event)
                currentOnTouch.value?.invoke(event) ?: false
            }
            val density = context.resources.displayMetrics.density
            getMapAsync { map ->
                smart?.let { map.addOnCameraIdleListener(it::slowDownLater) }
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

/** The smart frame rate of [view]: full speed from a touch until the map has been still a moment. */
private class SmartFps(private val view: MapView) {
    private var fast = false
    private val slowDown = Runnable {
        fast = false
        view.setMaximumFps(IDLE_FPS)
    }

    init {
        view.setMaximumFps(IDLE_FPS)
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.removeCallbacks(slowDown)
                if (!fast) {
                    fast = true
                    view.setMaximumFps(ceil(view.display?.refreshRate ?: DEFAULT_SCREEN_FPS).toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> slowDownLater()
        }
    }

    /** Back to [IDLE_FPS] once nothing has moved the map for [IDLE_AFTER_MS]. */
    fun slowDownLater() {
        if (!fast) return
        view.removeCallbacks(slowDown)
        view.postDelayed(slowDown, IDLE_AFTER_MS)
    }
}

private const val DEFAULT_SCREEN_FPS = 60f
