package app.sentrek.map

import app.sentrek.geo.units.ElevationUnit
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.textField

/** Heights printed by the base style (peaks), in the user's unit. The tiles carry both. */
object MapHeights {
    /** hiking.json's peak labels: name, and the height on a second line. */
    private const val PEAKS = "peak"

    fun apply(style: Style, unit: ElevationUnit) {
        val (property, suffix) = when (unit) {
            ElevationUnit.Meters -> "ele" to " m"
            ElevationUnit.Feet -> "ele_ft" to " ft"
        }
        style.getLayer(PEAKS)?.setProperties(
            textField(
                Expression.raw(
                    """["concat",["coalesce",["get","name"],""],""" +
                        """["case",["has","$property"],["concat","\n",["to-string",["get","$property"]],"$suffix"],""]]""",
                ),
            ),
        )
    }
}
