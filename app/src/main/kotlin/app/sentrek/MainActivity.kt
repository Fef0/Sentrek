package app.sentrek

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.widget.Toast
import app.sentrek.data.AppTheme
import app.sentrek.data.MapTheme
import app.sentrek.map.LocalNightMap
import app.sentrek.data.SavedCamera
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.BoundingBox
import app.sentrek.home.DefaultCamera
import app.sentrek.home.MapScreen
import app.sentrek.planner.PlannerViewModel
import app.sentrek.feature.planner.R as PlannerR
import app.sentrek.maps.RegionsScreen
import app.sentrek.record.PlacesScreen
import app.sentrek.record.TrackRepository
import app.sentrek.record.TracksScreen
import app.sentrek.record.showImportResult
import app.sentrek.settings.SettingsScreen
import app.sentrek.ui.SentrekTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.launch
import javax.inject.Inject

private enum class Screen { Map, Maps, Tracks, Places, Settings }

private const val CAMERA_SAVE_MS = 5_000L

private fun CameraPosition.toSaved() = SavedCamera(
    lat = target?.latitude ?: 0.0,
    lon = target?.longitude ?: 0.0,
    zoom = zoom,
    bearing = bearing,
)

private fun SavedCamera.toPosition(): CameraPosition = CameraPosition.Builder()
    .target(LatLng(lat, lon))
    .zoom(zoom)
    .bearing(bearing)
    .build()

/** Behind the status bar in dark themes: 40% black. */
private const val DARK_BAR_SCRIM = 0x66000000

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var tracks: TrackRepository

    /** True when the volume keys should zoom the map (glove mode, map screen). */
    private var volumeKeysZoom = false
    private val zoomKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** Bounds of a file just imported from another app, to show on the map. */
    private val imported = MutableStateFlow<BoundingBox?>(null)

    // The map view to remember. Written at most every few seconds (the camera moves with every
    // fix while following), and at once when the app goes to the background.
    private val cameraToSave = MutableStateFlow<CameraPosition?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            cameraToSave.filterNotNull().sample(CAMERA_SAVE_MS).collect { settings.setLastCamera(it.toSaved()) }
        }
        // Not again after a rotation or process restore: the file was imported already.
        if (savedInstanceState == null) importFrom(intent)
        setContent {
            val gloves by settings.gloveMode.collectAsStateWithLifecycle(initialValue = false)
            val theme by settings.theme.collectAsStateWithLifecycle(initialValue = AppTheme.Light)
            val fullScreen by settings.fullScreen.collectAsStateWithLifecycle(initialValue = true)
            val mapTheme by settings.mapTheme.collectAsStateWithLifecycle(initialValue = MapTheme.Day)
            val confirmExit by settings.confirmExit.collectAsStateWithLifecycle(initialValue = false)
            LaunchedEffect(fullScreen) {
                hideSystemBars = fullScreen
                applySystemBars()
            }
            val dark = when (theme) {
                AppTheme.System -> isSystemInDarkTheme()
                AppTheme.Light -> false
                AppTheme.Dark, AppTheme.Black -> true
            }
            DisposableEffect(dark) {
                // Status bar icons follow the theme. The map under them stays light, so in dark
                // themes a translucent band keeps the white icons readable.
                val bars = if (dark) SystemBarStyle.dark(DARK_BAR_SCRIM) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                applySystemBars()
                onDispose {}
            }
            val nightMap = when (mapTheme) {
                MapTheme.Auto -> dark
                MapTheme.Day -> false
                MapTheme.Night -> true
            }
            CompositionLocalProvider(LocalNightMap provides nightMap) {
                SentrekTheme(darkTheme = dark, black = theme == AppTheme.Black, gloves = gloves) {
                    var screen by rememberSaveable { mutableStateOf(Screen.Map) }
                    // Null until the last saved view is read: the map opens where it was left, not at a default first.
                    var camera by rememberSaveable { mutableStateOf<CameraPosition?>(null) }
                    LaunchedEffect(Unit) {
                        if (camera == null) camera = settings.lastCamera.first()?.toPosition() ?: DefaultCamera
                    }
                    // Area to show once back on the map (e.g. a track chosen in the list).
                    var focus by remember { mutableStateOf<BoundingBox?>(null) }
                    // The planner the map screen uses (same activity-wide instance), to open a route for editing.
                    val planner: PlannerViewModel = hiltViewModel()
                    val scope = rememberCoroutineScope()
                    val toMap = { screen = Screen.Map }
                    SideEffect { volumeKeysZoom = gloves && screen == Screen.Map }

                    val importedBounds by imported.collectAsStateWithLifecycle()
                    LaunchedEffect(importedBounds) {
                        val box = importedBounds ?: return@LaunchedEffect
                        focus = box
                        screen = Screen.Map
                        imported.value = null
                    }

                    val shownCamera = camera ?: return@SentrekTheme
                    ExitConfirmation(enabled = confirmExit && screen == Screen.Map)
                    if (screen != Screen.Map) BackHandler(onBack = toMap)
                    // A theme change rebuilds the screens, and with them the map in the right colours.
                    key(dark, nightMap) {
                        when (screen) {
                            Screen.Map -> MapScreen(
                                camera = shownCamera,
                                onCameraChange = {
                                    camera = it
                                    cameraToSave.value = it
                                },
                                onOpenMaps = { screen = Screen.Maps },
                                onOpenTracks = { screen = Screen.Tracks },
                                onOpenPlaces = { screen = Screen.Places },
                                onOpenSettings = { screen = Screen.Settings },
                                focus = focus,
                                onFocusDone = { focus = null },
                                zoomKeys = zoomKeys,
                            )
                            Screen.Maps -> RegionsScreen(initialCamera = shownCamera, onBack = toMap)
                            Screen.Tracks -> TracksScreen(
                                onBack = toMap,
                                onShowOnMap = { box -> focus = box; screen = Screen.Map },
                                onEditRoute = { track ->
                                    planner.editRoute(track) { opened ->
                                        if (opened) {
                                            scope.launch {
                                                focus = tracks.show(track)
                                                screen = Screen.Map
                                            }
                                        } else {
                                            Toast.makeText(this, PlannerR.string.plan_busy, Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                            )
                            Screen.Places -> PlacesScreen(
                                onBack = toMap,
                                onShowOnMap = { box -> focus = box; screen = Screen.Map },
                            )
                            Screen.Settings -> SettingsScreen(onBack = toMap)
                        }
                    }
                }
            }
        }
    }

    private var hideSystemBars = false

    /** Full screen hides both bars; a swipe from the edge shows them briefly, over the app. */
    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hideSystemBars) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and other apps can bring the bars back.
        if (hasFocus && hideSystemBars) applySystemBars()
    }

    override fun onStop() {
        super.onStop()
        cameraToSave.value?.let { camera -> lifecycleScope.launch { settings.setLastCamera(camera.toSaved()) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importFrom(intent)
    }

    /** "Open with" (VIEW) or "Share to" (SEND, SEND_MULTIPLE) GPX, KML or KMZ files from another app. */
    private fun importFrom(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val result = tracks.importAll(uris)
            showImportResult(this@MainActivity, result)
            result.bounds?.let { imported.value = it }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeysZoom) {
            val step = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> 1
                KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                else -> 0
            }
            if (step != 0) {
                zoomKeys.tryEmit(step)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
