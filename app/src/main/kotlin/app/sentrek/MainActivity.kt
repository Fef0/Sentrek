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
import app.sentrek.ui.SentrekTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class Screen { Map, Regions }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SentrekTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.Map) }
                var camera by rememberSaveable { mutableStateOf(DefaultCamera) }

                when (screen) {
                    Screen.Map -> MapScreen(
                        camera = camera,
                        onCameraChange = { camera = it },
                        onOpenRegions = { screen = Screen.Regions },
                    )
                    Screen.Regions -> {
                        BackHandler { screen = Screen.Map }
                        RegionsScreen(initialCamera = camera, onBack = { screen = Screen.Map })
                    }
                }
            }
        }
    }
}
