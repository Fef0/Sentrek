package app.sentrek.search

import androidx.lifecycle.compose.LifecycleResumeEffect
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.sentrek.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.dem.Panorama
import app.sentrek.geo.overlay.OverlayPoiSource
import app.sentrek.geo.overlay.PoiKind
import app.sentrek.record.ElevationRepository
import app.sentrek.record.LocationRepository
import app.sentrek.ui.Format
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max

/** What the peak finder has to show. */
sealed interface PeaksState {
    data object Working : PeaksState

    /** No position: no permission, or the GPS found none in time. */
    data object NoPosition : PeaksState

    /** No heights or no named peaks around: an area not downloaded, and offline. */
    data object NoData : PeaksState

    data class Ready(val skyline: Panorama.Skyline, val peaks: List<Panorama.Peak>) : PeaksState
}

@HiltViewModel
class PeaksViewModel @Inject constructor(
    private val location: LocationRepository,
    private val elevation: ElevationRepository,
    private val pois: OverlayPoiSource,
) : ViewModel() {
    private val _state = MutableStateFlow<PeaksState>(PeaksState.Working)
    val state: StateFlow<PeaksState> = _state.asStateFlow()

    private var job: Job? = null

    /** Each time the page opens: the user may be somewhere else now. */
    fun start() {
        job?.cancel()
        _state.value = PeaksState.Working
        job = viewModelScope.launch { work() }
    }

    private suspend fun work() {
        val fix = if (location.hasPermission()) withTimeoutOrNull(FIX_TIMEOUT_MS) { location.locations(1_000).first() } else null
        if (fix == null) {
            _state.value = PeaksState.NoPosition
            return
        }
        val here = LatLon(fix.latitude, fix.longitude)
        val box = around(here, RADIUS_M)
        val named = pois.pois(box).filter { it.kind == PoiKind.Peak && !it.name.isNullOrBlank() }
            .map { it.name!! to it.position }
        _state.value = withContext(Dispatchers.Default) {
            // The ground's own height under the user: the GPS altitude is often 20 m off.
            val panorama = Panorama(elevation)
            val ground = panorama.height(here) ?: return@withContext PeaksState.NoData
            val from = here.copy(ele = ground)
            val peaks = panorama.peaks(from, EYE_M, named, RADIUS_M)
            if (peaks.isEmpty()) PeaksState.NoData else PeaksState.Ready(panorama.skyline(from, EYE_M, RADIUS_M), peaks)
        }
    }

    private fun around(p: LatLon, radius: Double): BoundingBox {
        val dLat = Math.toDegrees(radius / Geo.EARTH_RADIUS_M)
        val dLon = dLat / max(cos(Math.toRadians(p.lat)), 0.01)
        return BoundingBox(p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon)
    }

    private companion object {
        const val RADIUS_M = 30_000.0
        const val EYE_M = 1.7
        const val FIX_TIMEOUT_MS = 30_000L
    }
}

/**
 * "What's that peak?": the skyline ahead, drawn from the terrain, with the names of the peaks
 * on it. Hold the phone up and turn: it follows the compass. A finger drags it round too,
 * for looking without turning, until "Follow the compass".
 */
