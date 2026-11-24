package app.sentrek.home

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.dem.Panorama
import app.sentrek.geo.overlay.OverlayPoiSource
import app.sentrek.geo.overlay.PoiKind
import app.sentrek.geo.route.RoutePlace
import app.sentrek.map.GoToLayer
import app.sentrek.map.MapIcons
import app.sentrek.record.ElevationRepository
import app.sentrek.record.routePlaceLabel
import app.sentrek.ui.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt

/** A place the compass strip can point at. */
data class HudMark(val kind: PoiKind, val name: String?, val position: LatLon)

/**
 * The places around the user worth a mark on the compass strip: the peaks in sight, huts,
 * passes and water nearby. Looked up again after each few hundred metres.
 */
@HiltViewModel
class HudCompassViewModel @Inject constructor(
    private val elevation: ElevationRepository,
    private val pois: OverlayPoiSource,
) : ViewModel() {
    private val _marks = MutableStateFlow<List<HudMark>>(emptyList())
    val marks: StateFlow<List<HudMark>> = _marks.asStateFlow()

    private var from: LatLon? = null
    private var job: Job? = null

    fun near(here: LatLon) {
        val last = from
        if (last != null && Geo.distance(last, here) < REFRESH_M) return
        from = here
        job?.cancel()
        job = viewModelScope.launch { _marks.value = find(here) }
    }

    private suspend fun find(here: LatLon): List<HudMark> {
        val all = runCatching { pois.pois(around(here, PEAK_RADIUS_M)) }.getOrDefault(emptyList())
        return withContext(Dispatchers.Default) {
            val near = all.filter { poi ->
                val radius = when (poi.kind) {
                    PoiKind.AlpineHut, PoiKind.WildernessHut -> HUT_RADIUS_M
                    PoiKind.Shelter, PoiKind.Pass -> SHELTER_RADIUS_M
                    PoiKind.Spring, PoiKind.DrinkingWater -> WATER_RADIUS_M
                    else -> return@filter false
                }
                (poi.kind != PoiKind.Pass || !poi.name.isNullOrBlank()) && Geo.distance(here, poi.position) <= radius
            }.map { HudMark(it.kind, it.name?.takeIf(String::isNotBlank), it.position) }
            val named = all.filter { it.kind == PoiKind.Peak && !it.name.isNullOrBlank() }.map { it.name!! to it.position }
            // The peaks the eye can see: those behind a nearer ridge would only crowd the strip.
            // Without the terrain (offline, area not downloaded), the nearest ones.
            val panorama = Panorama(elevation)
            val ground = runCatching { panorama.height(here) }.getOrNull()
            // The ones right here are always there (the sight check leaves out the closest).
            val close = named.filter { Geo.distance(here, it.second) <= CLOSE_PEAK_M }
            val seen = if (ground != null) {
                runCatching { panorama.peaks(here.copy(ele = ground), EYE_M, named, PEAK_RADIUS_M) }.getOrDefault(emptyList())
                    .map { it.name to it.position }
            } else {
                named.filter { Geo.distance(here, it.second) <= NEAR_PEAK_RADIUS_M }
            }
            (close + seen).distinctBy { it.first }.map { HudMark(PoiKind.Peak, it.first, it.second) } + near
        }
    }

    private fun around(p: LatLon, radius: Double): BoundingBox {
        val dLat = Math.toDegrees(radius / Geo.EARTH_RADIUS_M)
        val dLon = dLat / max(cos(Math.toRadians(p.lat)), 0.01)
        return BoundingBox(p.lat - dLat, p.lon - dLon, p.lat + dLat, p.lon + dLon)
    }

    private companion object {
        const val REFRESH_M = 300.0
        const val PEAK_RADIUS_M = 20_000.0
        const val NEAR_PEAK_RADIUS_M = 6_000.0
        const val CLOSE_PEAK_M = 1_000.0
        const val HUT_RADIUS_M = 8_000.0
        const val SHELTER_RADIUS_M = 4_000.0
        const val WATER_RADIUS_M = 1_500.0
        const val EYE_M = 1.7
    }
}

/**
 * A strip across the top of the map while the user's position is on it, as in games: the directions where the
 * phone points, and the places that lie that way. The one nearest straight ahead gets its name.
 */
