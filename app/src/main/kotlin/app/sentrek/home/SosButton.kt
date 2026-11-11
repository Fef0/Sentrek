package app.sentrek.home

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import app.sentrek.R
import app.sentrek.ui.EmergencyColors
import app.sentrek.ui.LocalTouch
import app.sentrek.ui.TouchTarget

/**
 * The SOS screen, one tap from the map: red on every theme and map, so it's found without
 * looking for it. While the screen is [locked] it takes a long press, as the lock does: a
 * pocket can't open it. [onShortPress] then, to say so.
 */
@Composable
fun SosButton(locked: Boolean, onOpen: () -> Unit, onShortPress: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .size(TouchTarget)
            .clip(shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                role = Role.Button,
                onClickLabel = stringResource(R.string.sos_open),
                onLongClickLabel = stringResource(R.string.sos_open),
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onOpen()
                },
                onClick = { if (locked) onShortPress() else onOpen() },
            ),
        shape = shape,
        color = EmergencyColors.container,
        contentColor = EmergencyColors.content,
        shadowElevation = 4.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            // As big as an icon would be, so it grows with the buttons in glove mode.
            val size = LocalTouch.current.icon.value * 0.62f
            Text("SOS", fontWeight = FontWeight.Black, fontSize = size.sp, letterSpacing = 0.5.sp)
        }
    }
}
