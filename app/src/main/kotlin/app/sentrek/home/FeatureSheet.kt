package app.sentrek.home

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.overlay.SacScale
import app.sentrek.geo.overlay.TrailVisibility
import app.sentrek.map.MapFeatureInfo
import app.sentrek.map.MapRoute
import app.sentrek.map.RouteMarks
import app.sentrek.record.WeatherResult
import app.sentrek.record.WeatherSection
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.SentrekIcons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.Icon
import androidx.compose.foundation.clickable
import java.util.Locale

/** Everything we know about a long-pressed map feature. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSheet(
    info: MapFeatureInfo,
    onSaveAsPlace: () -> Unit,
    onGoTo: () -> Unit,
    onDismiss: () -> Unit,
    loadWeather: suspend () -> WeatherResult,
    /** In a downloaded area; otherwise a note says what's missing, with a way to download it. */
    downloaded: Boolean = true,
    onDownload: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val context = LocalContext.current
            val kind = info.kind?.let { kindName(context, it) }
            // A route pressed off any path (on a road, say) is named after the route.
            val routeName = info.routes.firstOrNull()?.name?.takeIf { info.kind == "route" }
            Text(
                text = info.name ?: routeName ?: kind ?: stringResource(R.string.feature_unnamed),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (info.name != null && kind != null) {
                Text(kind, style = MaterialTheme.typography.titleMedium)
            }
            // What a download adds is about trails: nothing for a river, a lake or a ford.
            if (!downloaded && !info.isWater && !info.ford) NotDownloadedNote(onDownload)

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.tags["ele"]?.let { ele ->
                        // OSM heights are in metres; anything unusual is shown as written.
                        Fact(stringResource(R.string.feature_elevation), ele.toDoubleOrNull()?.let(Format::elevation) ?: "$ele m")
                    }
                    info.sacGrade?.let { grade ->
                        val name = SacScale.entries.firstOrNull { it.grade == grade }?.osm?.let { kindName(context, it) }
                        Fact(stringResource(R.string.feature_difficulty), listOfNotNull("T$grade", name).joinToString(" · "))
                    }
                    info.pisteDifficulty?.let { d ->
                        Fact(stringResource(R.string.feature_piste_difficulty), PISTE_DIFFICULTY[d]?.let { stringResource(it) } ?: humanize(d))
                    }
                    info.pisteGrooming?.let { g ->
                        Fact(stringResource(R.string.feature_piste_grooming), PISTE_GROOMING[g]?.let { stringResource(it) } ?: humanize(g))
                    }
                    info.visibility?.takeIf { it > 0 }?.let { level ->
                        TrailVisibility.entries.firstOrNull { it.level == level }?.let {
                            Fact(stringResource(R.string.feature_visibility), kindName(context, it.osm))
                        }
                    }
                    // Only what the map data says: "intermittent" when tagged so, nothing otherwise
                    // (an untagged stream isn't known to flow all year).
                    if (info.intermittent == true) {
                        Fact(stringResource(R.string.feature_water), stringResource(R.string.feature_water_intermittent))
                    }
                    // On a path's card; a pressed ford is already named so in the title.
                    if (info.ford && info.kind != "ford") Fact(stringResource(R.string.feature_crossing), stringResource(R.string.feature_ford))
                    if (info.protectedAreas.isNotEmpty()) {
                        Fact(
                            pluralStringResource(R.plurals.feature_protected_area, info.protectedAreas.size),
                            info.protectedAreas.joinToString("\n"),
                        )
                    }
                    Fact(
                        stringResource(R.string.feature_coordinates),
                        Format.coordinates(info.position.latitude, info.position.longitude),
                    )
                    // What OSM says that matters on the way (water, a fire, a bed), in words.
                    for ((key, label) in USEFUL_TAGS) {
                        val value = info.tags[key] ?: continue
                        Fact(stringResource(label), tagValue(value))
                    }
                }
            }

            // What to do with it first: the rest is for reading.
            OutlinedButton(
                onClick = onGoTo,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_go_to)) }
            OutlinedButton(
                onClick = onSaveAsPlace,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_save_place)) }

            WeatherSection(loadWeather)

            if (info.routes.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.feature_routes), style = MaterialTheme.typography.titleSmall)
                for (route in info.routes) RouteFacts(route) { uriHandler.openUri(it) }
            }

            val tags = info.tags - "ele"
            if (tags.isNotEmpty()) {
                HorizontalDivider()
                // As OpenStreetMap has it, for who wants it all: closed until asked for.
                var open by rememberSaveable(info.osmId) { mutableStateOf(false) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget)
                        .clickable { open = !open },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.feature_osm_tags), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(if (open) SentrekIcons.ExpandLess else SentrekIcons.ExpandMore, contentDescription = null)
                }
                if (open) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            for ((key, value) in tags) Fact(key, value)
                        }
                    }
                }
            }

            info.osmId?.let { id ->
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://www.openstreetmap.org/$id") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                ) { Text(stringResource(R.string.feature_open_osm)) }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_close)) }
        }
    }
}

