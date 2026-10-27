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

    /** A map pin. */
    val Place: ImageVector by lazy {
        icon(
            "Sentrek.Place",
            "M12,2C8.13,2 5,5.13 5,9c0,5.25 7,13 7,13s7,-7.75 7,-13c0,-3.87 -3.13,-7 -7,-7zM12,11.5c-1.38,0 -2.5,-1.12 -2.5,-2.5s1.12,-2.5 2.5,-2.5 2.5,1.12 2.5,2.5 -1.12,2.5 -2.5,2.5z",
        )
    }

    /** A line through a few points: the route planner. */
    val Plan: ImageVector by lazy {
        icon(
            "Sentrek.Plan",
            "M23,8c0,1.1 -0.9,2 -2,2c-0.18,0 -0.35,-0.02 -0.51,-0.07l-3.56,3.55C16.98,13.64 17,13.82 17,14c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2c0,-0.18 0.02,-0.36 0.07,-0.52l-2.55,-2.55C10.36,10.98 10.18,11 10,11s-0.36,-0.02 -0.52,-0.07l-4.55,4.56C4.98,15.65 5,15.82 5,16c0,1.1 -0.9,2 -2,2s-2,-0.9 -2,-2s0.9,-2 2,-2c0.18,0 0.35,0.02 0.51,0.07l4.56,-4.55C8.02,9.36 8,9.18 8,9c0,-1.1 0.9,-2 2,-2s2,0.9 2,2c0,0.18 -0.02,0.36 -0.07,0.52l2.55,2.55C14.64,12.02 14.82,12 15,12s0.36,0.02 0.52,0.07l3.55,-3.56C19.02,8.35 19,8.18 19,8c0,-1.1 0.9,-2 2,-2S23,6.9 23,8z",
        )
    }

    val Undo: ImageVector by lazy {
        icon("Sentrek.Undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88c3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    }

    val Redo: ImageVector by lazy {
        icon("Sentrek.Redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5c1.95,0 3.73,0.72 5.12,1.88L13,16h9V7L18.4,10.6z")
    }

    val Delete: ImageVector by lazy {
        icon("Sentrek.Delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    }

    val Check: ImageVector by lazy { icon("Sentrek.Check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }

    val Close: ImageVector by lazy {
        icon("Sentrek.Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    }

    /** Mountains in profile: the elevation profile. */
    val Profile: ImageVector by lazy {
        icon("Sentrek.Profile", "M14,6l-3.75,5 2.85,3.8 -1.6,1.2C9.81,13.75 7,10 7,10l-6,8h22L14,6z")
    }

    /** Builds a 24dp Material-style icon from SVG path data. */
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
