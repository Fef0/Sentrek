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

    /** An arrow pointing ahead: following the position with the map turned to the direction of travel. */
    val Heading: ImageVector by lazy { icon("Sentrek.Heading", "M12,2L4.5,20.29l0.71,0.71L12,18l6.79,3 0.71,-0.71z") }

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

    /** Battery with a plus: battery saver. */
    val BatterySaver: ImageVector by lazy {
        icon(
            "Sentrek.BatterySaver",
            "M16,4h-2V2h-4v2H8C7.45,4 7,4.45 7,5v16c0,0.55 0.45,1 1,1h8c0.55,0 1,-0.45 1,-1V5C17,4.45 16.55,4 16,4zM15,14h-2v2h-2v-2H9v-2h2v-2h2v2h2V14z",
        )
    }

    /** Battery with an exclamation mark: stamina mode. */
    val BatteryAlert: ImageVector by lazy {
        icon(
            "Sentrek.BatteryAlert",
            "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4zM13,18h-2v-2h2v2zM13,14h-2V9h2v5z",
        )
    }

    /** Crossed-out cloud: no network. */
    val CloudOff: ImageVector by lazy {
        icon(
            "Sentrek.CloudOff",
            "M19.35,10.04C18.67,6.59 15.64,4 12,4c-1.48,0 -2.85,0.43 -4.01,1.17l1.46,1.46C10.21,6.23 11.08,6 12,6c3.04,0 5.5,2.46 5.5,5.5v0.5H19c1.66,0 3,1.34 3,3c0,1.13 -0.64,2.11 -1.56,2.62l1.45,1.45C23.16,18.16 24,16.68 24,15c0,-2.64 -2.05,-4.78 -4.65,-4.96zM3,5.27l2.75,2.74C2.56,8.15 0,10.77 0,14c0,3.31 2.69,6 6,6h11.73l2,2L21,20.73 4.27,4 3,5.27zM7.73,10l8,8H6c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4h1.73z",
        )
    }

    val Camera: ImageVector by lazy {
        icon(
            "Sentrek.Camera",
            "M12,12m-3.2,0a3.2,3.2 0,1 1,6.4 0a3.2,3.2 0,1 1,-6.4 0M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5z",
        )
    }

    /** A picture: photos from the gallery. */
    val Image: ImageVector by lazy {
        icon(
            "Sentrek.Image",
            "M21,19V5c0,-1.1 -0.9,-2 -2,-2H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2zM8.5,13.5l2.5,3.01L14.5,12l4.5,6H5l3.5,-4.5z",
        )
    }

    val Search: ImageVector by lazy {
        icon(
            "Sentrek.Search",
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z",
        )
    }

    /** The medical star: the emergency screen. */
    val Emergency: ImageVector by lazy {
        icon(
            "Sentrek.Emergency",
            "M20.79,9.23l-2,-3.46L14,8.54V3h-4v5.54L5.21,5.77l-2,3.46L8,12l-4.79,2.77l2,3.46L10,15.46V21h4v-5.54l4.79,2.77l2,-3.46L16,12L20.79,9.23z",
        )
    }

    /** A folder: tracks, places and maps kept on the phone. */
    val Library: ImageVector by lazy {
        icon("Sentrek.Library", "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }

    val Lock: ImageVector by lazy {
        icon(
            "Sentrek.Lock",
            "M18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM12,17c-1.1,0 -2,-0.9 -2,-2s0.9,-2 2,-2 2,0.9 2,2 -0.9,2 -2,2zM15.1,8H8.9V6c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2z",
        )
    }

    val LockOpen: ImageVector by lazy {
        icon(
            "Sentrek.LockOpen",
            "M12,17c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM18,8h-1V6c0,-2.76 -2.24,-5 -5,-5S7,3.24 7,6h1.9c0,-1.71 1.39,-3.1 3.1,-3.1 1.71,0 3.1,1.39 3.1,3.1v2H6c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V10c0,-1.1 -0.9,-2 -2,-2zM18,20H6V10h12v10z",
        )
    }

    /** Phone signal bars. */
    val Signal: ImageVector by lazy { icon("Sentrek.Signal", "M17,4h3v16h-3zM5,14h3v6H5zM11,9h3v11h-3z") }

    /** A drop of water. */
    val Water: ImageVector by lazy {
        icon("Sentrek.Water", "M12,2c-5.33,4.55 -8,8.48 -8,11.8c0,4.98 3.8,8.2 8,8.2s8,-3.22 8,-8.2C20,10.48 17.33,6.55 12,2z")
    }

    /** A cloud with rain. */
    val Rain: ImageVector by lazy {
        icon(
            "Sentrek.Rain",
            "M19.35,8.04C18.67,4.59 15.64,2 12,2C9.11,2 6.6,3.64 5.35,6.04C2.34,6.36 0,8.91 0,12c0,3.31 2.69,6 6,6h13c2.76,0 5,-2.24 5,-5c0,-2.64 -2.05,-4.78 -4.65,-4.96z" +
                "M8,19l-1.5,3h1.6l1.5,-3zM12,19l-1.5,3h1.6l1.5,-3zM16,19l-1.5,3h1.6l1.5,-3z",
        )
    }

    /** A mountain. */
    val Peak: ImageVector by lazy { icon("Sentrek.Peak", "M14,6l-3.75,5 2.85,3.8 -1.6,1.2C9.81,13.75 7,10 7,10l-6,8h22L14,6z") }

    /** The sun. */
    val Sun: ImageVector by lazy {
        icon(
            "Sentrek.Sun",
            "M12,7c-2.76,0 -5,2.24 -5,5s2.24,5 5,5 5,-2.24 5,-5 -2.24,-5 -5,-5z" +
                "M2,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1L2,11c-0.55,0 -1,0.45 -1,1s0.45,1 1,1z" +
                "M20,13h2c0.55,0 1,-0.45 1,-1s-0.45,-1 -1,-1h-2c-0.55,0 -1,0.45 -1,1s0.45,1 1,1z" +
                "M11,2v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1L13,2c0,-0.55 -0.45,-1 -1,-1s-1,0.45 -1,1z" +
                "M11,20v2c0,0.55 0.45,1 1,1s1,-0.45 1,-1v-2c0,-0.55 -0.45,-1 -1,-1 -0.55,0 -1,0.45 -1,1z" +
                "M5.99,4.58c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0s0.39,-1.03 0,-1.41L5.99,4.58z" +
                "M18.36,16.95c-0.39,-0.39 -1.03,-0.39 -1.41,0 -0.39,0.39 -0.39,1.03 0,1.41l1.06,1.06c0.39,0.39 1.03,0.39 1.41,0 0.39,-0.39 0.39,-1.03 0,-1.41l-1.06,-1.06z" +
                "M19.42,5.99c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06z" +
                "M7.05,18.36c0.39,-0.39 0.39,-1.03 0,-1.41 -0.39,-0.39 -1.03,-0.39 -1.41,0l-1.06,1.06c-0.39,0.39 -0.39,1.03 0,1.41s1.03,0.39 1.41,0l1.06,-1.06z",
        )
    }

    /** A lightning bolt. */
    val Storm: ImageVector by lazy { icon("Sentrek.Storm", "M7,2v11h3v9l7,-12h-4l4,-8z") }

    val ExpandMore: ImageVector by lazy { icon("Sentrek.ExpandMore", "M16.59,8.59L12,13.17 7.41,8.59 6,10l6,6 6,-6z") }

    val ExpandLess: ImageVector by lazy { icon("Sentrek.ExpandLess", "M12,8l-6,6 1.41,1.41L12,10.83l4.59,4.58L18,14z") }

    /** Builds a 24dp Material-style icon from SVG path data. */
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
