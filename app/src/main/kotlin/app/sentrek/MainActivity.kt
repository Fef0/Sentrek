package app.sentrek

import app.sentrek.search.PeaksScreen
import app.sentrek.home.GoTo
import app.sentrek.home.HomeViewModel
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import android.widget.Toast
import app.sentrek.data.AppTheme
import app.sentrek.data.ButtonSize
import app.sentrek.data.TextSize
import app.sentrek.ui.Touch
import app.sentrek.data.MapTheme
import app.sentrek.data.PowerMode
import org.maplibre.android.MapLibre
import app.sentrek.map.LocalMapMaxFps
import app.sentrek.map.LocalNightMap
import app.sentrek.data.SavedCamera
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import app.sentrek.home.DefaultCamera
import app.sentrek.home.MapScreen
import app.sentrek.planner.PlannerViewModel
import app.sentrek.feature.planner.R as PlannerR
import app.sentrek.maps.RegionsScreen
import app.sentrek.record.PlacesScreen
import app.sentrek.search.SearchScreen
import app.sentrek.emergency.EmergencyScreen
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

private enum class Screen { Map, Maps, Tracks, Places, Search, Peaks, Emergency, Settings }

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
            val textSize by settings.textSize.collectAsStateWithLifecycle(initialValue = TextSize.Default)
            val buttonSize by settings.buttonSize.collectAsStateWithLifecycle(initialValue = ButtonSize.Standard)
            val gloves by settings.gloveMode.collectAsStateWithLifecycle(initialValue = false)
            val volumeZoom by settings.volumeKeysZoom.collectAsStateWithLifecycle(initialValue = false)
            val theme by settings.theme.collectAsStateWithLifecycle(initialValue = AppTheme.System)
            val fullScreen by settings.fullScreen.collectAsStateWithLifecycle(initialValue = false)
            val mapTheme by settings.mapTheme.collectAsStateWithLifecycle(initialValue = MapTheme.Day)
            val confirmExit by settings.confirmExit.collectAsStateWithLifecycle(initialValue = true)
            val power by settings.powerMode.collectAsStateWithLifecycle(initialValue = PowerMode.Normal)
            val chosenFps by settings.mapMaxFps.collectAsStateWithLifecycle(initialValue = 0)
            val mapFps = power.mapFps(chosenFps)
            // Stamina mode: downloaded maps only, so the radio isn't woken for tiles.
            LaunchedEffect(power) {
                MapLibre.setConnected(if (power == PowerMode.Stamina) false else null)
            }
            LaunchedEffect(fullScreen) {
                hideSystemBars = fullScreen
                applySystemBars()
            }
            // Stamina mode: black screens, whatever the theme (black OLED pixels use no power).
            val dark = power.blackScreens || when (theme) {
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
            val nightMap = power.blackScreens || when (mapTheme) {
                MapTheme.Auto -> dark
                MapTheme.Day -> false
                MapTheme.Night -> true
            }
            CompositionLocalProvider(LocalNightMap provides nightMap, LocalMapMaxFps provides mapFps) {
                SentrekTheme(
                    darkTheme = dark,
                    black = power.blackScreens || theme == AppTheme.Black,
                    touch = when {
                        gloves -> Touch.Gloves
                        buttonSize == ButtonSize.Large -> Touch.Large
                        buttonSize == ButtonSize.Largest -> Touch.Largest
                        else -> Touch.Standard
                    },
                    textScale = textSize.scale,
                ) {
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
                    SideEffect { volumeKeysZoom = (gloves || volumeZoom) && screen == Screen.Map }

                    val importedBounds by imported.collectAsStateWithLifecycle()
                    LaunchedEffect(importedBounds) {
                        val box = importedBounds ?: return@LaunchedEffect
                        focus = box
                        screen = Screen.Map
                        imported.value = null
                    }

                    val shownCamera = camera ?: return@SentrekTheme
                    ExitConfirmation(enabled = confirmExit && screen == Screen.Map)
                    // The peak finder opens from Search: back goes there.
                    if (screen != Screen.Map) BackHandler(onBack = if (screen == Screen.Peaks) ({ screen = Screen.Search }) else toMap)
                    val pages = rememberSaveableStateHolder()
                    // The map's own, shared: the SOS screen hands it a place to go to.
                    val home: HomeViewModel = hiltViewModel()
                    Box(Modifier.fillMaxSize()) {
                        // A theme or power mode change rebuilds the map in the right colours and
                        // frame rate. Only the map: a screen open on top (Settings) stays where it was.
                        key(dark, nightMap, power, mapFps) {
                            // The map stays loaded under the other screens: building it again
                            // (style, icons, overlays, tiles) made every return to it slow.
                            // Hidden, it draws nothing new unless followed GPS moves it.
                            Box(if (screen == Screen.Map) Modifier else Modifier.clearAndSetSemantics {}) {
                                MapScreen(
                                    camera = shownCamera,
                                    onCameraChange = {
                                        camera = it
                                        cameraToSave.value = it
                                    },
                                    onOpenMaps = { screen = Screen.Maps },
                                    onOpenTracks = { screen = Screen.Tracks },
                                    onOpenPlaces = { screen = Screen.Places },
                                    onOpenSearch = { screen = Screen.Search },
                                    onOpenEmergency = { screen = Screen.Emergency },
                                    onOpenSettings = { screen = Screen.Settings },
                                    focus = focus,
                                    onFocusDone = { focus = null },
                                    zoomKeys = zoomKeys,
                                    visible = screen == Screen.Map,
                                )
                            }
                        }
                        // Opaque and touch-tight: nothing reaches the map below.
                        if (screen != Screen.Map) Surface(Modifier.fillMaxSize()) {
                            // Each screen keeps where it was scrolled to, and what it had
                            // open, for when the user comes back to it.
                            pages.SaveableStateProvider(screen.name) {
                            when (screen) {
                                Screen.Map -> Unit
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
                                                val why = planner.blockedBy.value?.message ?: PlannerR.string.plan_busy
                                                Toast.makeText(this@MainActivity, why, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                )
                                Screen.Places -> PlacesScreen(
                                    onBack = toMap,
                                    onShowOnMap = { box -> focus = box; screen = Screen.Map },
                                )
                                Screen.Search -> SearchScreen(
                                    near = shownCamera.target?.let { LatLon(it.latitude, it.longitude) },
                                    onBack = toMap,
                                    onShowOnMap = { box -> focus = box; screen = Screen.Map },
                                    onPeaks = { screen = Screen.Peaks },
                                )
                                Screen.Peaks -> PeaksScreen(onBack = { screen = Screen.Search })
                                Screen.Emergency -> EmergencyScreen(
                                    onBack = toMap,
                                    onGoTo = { name, position ->
                                        home.goTo(GoTo(name, position))
                                        screen = Screen.Map
                                    },
                                )
                                Screen.Settings -> SettingsScreen(onBack = toMap)
                            }
                            }
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
        if (!hideSystemBars) return
        // Other apps can bring the bars back; so can a dialog or sheet, which has a window of its own.
        if (hasFocus) applySystemBars() else window.decorView.post(::hideBarsInDialogs)
    }

    /**
     * Hides the bars in the app's other windows: dialogs and sheets would show them again, since
     * each window has its own. Before Android 10 the app can't list its windows, so they show there.
     */
    private fun hideBarsInDialogs() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        for (view in WindowInspector.getGlobalWindowViews()) {
            if (view === window.decorView || !view.isAttachedToWindow) continue
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.windowInsetsController?.run {
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                }
            } else {
                @Suppress("DEPRECATION")
                view.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        }
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
