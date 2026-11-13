package app.sentrek.map

import app.sentrek.geo.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** A searched position on the map: a ring with a dot, so the exact spot shows. Nothing else. */
object SearchPin {
    private const val ID = "search-pin"
    private const val DOT = "search-pin-dot"
    private const val COLOR = "#d84315"

    fun sync(style: Style, at: LatLon?) {
        val features = FeatureCollection.fromFeatures(listOfNotNull(at?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
        val source = style.getSourceAs<GeoJsonSource>(ID)
        if (source != null) {
            source.setGeoJson(features)
            return
        }
        style.addSource(GeoJsonSource(ID, features))
        style.addLayer(
            CircleLayer(ID, ID).withProperties(
                circleRadius(12f),
                circleColor("#ffffff"),
                circleStrokeColor(COLOR),
                circleStrokeWidth(4f),
            ),
        )
        style.addLayer(CircleLayer(DOT, ID).withProperties(circleRadius(4f), circleColor(COLOR)))
    }
}
