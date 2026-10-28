package app.sentrek.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * One line that shrinks to fit rather than wrap or be cut: for short labels in fixed widths
 * (bar items, option segments) with the largest text sizes. At its normal size whenever it fits.
 */
@Composable
fun FitText(text: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    // Measured again from full size when the text, its style or the text size setting change.
    val fontScale = LocalDensity.current.fontScale
    var scale by remember(text, style, fontScale) { mutableFloatStateOf(1f) }
    var ready by remember(text, style, fontScale) { mutableStateOf(false) }
    Text(
        text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        style = style.copy(fontSize = style.fontSize * scale),
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth && scale > MIN_SCALE) scale *= STEP else ready = true
        },
    )
}

private const val STEP = 0.92f
private const val MIN_SCALE = 0.6f
