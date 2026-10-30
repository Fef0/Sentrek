package app.sentrek.map

import android.content.Context
import app.sentrek.core.map.R
import app.sentrek.geo.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The last place the phone had a signal, as a badge with signal bars: shown only while there
 * is none, so the map stays clear the rest of the time.
 */
object SignalLayer {
    private const val ID = "last-signal"
    private const val IMAGE = "last-signal-badge"
    private const val COLOR = 0xFF2E7D32.toInt()

    private var sentStyle: Style? = null
    private var sentAt: LatLon? = null

    /** [at] null: nothing to show. Only a change is sent: each one lays the labels out again. */
    fun sync(context: Context, style: Style, at: LatLon?) {
        if (style === sentStyle && at == sentAt) return
        sentStyle = style
        sentAt = at
        val features = FeatureCollection.fromFeatures(listOfNotNull(at?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
        val source = style.getSourceAs<GeoJsonSource>(ID)
        if (source != null) {
            source.setGeoJson(features)
            return
        }
        style.addImage(IMAGE, MapIcons.badge(context, R.drawable.map_signal, COLOR, context.resources.displayMetrics.density))
        style.addSource(GeoJsonSource(ID, features))
        PlaceLayers.addBelow(
            style,
            SymbolLayer(ID, ID).withProperties(
                iconImage(IMAGE),
                iconSize(1.3f),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
            ),
        )
    }
}
