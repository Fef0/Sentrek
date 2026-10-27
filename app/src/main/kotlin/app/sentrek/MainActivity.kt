package app.sentrek

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.BoundingBox
import app.sentrek.home.DefaultCamera
import app.sentrek.home.MapScreen
import app.sentrek.maps.RegionsScreen
import app.sentrek.record.TracksScreen
import app.sentrek.settings.SettingsScreen
import app.sentrek.ui.SentrekTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import javax.inject.Inject

private enum class Screen { Map, Maps, Tracks, Settings }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settings: SettingsRepository

    /** True when the volume keys should zoom the map (glove mode, map screen). */
    private var volumeKeysZoom = false
    private val zoomKeys = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val gloves by settings.gloveMode.collectAsStateWithLifecycle(initialValue = false)
            SentrekTheme(gloves = gloves) {
                var screen by rememberSaveable { mutableStateOf(Screen.Map) }
                var camera by rememberSaveable { mutableStateOf(DefaultCamera) }
                // Area to show once back on the map (e.g. a track chosen in the list).
                var focus by remember { mutableStateOf<BoundingBox?>(null) }
                val toMap = { screen = Screen.Map }
                SideEffect { volumeKeysZoom = gloves && screen == Screen.Map }

                if (screen != Screen.Map) BackHandler(onBack = toMap)
                when (screen) {
                    Screen.Map -> MapScreen(
                        camera = camera,
                        onCameraChange = { camera = it },
                        onOpenMaps = { screen = Screen.Maps },
                        onOpenTracks = { screen = Screen.Tracks },
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
                    Screen.Settings -> SettingsScreen(onBack = toMap)
                }
            }
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
