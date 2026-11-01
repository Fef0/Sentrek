package app.sentrek.map

import android.graphics.Bitmap
import android.util.Log
import app.sentrek.geo.dem.Slope
import app.sentrek.geo.dem.TerrainTiles
import app.sentrek.geo.dem.Terrarium
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.rasterOpacity
import org.maplibre.android.style.layers.PropertyFactory.rasterResampling
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Steep slopes shaded as on avalanche maps (see [Slope]): yellow 30–35°, orange 35–40°, red
 * 40–45°, purple from 45°. The tiles are painted on the phone from the terrain tiles, when
 * MapLibre asks for them at [HOST] (served by [Tiles]), so they work wherever the terrain does:
 * offline in downloaded areas.
 */
object MapSlopes {
    const val HOST = "slopes.sentrek.invalid"
    private const val SOURCE = "slopes"
    private const val LAYER = "slopes"

    /** Further out a terrain pixel is over 60 m wide: steep gullies blur into gentle slopes. */
    const val MIN_ZOOM = 11

    /** As detailed as the terrain gets; closer in, MapLibre scales these up. */
    private const val MAX_ZOOM = Terrarium.ZOOM

    /** Right above the relief: under water, roads, trails and contour lines (hiking.json). */
    private const val ABOVE_LAYER = "hillshade"

    @Volatile
    private var terrain: TerrainTiles? = null

    /** The shades, from the gentlest band up. */
    private val COLORS = intArrayOf(0xFFFFE14D.toInt(), 0xFFFF9A1F.toInt(), 0xFFF2382A.toInt(), 0xFF8E24AA.toInt())

    /** For a legend: the colour of each band of [Slope.BANDS]. */
    val colors: List<Int> get() = COLORS.toList()

    /** [terrain] null removes the shading. */
    fun sync(style: Style, terrain: TerrainTiles?, night: Boolean) {
        style.getLayer(LAYER)?.let(style::removeLayer)
        style.getSource(SOURCE)?.let(style::removeSource)
        this.terrain = terrain
        if (terrain == null) return
        val tiles = TileSet("2.2.0", "https://$HOST/{z}/{x}/{y}.png").apply {
            minZoom = MIN_ZOOM.toFloat()
            maxZoom = MAX_ZOOM.toFloat()
        }
        style.addSource(RasterSource(SOURCE, tiles, Terrarium.TILE_SIZE))
        style.addLayerAbove(
            RasterLayer(LAYER, SOURCE).withProperties(
                rasterOpacity(if (night) 0.4f else 0.5f),
                rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            ).apply { minZoom = MIN_ZOOM.toFloat() },
            ABOVE_LAYER,
        )
    }

    /** The PNG for map tile [z]/[x]/[y]; null where there is no terrain for it. */
    private fun paint(z: Int, x: Int, y: Int): ByteArray? {
        val terrain = terrain ?: return null
        val size = Terrarium.TILE_SIZE
        val world = size shl z
        val centreKey = Terrarium.TileKey(x, y, z)
        val centre = terrain.tile(centreKey) ?: return null
        val loaded = hashMapOf<Terrarium.TileKey, Terrarium.Tile?>(centreKey to centre)

        // The tile's heights with one pixel of its neighbours all round, for the slope at its edge.
        val width = size + 2
        val heights = FloatArray(width * width)
        for (j in 0 until width) {
            val gy = (y * size - 1 + j).coerceIn(0, world - 1)
            for (i in 0 until width) {
                val gx = Math.floorMod(x * size - 1 + i, world)
                val key = Terrarium.TileKey(gx / size, gy / size, z)
                // A missing neighbour: repeat the tile's own edge.
                val tile = loaded.getOrPut(key) { terrain.tile(key) }
                heights[j * width + i] = tile?.get(gx % size, gy % size) ?: centre[gx - x * size, gy - y * size]
            }
        }
        val degrees = Slope.degrees(heights, width, width) { row ->
            val py = (y * size + row + 0.5) / world
            Slope.metersPerPixel(Math.toDegrees(atan(sinh(PI * (1 - 2 * py)))), z)
        }

        val pixels = IntArray(size * size) { i -> Slope.band(degrees[i]).let { if (it == 0) 0 else COLORS[it - 1] } }
        val bitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** How long MapLibre keeps a painted slope tile: a day. */
    private const val KEEP_S = 86_400

    /** Answers MapLibre's requests for slope tiles from the terrain, without the network. */
    internal object Tiles : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (request.url.host != HOST) return chain.proceed(request)
            val parts = request.url.pathSegments
            val png = runCatching {
                val (z, x, y) = listOf(parts[0], parts[1], parts[2].removeSuffix(".png")).map(String::toInt)
                paint(z, x, y)
            }.onFailure { Log.w("MapSlopes", "No slopes for ${request.url}: ${it.message}") }.getOrNull()
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (png != null) 200 else 404)
                .message(if (png != null) "OK" else "Not Found")
                // Kept a day once painted (terrain doesn't change); a missing one is asked for
                // again each time, since the terrain may be there later (online, downloaded).
                .header("Cache-Control", if (png != null) "max-age=$KEEP_S" else "no-store")
                .body((png ?: ByteArray(0)).toResponseBody("image/png".toMediaType()))
                .build()
        }
    }
}