@Composable
fun PeaksScreen(onBack: () -> Unit, viewModel: PeaksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.start() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.safeDrawingPadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MapButton(SentrekIcons.Back, stringResource(R.string.search_back), onBack, Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.peaks_title), style = MaterialTheme.typography.headlineMedium)
            }
            when (val s = state) {
                PeaksState.Working -> Message(stringResource(R.string.peaks_working))
                PeaksState.NoPosition -> Message(stringResource(R.string.peaks_no_position))
                PeaksState.NoData -> Message(stringResource(R.string.peaks_no_data))
                is PeaksState.Ready -> Ready(s)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Ready(state: PeaksState.Ready) {
    val heading = rememberHeading()
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // Turning the phone moves the view; a drag adds to it until the next turn.
    val centre = (((heading ?: 0f) + drag) % 360 + 360) % 360
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val label = TextStyle(color = colors.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val small = TextStyle(color = colors.onBackground, fontSize = 13.sp)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(300.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                ) { _, dx -> drag -= dx / size.width * FIELD_DEG }
            },
    ) {
        val pxPerDeg = size.width / FIELD_DEG
        val inView = (0..size.width.toInt() step 4).map { x -> state.skyline.at((centre - FIELD_DEG / 2 + x / pxPerDeg).toDouble()).toDouble() }
        // The view's angles fill the lower part; peaks' names above.
        val low = inView.min()
        val high = max(inView.max(), low + 2)
        val top = size.height * 0.45f
        val bottom = size.height
        val yPerDeg = (bottom - top) / (high - low).toFloat()
        fun y(angle: Double) = (bottom - (angle - low) * yPerDeg).toFloat()

        val ground = Path().apply {
            moveTo(0f, bottom)
            inView.forEachIndexed { i, a -> lineTo(i * 4f, y(a)) }
            lineTo(size.width, bottom)
            close()
        }
        drawPath(ground, colors.surfaceVariant)
        drawPath(ground, colors.onSurfaceVariant, style = Stroke(width = 2.dp.toPx()))

        // Names on rows of their own where they would overlap; the highest get theirs first:
        // the Matterhorn before the shoulder beside it.
        val rows = List(LABEL_ROWS) { mutableListOf<ClosedFloatingPointRange<Float>>() }
        for (peak in state.peaks.sortedByDescending { it.height }) {
            val off = ((peak.azimuth - centre + 540) % 360) - 180
            if (abs(off) > FIELD_DEG / 2) continue
            val x = (size.width / 2 + off * pxPerDeg).toFloat()
            val name = measurer.measure(peak.name, label)
            val detail = measurer.measure("${Format.elevation(peak.height)} · ${Format.distance(peak.distance)}", small)
            val half = max(name.size.width, detail.size.width) / 2f
            // Names near an edge move in, the line still points at the summit.
            val labelX = x.coerceIn(half, size.width - half)
            val span = (labelX - half - 8.dp.toPx())..(labelX + half + 8.dp.toPx())
            val row = rows.indexOfFirst { taken -> taken.none { it.start < span.endInclusive && span.start < it.endInclusive } }
                .takeIf { it >= 0 } ?: continue
            rows[row] += span
            val labelY = row * 44.dp.toPx()
            // On the skyline, not above it: the map's summit heights are a little above the
            // smoothed terrain the skyline is drawn from.
            val tip = y(minOf(peak.elevation, state.skyline.at(peak.azimuth).toDouble()))
            drawLine(colors.primary, Offset(x, labelY + 38.dp.toPx()), Offset(x, tip), strokeWidth = 1.5.dp.toPx())
            drawCircle(colors.primary, 4.dp.toPx(), Offset(x, tip))
            drawText(name, topLeft = Offset(labelX - name.size.width / 2, labelY))
            drawText(detail, topLeft = Offset(labelX - detail.size.width / 2, labelY + name.size.height))
        }
        // Where the phone points.
        drawLine(colors.error, Offset(size.width / 2, bottom - 16.dp.toPx()), Offset(size.width / 2, bottom), strokeWidth = 3.dp.toPx())
    }
    Text(
        stringResource(R.string.peaks_direction, Format.bearing(centre.toDouble())) +
            if (heading == null) " · " + stringResource(R.string.peaks_no_compass) else "",
        style = MaterialTheme.typography.titleMedium,
    )
    if (drag != 0f && !dragging) {
        OutlinedButton(onClick = { drag = 0f }, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
            Text(stringResource(R.string.peaks_follow_compass))
        }
    }
    // The ones in view, nearest the middle first: a list reads better than a crowded skyline.
    val near = state.peaks
        .map { it to abs(((it.azimuth - centre + 540) % 360) - 180) }
        .filter { it.second <= FIELD_DEG / 2 }
        .sortedBy { it.second }
        .take(LIST_MAX)
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((peak, _) in near) {
            Column {
                Text(peak.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    listOf(Format.elevation(peak.height), Format.distance(peak.distance), Format.bearing(peak.azimuth)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Text(stringResource(R.string.peaks_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
    }
}

/**
 * Where the back of the phone points, held up in front of the eyes, in compass degrees; null
 * without a compass. Smoothed: the raw sensor shakes with the hand.
 */
@Composable
private fun rememberHeading(): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    // Only while the app is in front: the screen off or another app, the sensor rests.
    LifecycleResumeEffect(Unit) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return@LifecycleResumeEffect onPauseOrDispose { }
        val rotation = FloatArray(9)
        val upright = FloatArray(9)
        val orientation = FloatArray(3)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, upright)
                SensorManager.getOrientation(upright, orientation)
                val raw = ((Math.toDegrees(orientation[0].toDouble()) + 360) % 360).toFloat()
                val last = heading
                heading = if (last == null) raw else {
                    val diff = ((raw - last + 540) % 360) - 180
                    // A small turn is shake; a bigger one is followed briskly.
                    if (abs(diff) < 0.4f) last else (last + diff * SMOOTHING + 360) % 360
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onPauseOrDispose { sensors.unregisterListener(listener) }
    }
    return heading
}

/** Degrees across the screen: about what the eye takes in without turning. */
private const val FIELD_DEG = 60f
private const val SMOOTHING = 0.15f
private const val LIST_MAX = 6
private const val LABEL_ROWS = 3
