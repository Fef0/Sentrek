package app.sentrek.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
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

/**
 * Touch sizes. [target]: minimum height of every button (48 dp is Android's standard).
 * [pressRadius]: extra distance around an icon (or its label) where a long press still selects it.
 */
@Immutable
data class Touch(val target: Dp, val icon: Dp, val pressRadius: Dp) {
    companion object {
        val Standard = Touch(48.dp, 24.dp, 4.dp)
        val Large = Touch(56.dp, 28.dp, 8.dp)
        val Largest = Touch(64.dp, 32.dp, 12.dp)

        /** As big as [Largest], and long presses on the map reach much further. */
        val Gloves = Touch(64.dp, 32.dp, 28.dp)
    }
}

val LocalTouch = staticCompositionLocalOf { Touch.Standard }

/**
 * True over the night map: its ground is nearly black, and so are the buttons of a dark
 * theme. They get a light edge then, or only their shadow would show where they are.
 */
val LocalDarkMap = staticCompositionLocalOf { false }

/** The edge of a button floating over the map: none by day, light over the night map. */
val mapButtonBorder: BorderStroke?
    @Composable @ReadOnlyComposable
    get() = if (LocalDarkMap.current) BorderStroke(1.5.dp, Color.White.copy(alpha = 0.55f)) else null

/** Minimum touch target for the current mode. */
val TouchTarget: Dp
    @Composable @ReadOnlyComposable
    get() = LocalTouch.current.target

/**
 * Large square button floating over the map. [onLongClick] (described by [longClickLabel])
 * adds a second action; [interactionSource] tells when the finger is still on the button.
 * [badge]: a small second icon in the bottom right corner, saying what kind of button it is
 * when [icon] shows its current state.
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
    badge: ImageVector? = null,
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
        border = mapButtonBorder,
    ) {
        val iconSize = LocalTouch.current.icon
        Box(contentAlignment = Alignment.Center) {
            if (badge == null) {
                Icon(icon, contentDescription, Modifier.size(iconSize))
            } else {
                // The main icon centred like every other button's; the badge over its corner,
                // on a patch of the button's own colour so the two stay apart.
                Icon(icon, contentDescription, Modifier.size(iconSize))
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = iconSize / 12, bottom = iconSize / 12)
                        .background(if (active) colors.primary else colors.surface, RoundedCornerShape(5.dp))
                        .padding(2.dp),
                ) {
                    // Big enough to read at a glance, over the main icon's corner.
                    Icon(badge, contentDescription = null, modifier = Modifier.size(iconSize * 0.6f))
                }
            }
        }
    }
}
