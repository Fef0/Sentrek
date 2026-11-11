package app.sentrek.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/** Signal orange: readable on snow, rock and forest alike. */
private val SignalOrange = Color(0xFFE85D04)

/**
 * Emergency red: the SOS button and the call for help, the same on every theme and map, and
 * never the pale red of a dark theme's errors. White on it passes contrast in sunlight.
 */
object EmergencyColors {
    val container = Color(0xFFC62828)
    val content = Color.White
}

// High contrast on purpose: the app is read in direct sunlight.
private val LightColors = lightColorScheme(
    primary = SignalOrange,
    onPrimary = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF111111),
    primaryContainer = Color(0xFFFFDBC8),
    onPrimaryContainer = Color(0xFF331200),
    secondaryContainer = Color(0xFFE6E6E6),
    onSecondaryContainer = Color(0xFF111111),
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFF222222),
    background = Color.White,
    onBackground = Color(0xFF111111),
)

// Dark grey rather than black: softer, and elevation still shows as lighter surfaces.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF5C2300),
    onPrimaryContainer = Color(0xFFFFDBC8),
    secondaryContainer = Color(0xFF3A3A3A),
    onSecondaryContainer = Color(0xFFF0F0F0),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFE0E0E0),
    surfaceContainerLowest = Color(0xFF0F0F0F),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF262626),
    surfaceContainerHighest = Color(0xFF303030),
    background = Color(0xFF121212),
    onBackground = Color(0xFFF5F5F5),
)

// Pure black backgrounds: OLED screens switch those pixels off, saving battery.
private val BlackColors = DarkColors.copy(
    surface = Color.Black,
    surfaceVariant = Color(0xFF1C1C1C),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0A0A0A),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF1A1A1A),
    surfaceContainerHighest = Color(0xFF222222),
    background = Color.Black,
)

private val SentrekTypography = Typography().run {
    copy(
        // Large, bold numbers for glanceable data tiles.
        displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
        labelLarge = labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 18.sp),
    )
}

/**
 * [darkTheme] picks dark colours; with [black] too, pure black backgrounds for OLED screens.
 * [textScale] multiplies the phone's own font scale; [touch] sizes the buttons.
 */
@Composable
fun SentrekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    black: Boolean = false,
    touch: Touch = Touch.Standard,
    textScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val colors = when {
        !darkTheme -> LightColors
        black -> BlackColors
        else -> DarkColors
    }
    MaterialTheme(colorScheme = colors, typography = SentrekTypography) {
        // Text outside any Surface (or on a see-through one) would otherwise default to black,
        // invisible on dark backgrounds.
        CompositionLocalProvider(
            LocalTouch provides touch,
            LocalDensity provides Density(density.density, density.fontScale * textScale),
            LocalContentColor provides colors.onBackground,
            content = content,
        )
    }
}
