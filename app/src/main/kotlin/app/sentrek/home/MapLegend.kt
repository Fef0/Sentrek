package app.sentrek.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sentrek.R
import app.sentrek.geo.dem.Contours
import app.sentrek.geo.dem.Slope
import app.sentrek.geo.overlay.MapKind
import app.sentrek.geo.units.ElevationUnit
import app.sentrek.map.MapColors
import app.sentrek.map.MapIcons
import app.sentrek.map.MapOverlays
import app.sentrek.map.MapSlopes

/** How a legend row draws its sample: as on the map, at a middle zoom. */
private sealed interface Sample {
    /** A line [widthDp] wide; [dash] in line widths (on, off), as MapLibre's dasharray. */
    data class Line(val color: String, val widthDp: Float, val dash: Pair<Float, Float>? = null, val alpha: Float = 1f) : Sample

    data class Ferrata(val night: Boolean) : Sample

    data class Fill(val color: Int) : Sample

    data class Icon(val name: String) : Sample
}

/**
 * What the colours, lines and icons on the map mean, for the map shown now ([kind], [night]).
 * The samples are drawn from the same colours as the map layers ([MapColors]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLegendSheet(
    kind: MapKind,
    night: Boolean,
    showDifficulty: Boolean,
    zoom: Double,
    elevationUnit: ElevationUnit,
    onDismiss: () -> Unit,
) {
    val background = Color(
        MapColors.argb(
            when {
                night -> MapColors.BACKGROUND_NIGHT
                kind == MapKind.Winter -> MapColors.BACKGROUND_WINTER
                else -> MapColors.BACKGROUND
            },
        ),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.legend_title), style = MaterialTheme.typography.titleLarge)
            when (kind) {
                MapKind.Hiking -> HikingLines(night, showDifficulty, background)
                MapKind.Winter -> WinterLines(night, background)
                MapKind.Cycling -> CyclingLines(night, background)
            }
            Section(stringResource(R.string.slope_legend))
            for ((band, color) in MapSlopes.colors.withIndex()) {
                val from = Slope.BANDS[band].toInt()
                val to = Slope.BANDS.getOrNull(band + 1)?.toInt()
                val label = if (to != null) stringResource(R.string.legend_slope_range, from, to) else stringResource(R.string.legend_slope_over, from)
                Item(Sample.Fill(color), label, background)
            }
            Section(stringResource(R.string.settings_contours))
            val spacing = Contours.spacing(zoom.toInt().coerceIn(12, 15), elevationUnit)
            val unit = if (elevationUnit == ElevationUnit.Feet) "ft" else "m"
            Text(
                stringResource(R.string.legend_contours, "${spacing.interval} $unit", "${spacing.index} $unit"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Section(stringResource(R.string.legend_points))
            for ((name, label) in POINTS) Item(Sample.Icon(name), stringResource(label), background)
        }
    }
}

@Composable
private fun HikingLines(night: Boolean, showDifficulty: Boolean, background: Color) {
    Section(stringResource(R.string.legend_trails))
    if (showDifficulty) {
        Item(Sample.Line(MapColors.T1, 3f, 3f to 1.5f), stringResource(R.string.legend_t1), background)
        Item(Sample.Line(MapColors.T2_T3, 3f, 3f to 1.5f), stringResource(R.string.legend_t2_t3), background)
        Item(Sample.Line(MapColors.T4_T6, 3f, 3f to 1.5f), stringResource(R.string.legend_t4_t6), background)
        Item(Sample.Line(MapColors.T2_T3, 3f, 1f to 2.5f), stringResource(R.string.legend_faint), background)
    }
    Item(Sample.Line(if (night) MapColors.PATH_NIGHT else MapColors.PATH, 2.5f, 3f to 1.5f), stringResource(R.string.legend_path), background)
    Item(Sample.Line(if (night) MapColors.TRACK_NIGHT else MapColors.TRACK, 2.5f, 4f to 2f), stringResource(R.string.kind_track), background)
    Item(Sample.Ferrata(night), stringResource(R.string.legend_ferrata, MapOverlays.HUSLER_GRADES[1], MapOverlays.HUSLER_GRADES.last()), background)
    Section(stringResource(R.string.legend_routes))
    val alpha = if (night) MapColors.ROUTE_OPACITY_NIGHT else MapColors.ROUTE_OPACITY
    Item(Sample.Line(if (night) MapColors.ROUTE_NATIONAL_NIGHT else MapColors.ROUTE_NATIONAL, 8f, alpha = alpha), stringResource(R.string.legend_route_national), background)
    Item(Sample.Line(if (night) MapColors.ROUTE_REGIONAL_NIGHT else MapColors.ROUTE_REGIONAL, 8f, alpha = alpha), stringResource(R.string.legend_route_regional), background)
    Item(Sample.Line(if (night) MapColors.ROUTE_LOCAL_NIGHT else MapColors.ROUTE_LOCAL, 8f, alpha = alpha), stringResource(R.string.legend_route_local), background)
}

@Composable
private fun WinterLines(night: Boolean, background: Color) {
    Section(stringResource(R.string.kind_piste_downhill))
    Item(Sample.Line(MapColors.PISTE_NOVICE, 3.2f), stringResource(R.string.piste_novice), background)
    Item(Sample.Line(MapColors.PISTE_EASY, 3.2f), stringResource(R.string.piste_easy), background)
    Item(Sample.Line(MapColors.PISTE_INTERMEDIATE, 3.2f), stringResource(R.string.piste_intermediate), background)
    Item(Sample.Line(MapColors.pisteAdvanced(night), 3.2f), stringResource(R.string.piste_advanced), background)
    Item(Sample.Line(MapColors.PISTE_FREERIDE, 3.2f), stringResource(R.string.piste_freeride), background)
    Section(stringResource(R.string.legend_other_runs))
    Item(Sample.Line(MapColors.PISTE_NORDIC, 2.2f, alpha = 0.85f), stringResource(R.string.kind_piste_nordic), background)
    Item(Sample.Line(MapColors.PISTE_SKITOUR, 2.2f, alpha = 0.85f), stringResource(R.string.kind_piste_skitour), background)
    Item(Sample.Line(MapColors.PISTE_SLED, 2.2f, alpha = 0.85f), stringResource(R.string.kind_piste_sled), background)
    Item(Sample.Line(MapColors.PISTE_HIKE, 2.2f, alpha = 0.85f), stringResource(R.string.kind_piste_hike), background)
}

@Composable
private fun CyclingLines(night: Boolean, background: Color) {
    Section(stringResource(R.string.legend_cycle_routes))
    val alpha = MapColors.CYCLE_OPACITY
    Item(Sample.Line(MapColors.CYCLE_NATIONAL, 7f, alpha = alpha), stringResource(R.string.legend_route_national), background)
    Item(Sample.Line(MapColors.CYCLE_REGIONAL, 7f, alpha = alpha), stringResource(R.string.legend_route_regional), background)
    Item(Sample.Line(MapColors.CYCLE_LOCAL, 7f, alpha = alpha), stringResource(R.string.legend_route_local), background)
    Item(Sample.Line(MapColors.CYCLE_MTB, 7f, alpha = alpha), stringResource(R.string.legend_mtb), background)
    Item(Sample.Line(MapColors.CYCLEWAY, 2f), stringResource(R.string.kind_cycleway), background)
    Item(Sample.Line(if (night) MapColors.PATH_NIGHT else MapColors.PATH, 2.5f, 3f to 1.5f), stringResource(R.string.legend_path), background)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Item(sample: Sample, label: String, background: Color) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 32.dp)
                .background(background, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            SampleDrawing(sample)
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SampleDrawing(sample: Sample) {
    when (sample) {
        is Sample.Line -> Canvas(Modifier.size(width = 44.dp, height = 20.dp)) {
            val width = sample.widthDp * density
            val y = size.height / 2
            val effect = sample.dash?.let { (on, off) -> PathEffect.dashPathEffect(floatArrayOf(on * width, off * width)) }
            drawLine(
                Color(MapColors.argb(sample.color)).copy(alpha = sample.alpha),
                Offset(0f, y), Offset(size.width, y),
                strokeWidth = width,
                cap = if (sample.dash == null && sample.alpha < 1f) StrokeCap.Round else StrokeCap.Butt,
                pathEffect = effect,
            )
        }
        is Sample.Ferrata -> Box(contentAlignment = Alignment.Center) {
            val color = Color(MapColors.argb(MapColors.ferrata(sample.night)))
            Canvas(Modifier.size(width = 44.dp, height = 20.dp)) {
                val line = 2f * density
                val y = size.height / 2
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
                // Ticks as on the map: 0.22 of a wide line's width long, every 2.5 widths.
                val wide = 8f * density
                val ticks = PathEffect.dashPathEffect(floatArrayOf(0.22f * wide, 2.5f * wide))
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = wide, pathEffect = ticks)
            }
            Surface(shape = RoundedCornerShape(3.dp), color = color) {
                Text(
                    MapOverlays.HUSLER_GRADES[2],
                    color = if (sample.night) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 3.dp),
                )
            }
        }
        is Sample.Fill -> Box(Modifier.size(width = 40.dp, height = 18.dp).background(Color(sample.color), RoundedCornerShape(3.dp)))
        is Sample.Icon -> {
            val (symbol, color) = MapIcons.symbol(sample.name) ?: return
            Surface(
                modifier = Modifier.size(26.dp),
                shape = CircleShape,
                color = Color.White,
                border = BorderStroke(1.5.dp, Color(color)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(symbol), contentDescription = null, tint = Color(color), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** The map's point icons, one of each kind of badge that matters on a hike. */
private val POINTS = listOf(
    "alpine_hut" to R.string.kind_alpine_hut,
    "wilderness_hut" to R.string.kind_wilderness_hut,
    "shelter" to R.string.kind_shelter,
    "camping" to R.string.kind_camp_site,
    "spring" to R.string.kind_spring,
    "drinking_water" to R.string.kind_drinking_water,
    "waterfall" to R.string.kind_waterfall,
    "ford" to R.string.kind_ford,
    "peak" to R.string.kind_peak,
    "saddle" to R.string.kind_saddle,
    "viewpoint" to R.string.kind_viewpoint,
    "cave" to R.string.kind_cave_entrance,
    "guidepost" to R.string.kind_guidepost,
    "board" to R.string.kind_board,
    "emergency_phone" to R.string.kind_phone,
    "cross" to R.string.kind_cross,
    "shrine" to R.string.kind_wayside_shrine,
    "memorial" to R.string.kind_memorial,
    "castle" to R.string.kind_castle,
    "place_of_worship" to R.string.kind_place_of_worship,
    "picnic" to R.string.kind_picnic_site,
    "restaurant" to R.string.kind_restaurant,
    "parking" to R.string.kind_parking,
    "toilets" to R.string.kind_toilets,
)
