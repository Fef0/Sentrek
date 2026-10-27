package app.sentrek.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.map.MapStyle
import app.sentrek.map.SentrekMap
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

// Western Alps until "remember last position" lands with DataStore.
private val DefaultCamera = CameraPosition.Builder()
    .target(LatLng(45.9763, 7.6586))
    .zoom(11.0)
    .build()

@Composable
fun MapScreen() {
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    Box(Modifier.fillMaxSize()) {
        SentrekMap(
            style = MapStyle.Hiking,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { m, _ ->
                if (map == null) m.cameraPosition = DefaultCamera
                map = m
            },
        )

        // Controls on the right edge, lower half: reachable with the thumb.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .safeDrawingPadding()
                .padding(end = 12.dp, top = 120.dp),
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
}