/** A route running here: its waymark and name, what it's part of, and what OSM says about it. */
@Composable
private fun RouteFacts(route: MapRoute, onOpen: (String) -> Unit) {
    val density = LocalDensity.current.density
    val mark = remember(route.mark, density) {
        route.mark?.let { RouteMarks.image(RouteMarks.PREFIX + it, density)?.asImageBitmap() }
    }
    val facts = route.facts
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            mark?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
            Column {
                val ref = facts["ref"].takeIf { route.mark == null }
                Text(
                    listOfNotNull(ref, route.name).joinToString(" · ").ifEmpty { null } ?: facts["ref"] ?: stringResource(R.string.feature_route),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                for (whole in route.partOf) {
                    Text(stringResource(R.string.feature_part_of, whole), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                facts["stage"]?.let { Fact(stringResource(R.string.feature_route_stage), it) }
                listOfNotNull(facts["from"], facts["to"]).takeIf { it.isNotEmpty() }?.let {
                    Fact(stringResource(R.string.feature_route_from_to), it.joinToString(" → "))
                }
                facts["distance"]?.let { d ->
                    // OSM route distances are in kilometres.
                    Fact(stringResource(R.string.feature_route_distance), d.toDoubleOrNull()?.let { Format.distance(it * 1000) } ?: d)
                }
                listOfNotNull(
                    facts["ascent"]?.let { "↑ " + (it.toDoubleOrNull()?.let(Format::elevation) ?: it) },
                    facts["descent"]?.let { "↓ " + (it.toDoubleOrNull()?.let(Format::elevation) ?: it) },
                ).takeIf { it.isNotEmpty() }?.let { Fact(stringResource(R.string.feature_route_climb), it.joinToString("  ")) }
                facts["duration:forward"]?.let { Fact(stringResource(R.string.feature_route_time), it.removePrefix("0") + " h") }
                facts["cai_scale"]?.let { Fact(stringResource(R.string.feature_route_cai), it) }
                facts["cai_scale:mtb"]?.let { Fact(stringResource(R.string.feature_route_cai_mtb), it) }
                facts["mtb:scale"]?.let { Fact(stringResource(R.string.feature_route_mtb_scale), it) }
                facts["operator"]?.let { Fact(stringResource(R.string.feature_route_operator), it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            facts["website"]?.takeIf { it.startsWith("http") }?.let { site ->
                OutlinedButton(onClick = { onOpen(site) }, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.feature_route_website))
                }
            }
            OutlinedButton(
                onClick = { onOpen("https://www.openstreetmap.org/${route.osmId}") },
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_route_osm)) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

/** "demanding_mountain_hiking" → "Demanding mountain hiking". OSM values aren't translated yet. */
/** What a map feature is, in the app's language where it has a name of its own, else as OSM writes it. */
fun kindName(context: Context, osm: String): String =
    KIND_NAMES[osm]?.let(context::getString) ?: humanize(osm)

/**
 * OSM values Sentrek names in the app's language: what a pressed feature is (paths, water,
 * fords, the points on the map), SAC grades and trail visibility. Anything else as OSM has it.
 */
private val KIND_NAMES = mapOf(
    "path" to R.string.kind_path,
    "track" to R.string.kind_track,
    "route" to R.string.kind_route,
    "river" to R.string.kind_river,
    "stream" to R.string.kind_stream,
    "canal" to R.string.kind_canal,
    "ditch" to R.string.kind_ditch,
    "drain" to R.string.kind_drain,
    "lake" to R.string.kind_lake,
    "ford" to R.string.kind_ford,
    "guidepost" to R.string.kind_guidepost,
    "board" to R.string.kind_board,
    "map" to R.string.kind_map,
    "basic_hut" to R.string.kind_basic_hut,
    "weather_shelter" to R.string.kind_weather_shelter,
    "lean_to" to R.string.kind_lean_to,
    "rock_shelter" to R.string.kind_rock_shelter,
    "memorial" to R.string.kind_memorial,
    "wayside_cross" to R.string.kind_wayside_cross,
    "wayside_shrine" to R.string.kind_wayside_shrine,
    "archaeological_site" to R.string.kind_archaeological_site,
    "castle" to R.string.kind_castle,
    "ruins" to R.string.kind_ruins,
    "monument" to R.string.kind_monument,
    "cross" to R.string.kind_cross,
    "alpine_hut" to R.string.kind_alpine_hut,
    "wilderness_hut" to R.string.kind_wilderness_hut,
    "viewpoint" to R.string.kind_viewpoint,
    "camp_site" to R.string.kind_camp_site,
    "waterfall" to R.string.kind_waterfall,
    "spring" to R.string.kind_spring,
    "cave_entrance" to R.string.kind_cave_entrance,
    "peak" to R.string.kind_peak,
    "saddle" to R.string.kind_saddle,
    "volcano" to R.string.kind_volcano,
    SPOT to R.string.kind_spot,
    "city" to R.string.kind_city,
    "town" to R.string.kind_town,
    "village" to R.string.kind_village,
    "hamlet" to R.string.kind_hamlet,
    "suburb" to R.string.kind_suburb,
    "drinking_water" to R.string.kind_drinking_water,
    "water_point" to R.string.kind_water_point,
    "shelter" to R.string.kind_shelter,
    "phone" to R.string.kind_phone,
    "place_of_worship" to R.string.kind_place_of_worship,
    "chalet" to R.string.kind_chalet,
    "picnic_site" to R.string.kind_picnic_site,
    "restaurant" to R.string.kind_restaurant,
    "cafe" to R.string.kind_cafe,
    "bar" to R.string.kind_bar,
    "pub" to R.string.kind_pub,
    "biergarten" to R.string.kind_biergarten,
    "hotel" to R.string.kind_hotel,
    "hostel" to R.string.kind_hostel,
    "guest_house" to R.string.kind_guest_house,
    "motel" to R.string.kind_motel,
    "parking" to R.string.kind_parking,
    "toilets" to R.string.kind_toilets,
    "hiking" to R.string.sac_hiking,
    "mountain_hiking" to R.string.sac_mountain_hiking,
    "demanding_mountain_hiking" to R.string.sac_demanding_mountain_hiking,
    "alpine_hiking" to R.string.sac_alpine_hiking,
    "demanding_alpine_hiking" to R.string.sac_demanding_alpine_hiking,
    "difficult_alpine_hiking" to R.string.sac_difficult_alpine_hiking,
    "excellent" to R.string.visibility_excellent,
    "good" to R.string.visibility_good,
    "intermediate" to R.string.visibility_intermediate,
    "bad" to R.string.visibility_bad,
    "horrible" to R.string.visibility_horrible,
    "no" to R.string.visibility_no,
    "piste" to R.string.kind_piste,
    "piste_downhill" to R.string.kind_piste_downhill,
    "piste_nordic" to R.string.kind_piste_nordic,
    "piste_skitour" to R.string.kind_piste_skitour,
    "piste_sled" to R.string.kind_piste_sled,
    "piste_sleigh" to R.string.kind_piste_sleigh,
    "piste_hike" to R.string.kind_piste_hike,
    "piste_snow_park" to R.string.kind_piste_snow_park,
    "piste_connection" to R.string.kind_piste_connection,
    "aerialway" to R.string.kind_aerialway,
    "cable_car" to R.string.kind_cable_car,
    "gondola" to R.string.kind_gondola,
    "mixed_lift" to R.string.kind_mixed_lift,
    "chair_lift" to R.string.kind_chair_lift,
    "drag_lift" to R.string.kind_drag_lift,
    "t-bar" to R.string.kind_t_bar,
    "j-bar" to R.string.kind_j_bar,
    "platter" to R.string.kind_platter,
    "rope_tow" to R.string.kind_rope_tow,
    "magic_carpet" to R.string.kind_magic_carpet,
    "zip_line" to R.string.kind_zip_line,
    "goods" to R.string.kind_goods,
    "cycleway" to R.string.kind_cycleway,
)

/** A piste's difficulty, with the colour the winter map gives it. */
private val PISTE_DIFFICULTY = mapOf(
    "novice" to R.string.piste_novice,
    "easy" to R.string.piste_easy,
    "intermediate" to R.string.piste_intermediate,
    "advanced" to R.string.piste_advanced,
    "expert" to R.string.piste_expert,
    "freeride" to R.string.piste_freeride,
    "extreme" to R.string.piste_extreme,
)

private val PISTE_GROOMING = mapOf(
    "classic" to R.string.grooming_classic,
    "skating" to R.string.grooming_skating,
    "classic+skating" to R.string.grooming_classic_skating,
    "mogul" to R.string.grooming_mogul,
    "backcountry" to R.string.grooming_backcountry,
    "scooter" to R.string.grooming_scooter,
)

/** The kind of a long-pressed spot with nothing on it. */
const val SPOT = "spot"

/** OSM tags worth a line of their own on a feature's card, under a name in the app's language. */
private val USEFUL_TAGS = listOf(
    "drinking_water" to R.string.feature_tag_water,
    "fireplace" to R.string.feature_tag_fireplace,
    "beds" to R.string.feature_tag_beds,
    "mattress" to R.string.feature_tag_mattress,
    "toilets" to R.string.feature_tag_toilets,
    "reservation" to R.string.feature_tag_reservation,
    "fee" to R.string.feature_tag_fee,
    "opening_hours" to R.string.feature_tag_opening_hours,
    "phone" to R.string.feature_tag_phone,
    "seasonal" to R.string.feature_tag_seasonal,
)

/** "yes" and "no" in the app's language; anything else as written. */
@Composable
private fun tagValue(value: String): String = when (value) {
    "yes" -> stringResource(R.string.feature_yes)
    "no" -> stringResource(R.string.feature_no)
    else -> value.replace(';', ',').replace('_', ' ')
}

fun humanize(osm: String): String =
    osm.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** Said where the details are looked for: without the area, some of them can't be shown. */
@Composable
private fun NotDownloadedNote(onDownload: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_not_downloaded), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.area_download))
            }
        }
    }
}
