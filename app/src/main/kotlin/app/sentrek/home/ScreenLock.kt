package app.sentrek.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.ui.LocalTouch
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.mapButtonBorder
import kotlinx.coroutines.launch

/**
 * Over everything while the screen is locked: rain drops, a sleeve or the pocket can't pan
 * the map, press a button or stop a recording. [onTouch] says so, to remind how to unlock.
 */
@Composable
fun TouchShield(onTouch: () -> Unit, modifier: Modifier = Modifier) {
    val currentOnTouch by rememberUpdatedState(onTouch)
    Box(
        modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                currentOnTouch()
                // Every move and the lift too, or the map below would still see a drag.
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                    if (event.changes.none { it.pressed }) break
                }
            }
        },
    )
}

/**
 * Unlocked, a tap locks. Locked, it takes a press held for [UNLOCK_MS] (a ring fills up) to
 * unlock: what a drop or a brushing touch never does. [onShortPress] when let go too soon.
 */
@Composable
fun LockButton(locked: Boolean, onLock: () -> Unit, onUnlock: () -> Unit, onShortPress: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(locked) { progress.snapTo(0f) }
    val isLocked by rememberUpdatedState(locked)
    val lock by rememberUpdatedState(onLock)
    val unlock by rememberUpdatedState(onUnlock)
    val tooSoon by rememberUpdatedState(onShortPress)
    val label = stringResource(if (locked) R.string.lock_unlock else R.string.lock_lock)
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .size(TouchTarget)
            .semantics {
                role = Role.Button
                contentDescription = label
                // Screen readers can't hold: a plain activation does it.
                onClick(label) { if (isLocked) unlock() else lock(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!isLocked) {
                        if (waitForUpOrCancellation() != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            lock()
                        }
                        return@awaitEachGesture
                    }
                    val hold = scope.launch {
                        progress.animateTo(1f, tween(UNLOCK_MS, easing = LinearEasing))
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        unlock()
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) {
                        hold.cancel()
                        scope.launch { progress.snapTo(0f) }
                        tooSoon()
                    }
                }
            },
        shape = RoundedCornerShape(16.dp),
        color = if (locked) colors.primary else colors.surface,
        contentColor = if (locked) colors.onPrimary else colors.onSurface,
        shadowElevation = 4.dp,
        border = mapButtonBorder,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(if (locked) SentrekIcons.Lock else SentrekIcons.LockOpen, contentDescription = null, Modifier.size(LocalTouch.current.icon))
            if (progress.value > 0f) {
                CircularProgressIndicator(
                    progress = { progress.value },
                    color = colors.onPrimary,
                    strokeWidth = 4.dp,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                )
            }
        }
    }
}

private const val UNLOCK_MS = 1_000
