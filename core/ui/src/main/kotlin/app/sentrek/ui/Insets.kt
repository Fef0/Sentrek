package app.sentrek.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Clear of the status bar and of camera cutouts at the top (and on the sides in landscape).
 * Also in full screen: the hidden status bar's strip still takes taps for itself there, and
 * the cutout stays.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Modifier.safeTopPadding(): Modifier =
    windowInsetsPadding(
        WindowInsets.safeDrawing.union(WindowInsets.statusBarsIgnoringVisibility)
            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    )
