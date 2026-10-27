package app.sentrek

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.sentrek.home.DefaultCamera
import app.sentrek.home.MapScreen
import app.sentrek.maps.RegionsScreen
import app.sentrek.settings.SettingsScreen
import app.sentrek.ui.SentrekTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class Screen { Map, Maps, Settings }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SentrekTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Map) }
                var camera by rememberSaveable { mutableStateOf(DefaultCamera) }
                val toMap = { screen = Screen.Map }

                if (screen != Screen.Map) BackHandler(onBack = toMap)
                when (screen) {
                    Screen.Map -> MapScreen(
                        camera = camera,
                        onCameraChange = { camera = it },
                        onOpenMaps = { screen = Screen.Maps },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    Screen.Maps -> RegionsScreen(initialCamera = camera, onBack = toMap)
                    Screen.Settings -> SettingsScreen(onBack = toMap)
                }
            }
        }
    }
}
