package app.sentrek.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Touch sizes: standard Android sizes, or larger ones when glove mode is on. */
@Immutable
data class Touch(val gloves: Boolean) {
    /** Minimum height of every button. */
    val target: Dp get() = if (gloves) 64.dp else 48.dp

    val icon: Dp get() = if (gloves) 32.dp else 24.dp

    /** Extra distance around an icon (or its label) where a long press still selects it. */
    val pressRadius: Dp get() = if (gloves) 28.dp else 4.dp
}

val LocalTouch = staticCompositionLocalOf { Touch(gloves = false) }

/** Minimum touch target for the current mode. */
val TouchTarget: Dp
    @Composable @ReadOnlyComposable
    get() = LocalTouch.current.target

/**
 * Large square button floating over the map. [onLongClick] (described by [longClickLabel])
 * adds a second action; [interactionSource] tells when the finger is still on the button.
 */
@Composable
fun MapButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .size(TouchTarget)
            .clip(shape)
            .combinedClickable(
                interactionSource = interactions,
                indication = ripple(),
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        shape = shape,
        color = if (active) colors.primary else colors.surface,
        contentColor = if (active) colors.onPrimary else colors.onSurface,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, Modifier.size(LocalTouch.current.icon))
        }
    }
}
