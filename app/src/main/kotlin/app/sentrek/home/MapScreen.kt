package app.sentrek.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.R
import app.sentrek.map.MapOverlays
import app.sentrek.map.MapStyle
import app.sentrek.map.SentrekMap
import app.sentrek.ui.BarItem
import app.sentrek.ui.BottomBar
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

// Western Alps until "remember last position" lands with DataStore.
val DefaultCamera: CameraPosition = CameraPosition.Builder()
    .target(LatLng(45.9763, 7.6586))
    .zoom(11.0)
    .build()

@Composable
fun MapScreen(
    camera: CameraPosition,
    onCameraChange: (CameraPosition) -> Unit,
    onOpenMaps: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val overlays by viewModel.overlays.collectAsStateWithLifecycle()
    val showDifficulty by viewModel.showDifficulty.collectAsStateWithLifecycle()
    val rasters by viewModel.rasters.collectAsStateWithLifecycle()
    val currentOnCameraChange by rememberUpdatedState(onCameraChange)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            SentrekMap(
                style = MapStyle.Hiking,
                modifier = Modifier.fillMaxSize(),
                onMapReady = { m, s ->
                    m.cameraPosition = camera
                    map = m
                    style = s
                },
            )

            // Zoom stays on the right edge, in the lower half: reachable with the thumb.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MapButton(
                    icon = SentrekIcons.Plus,
                    contentDescription = stringResource(R.string.zoom_in),
                    onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) },
                )
                MapButton(
                    icon = SentrekIcons.Minus,
                    contentDescription = stringResource(R.string.zoom_out),
                    onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) },
                )
            }
        }

        BottomBar(
            items = listOf(
                BarItem(SentrekIcons.Layers, stringResource(R.string.bar_maps), onOpenMaps),
                BarItem(Icons.Filled.Settings, stringResource(R.string.bar_settings), onOpenSettings),
            ),
        )
    }

    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val onIdle = MapLibreMap.OnCameraIdleListener { currentOnCameraChange(m.cameraPosition) }
        m.addOnCameraIdleListener(onIdle)
        onDispose { m.removeOnCameraIdleListener(onIdle) }
    }

    LaunchedEffect(style, rasters, overlays, showDifficulty) {
        style?.let { MapOverlays.sync(it, rasters, overlays, showDifficulty) }
    }
}
