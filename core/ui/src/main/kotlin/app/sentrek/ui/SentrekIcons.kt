package app.sentrek.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Icons missing from material-icons-core; avoids pulling in the huge extended set. */
object SentrekIcons {
    val Plus: ImageVector get() = Icons.Filled.Add

    val Minus: ImageVector by lazy { icon("Sentrek.Minus", "M19,13H5v-2h14v2z") }

    val Download: ImageVector by lazy { icon("Sentrek.Download", "M5,20h14v-2H5V20zM19,9h-4V3H9v6H5l7,7L19,9z") }

    val Layers: ImageVector by lazy {
        icon("Sentrek.Layers", "M11.99,18.54l-7.37,-5.73L3,14.07l9,7 9,-7 -1.63,-1.27 -7.38,5.74zM12,16l7.36,-5.73L21,9l-9,-7 -9,7 1.63,1.27L12,16z")
    }

    val Back: ImageVector by lazy { icon("Sentrek.Back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }

    val Record: ImageVector by lazy { icon("Sentrek.Record", "M12,12m-8,0a8,8 0,1 1,16 0a8,8 0,1 1,-16 0") }

    val Stop: ImageVector by lazy { icon("Sentrek.Stop", "M6,6h12v12H6z") }

    val Pause: ImageVector by lazy { icon("Sentrek.Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z") }

    val MyLocation: ImageVector by lazy {
        icon(
            "Sentrek.MyLocation",
            "M12,8c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4 -1.79,-4 -4,-4zM20.94,11c-0.46,-4.17 -3.77,-7.48 -7.94,-7.94L13,1h-2v2.06C6.83,3.52 3.52,6.83 3.06,11L1,11v2h2.06c0.46,4.17 3.77,7.48 7.94,7.94L11,23h2v-2.06c4.17,-0.46 7.48,-3.77 7.94,-7.94L23,13v-2h-2.06zM12,19c-3.87,0 -7,-3.13 -7,-7s3.13,-7 7,-7 7,3.13 7,7 -3.13,7 -7,7z",
        )
    }

    /** A winding route between two points. */
    val Tracks: ImageVector by lazy {
        icon(
            "Sentrek.Tracks",
            "M19,15.18V7c0,-2.21 -1.79,-4 -4,-4s-4,1.79 -4,4v10c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2V8.82C8.16,8.4 9,7.3 9,6c0,-1.66 -1.34,-3 -3,-3S3,4.34 3,6c0,1.3 0.84,2.4 2,2.82V17c0,2.21 1.79,4 4,4s4,-1.79 4,-4V7c0,-1.1 0.9,-2 2,-2s2,0.9 2,2v8.18c-1.16,0.41 -2,1.51 -2,2.82c0,1.66 1.34,3 3,3s3,-1.34 3,-3C21,16.7 20.16,15.6 19,15.18z",
        )
    }

    /** Builds a 24dp Material-style icon from SVG path data. */
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