@Composable
fun HudCompass(
    compass: Float?,
    here: State<Location?>,
    goTo: GoTo?,
    modifier: Modifier = Modifier,
    viewModel: HudCompassViewModel = hiltViewModel(),
) {
    val marks by viewModel.marks.collectAsStateWithLifecycle()
    val fix = here.value
    // Where the phone points; without a compass, the way the user walks.
    val heading = compass ?: fix?.takeIf { it.hasBearing() && it.speed > MIN_COURSE_SPEED }?.bearing ?: return
    val position = fix?.let { LatLon(it.latitude, it.longitude) }
    // Rounded so a fix a few metres on doesn't restart the look-up.
    LaunchedEffect(position?.let { (it.lat * 1_000).roundToInt() to (it.lon * 1_000).roundToInt() }) {
        position?.let(viewModel::near)
    }

    // Each screen frame the strip moves part of the way to the latest heading, and stops once
    // there. Restarting an animation for each reading didn't work: thirty a second, each one
    // was cancelled before it had moved.
    var shown by remember { mutableFloatStateOf(heading) }
    val target by rememberUpdatedState(heading)
    LaunchedEffect(Unit) {
        while (true) {
            snapshotFlow { angleTo(target, shown) }.first { abs(it) > SETTLED_DEG }
            var last = 0L
            while (abs(angleTo(target, shown)) > SETTLED_DEG) {
                withFrameNanos { now ->
                    val ms = if (last == 0L) FRAME_MS else (now - last) / 1_000_000f
                    last = now
                    val share = 1 - exp(-ms / EASE_MS)
                    shown = (shown + angleTo(target, shown) * share + 360) % 360
                }
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val letter = TextStyle(color = colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    val degrees = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp)
    val label = TextStyle(color = colors.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    val icons = HUD_KINDS.associateWith { kind -> MapIcons.symbol(kind.icon)?.let { painterResource(it.first) to Color(it.second) } }
    val goToIcon = painterResource(app.sentrek.core.map.R.drawable.poi_star)
    val names = HUD_KINDS.associateWith { stringResource(routePlaceLabel(RoutePlace.Kind.Osm(it))) }

    data class Seen(val name: String, val icon: Painter?, val tint: Color, val azimuth: Float, val distance: Double, val rank: Int)
    // All the places around, wherever the phone points: which of them fit on the strip is
    // decided from where they lie, so turning only slides them, none comes or goes.
    val around = if (position == null) emptyList() else buildList {
        fun add(name: String, icon: Painter?, tint: Color, at: LatLon, rank: Int) =
            add(Seen(name, icon, tint, Geo.bearing(position, at).toFloat(), Geo.distance(position, at), rank))
        goTo?.let { add(it.name, goToIcon, Color(GoToLayer.COLOR), it.position, 0) }
        for (mark in marks) {
            val icon = icons[mark.kind]
            add(mark.name ?: names.getValue(mark.kind), icon?.first, icon?.second ?: colors.primary, mark.position, 1 + HUD_KINDS.indexOf(mark.kind))
        }
    }.sortedWith(compareBy({ it.rank }, { it.distance }))

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = colors.surface.copy(alpha = 0.92f),
        shadowElevation = 4.dp,
    ) {
        Canvas(Modifier.fillMaxWidth().height(HEIGHT)) {
            val pxPerDeg = size.width / FIELD_DEG
            val mid = size.width / 2
            fun x(off: Float) = mid + off * pxPerDeg
            val centre = shown
            val first = Math.floorDiv((centre - FIELD_DEG / 2).toInt(), TICK_DEG) * TICK_DEG
            val tickTop = 9.dp.toPx()
            var deg = first
            while (deg <= centre + FIELD_DEG / 2) {
                val off = deg - centre
                val d = Math.floorMod(deg, 360)
                val px = x(off)
                when {
                    d % 45 == 0 -> {
                        val text = measurer.measure(COMPASS[d / 45], letter)
                        drawText(text, topLeft = Offset(px - text.size.width / 2, tickTop), alpha = fade(off))
                    }
                    d % 15 == 0 -> {
                        val text = measurer.measure("$d", degrees)
                        drawText(text, topLeft = Offset(px - text.size.width / 2, tickTop + 2.dp.toPx()), alpha = fade(off))
                    }
                    else -> drawLine(colors.onSurfaceVariant, Offset(px, tickTop + 4.dp.toPx()), Offset(px, tickTop + 14.dp.toPx()), strokeWidth = 1.5.dp.toPx(), alpha = fade(off))
                }
                deg += TICK_DEG
            }
            // Where the phone points.
            val caret = Path().apply {
                moveTo(mid - 7.dp.toPx(), 0f)
                lineTo(mid + 7.dp.toPx(), 0f)
                lineTo(mid, 7.dp.toPx())
                close()
            }
            drawPath(caret, colors.primary)

            // The places that way. Where two would overlap, the more important (then the nearer)
            // stays. The one nearest straight ahead is ringed, with its name below.
            val iconSize = 20.dp.toPx()
            val iconTop = 32.dp.toPx()
            val gap = iconSize * 1.2f / pxPerDeg
            val kept = mutableListOf<Seen>()
            for (s in around) if (kept.none { angle(it.azimuth, s.azimuth) < gap }) kept += s
            val offs = kept.map { it to angleTo(it.azimuth, centre) }.filter { abs(it.second) <= FIELD_DEG / 2 }
            val ahead = offs.minByOrNull { abs(it.second) }?.first
            for ((s, off) in offs) {
                val px = x(off)
                val alpha = fade(off)
                val centreY = iconTop + iconSize / 2
                val radius = iconSize / 2 + 1.dp.toPx()
                drawCircle(Color.White, radius, Offset(px, centreY), alpha = alpha)
                drawCircle(s.tint, radius, Offset(px, centreY), alpha = alpha, style = Stroke(1.5.dp.toPx()))
                if (s === ahead) drawCircle(colors.primary, radius + 3.dp.toPx(), Offset(px, centreY), alpha = alpha, style = Stroke(2.5.dp.toPx()))
                s.icon?.let { icon ->
                    val inner = iconSize * 0.6f
                    translate(px - inner / 2, iconTop + (iconSize - inner) / 2) {
                        with(icon) { draw(Size(inner, inner), alpha = alpha, colorFilter = ColorFilter.tint(s.tint)) }
                    }
                }
            }
            ahead?.let { s ->
                val text = measurer.measure(
                    "${s.name} · ${Format.distance(s.distance)}",
                    label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    constraints = Constraints(maxWidth = (size.width - 16.dp.toPx()).toInt()),
                )
                val left = (x(angleTo(s.azimuth, centre)) - text.size.width / 2).coerceIn(8.dp.toPx(), size.width - 8.dp.toPx() - text.size.width)
                drawText(text, topLeft = Offset(left, iconTop + iconSize + 6.dp.toPx()))
            }
        }
    }
}

/** From [from] to [to], -180..180 degrees. */
private fun angleTo(to: Float, from: Float): Float = ((to - from) % 360 + 540) % 360 - 180

/** The angle between two directions, 0..180 degrees. */
private fun angle(a: Float, b: Float): Float = abs(angleTo(a, b))

/** Full in the middle, fading out over the last degrees at each end, so nothing is cut in half. */
private fun fade(off: Float): Float = ((FIELD_DEG / 2 - abs(off)) / FADE_DEG).coerceIn(0f, 1f)

private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
private val HUD_KINDS = listOf(PoiKind.Peak, PoiKind.AlpineHut, PoiKind.WildernessHut, PoiKind.Shelter, PoiKind.Pass, PoiKind.Spring, PoiKind.DrinkingWater)

/** Degrees across the strip. */
private const val FIELD_DEG = 90f
private const val TICK_DEG = 5
private const val FADE_DEG = 10f

/** How quickly the strip catches up with the compass: about two thirds of the way in this time. */
private const val EASE_MS = 60f
private const val FRAME_MS = 16f

/** Closer than this, the strip is where the compass points. */
private const val SETTLED_DEG = 0.05f

/** Slower than this (m/s), the GPS course is noise. */
private const val MIN_COURSE_SPEED = 0.5f
private val HEIGHT = 82.dp
