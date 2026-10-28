package app.sentrek.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The map's compass, as in Trekarta: hidden while the map points north, fading in as it turns
 * away (so a slight twist doesn't nag). A tap turns the map back to north; a long press locks
 * rotation, and while locked the compass stays, with a padlock.
 */
@Composable
fun MapCompass(
    bearing: Double,
    locked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    contentDescription: String,
    longClickLabel: String,
    modifier: Modifier = Modifier,
) {
    // -180..180, so 359° counts as 1° off north.
    val off = ((bearing + 180) % 360 + 360) % 360 - 180
    if (!locked && abs(off) < 0.05) return
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .size(TouchTarget)
            .alpha(if (locked) 1f else (abs(off) / FULL_ALPHA_DEGREES).toFloat().coerceIn(0.2f, 1f))
            .clip(CircleShape)
            .combinedClickable(
                indication = ripple(),
                interactionSource = null,
                role = Role.Button,
                onClickLabel = contentDescription,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        shape = CircleShape,
        color = colors.surface,
        contentColor = colors.onSurface,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            val south = colors.onSurface.copy(alpha = 0.45f)
            Canvas(Modifier.size(TouchTarget * 0.62f).rotate(-off.toFloat())) {
                val w = size.width
                val h = size.height
                val half = w * 0.2f
                val north = Path().apply {
                    moveTo(w / 2, 0f)
                    lineTo(w / 2 + half, h / 2)
                    lineTo(w / 2 - half, h / 2)
                    close()
                }
                val southPath = Path().apply {
                    moveTo(w / 2, h)
                    lineTo(w / 2 + half, h / 2)
                    lineTo(w / 2 - half, h / 2)
                    close()
                }
                drawPath(north, NorthRed)
                drawPath(southPath, south)
            }
            if (locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(12.dp),
                )
            }
        }
    }
}

private val NorthRed = Color(0xFFD32F2F)

/** Turned this far from north (or more), the compass is fully opaque. */
private const val FULL_ALPHA_DEGREES = 5.0
