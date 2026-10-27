package app.sentrek.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class BarItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/**
 * Trekarta-style action bar along the bottom edge: the thumb's natural reach. Every item
 * is a full-height, equal-width target with icon and label, so it works with gloves.
 */
@Composable
fun BottomBar(items: List<BarItem>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (item in items) {
                Surface(
                    onClick = item.onClick,
                    modifier = Modifier.weight(1f).heightIn(min = GloveTarget + 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        Icon(item.icon, contentDescription = null, modifier = Modifier.size(28.dp))
                        Text(item.label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
