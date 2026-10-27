package app.sentrek.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Icons missing from material-icons-core; avoids pulling in the huge extended set. */
object SentrekIcons {
    val Plus: ImageVector get() = Icons.Filled.Add

    val Minus: ImageVector by lazy {
        materialIcon(name = "Sentrek.Minus") {
            materialPath {
                moveTo(19f, 13f)
                horizontalLineTo(5f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(14f)
                close()
            }
        }
    }
}
