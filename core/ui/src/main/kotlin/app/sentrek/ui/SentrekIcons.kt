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

    /** Builds a 24dp Material-style icon from SVG path data. */
    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
