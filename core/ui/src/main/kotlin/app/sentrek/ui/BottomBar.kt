package app.sentrek.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * [highlighted]: an ongoing action (e.g. recording), filled with the signal colour.
 * [dimmed]: looks disabled but still answers a tap, to say why it can't be used now.
 */
data class BarItem(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val highlighted: Boolean = false,
    val enabled: Boolean = true,
    val dimmed: Boolean = false,
)

/**
 * Trekarta-style action bar along the bottom edge: the thumb's natural reach. Every item
 * is a full-height target with icon and label; taller in glove mode. Items are as wide as
 * their label needs, plus an even share of the room left, so every label keeps the same full
 * size ("Impostazioni" next to "Cerca"); only when they can't all fit do labels shrink.
 */
@Composable
fun BottomBar(items: List<BarItem>, modifier: Modifier = Modifier, edgeToEdge: Boolean = true) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        BoxWithConstraints(
            // [edgeToEdge] false: a row stacked above the bar, clear of the system navigation.
            modifier = (if (edgeToEdge) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            val style = MaterialTheme.typography.labelLarge
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val labels = items.map { it.label }
            val weights = remember(labels, style, density, constraints.maxWidth) {
                val gap = with(density) { GAP.roundToPx() } * (items.size - 1)
                val padding = with(density) { LABEL_PADDING.roundToPx() } * 2
                val needed = labels.map { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width + padding }
                val room = constraints.maxWidth - gap
                val spare = room - needed.sum()
                if (spare >= 0) needed.map { (it + spare / items.size).toFloat() } else List(items.size) { 1f }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                for ((i, item) in items.withIndex()) {
                    Surface(
                        onClick = item.onClick,
                        enabled = item.enabled,
                        modifier = Modifier.weight(weights[i]).heightIn(min = TouchTarget + 8.dp).alpha(if (item.enabled && !item.dimmed) 1f else 0.38f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (item.highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (item.highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = LABEL_PADDING),
                        ) {
                            Icon(item.icon, contentDescription = null, modifier = Modifier.size(LocalTouch.current.icon - 4.dp))
                            FitText(item.label, style = style)
                        }
                    }
                }
            }
        }
    }
}

private val GAP = 4.dp

/** Room on each side of a label, so neighbours don't run together. */
private val LABEL_PADDING = 2.dp
