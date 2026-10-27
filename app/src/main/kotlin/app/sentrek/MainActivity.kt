package app.sentrek

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.BoundingBox
import app.sentrek.home.DefaultCamera
import app.sentrek.home.MapScreen
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
import kotlinx.coroutines.launch
import javax.inject.Inject

private enum class Screen { Map, Maps, Tracks, Places, Settings }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var tracks: TrackRepository

    /** True when the volume keys should zoom the map (glove mode, map screen). */
    private var volumeKeysZoom = false
    private val zoomKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    /** Bounds of a file just imported from another app, to show on the map. */
    private val imported = MutableStateFlow<BoundingBox?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not again after a rotation or process restore: the file was imported already.
        if (savedInstanceState == null) importFrom(intent)
        setContent {
            val gloves by settings.gloveMode.collectAsStateWithLifecycle(initialValue = false)
            SentrekTheme(gloves = gloves) {
                var screen by rememberSaveable { mutableStateOf(Screen.Map) }
                var camera by rememberSaveable { mutableStateOf(DefaultCamera) }
                // Area to show once back on the map (e.g. a track chosen in the list).
                var focus by remember { mutableStateOf<BoundingBox?>(null) }
                val toMap = { screen = Screen.Map }
                SideEffect { volumeKeysZoom = gloves && screen == Screen.Map }

                val importedBounds by imported.collectAsStateWithLifecycle()
                LaunchedEffect(importedBounds) {
                    val box = importedBounds ?: return@LaunchedEffect
                    focus = box
                    screen = Screen.Map
                    imported.value = null
                }

                if (screen != Screen.Map) BackHandler(onBack = toMap)
                when (screen) {
                    Screen.Map -> MapScreen(
                        camera = camera,
                        onCameraChange = { camera = it },
                        onOpenMaps = { screen = Screen.Maps },
                        onOpenTracks = { screen = Screen.Tracks },
                        onOpenPlaces = { screen = Screen.Places },
                        onOpenSettings = { screen = Screen.Settings },
                        focus = focus,
                        onFocusDone = { focus = null },
                        zoomKeys = zoomKeys,
                    )
                    Screen.Maps -> RegionsScreen(initialCamera = camera, onBack = toMap)
                    Screen.Tracks -> TracksScreen(
                        onBack = toMap,
                        onShowOnMap = { box -> focus = box; screen = Screen.Map },
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
