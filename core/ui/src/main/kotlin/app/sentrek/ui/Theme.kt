package app.sentrek.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Signal orange: readable on snow, rock and forest alike. */
private val SignalOrange = Color(0xFFE85D04)

// High contrast on purpose: the app is read in direct sunlight.
private val LightColors = lightColorScheme(
    primary = SignalOrange,
    onPrimary = Color.White,
    surface = Color.White,
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFF222222),
    background = Color.White,
    onBackground = Color(0xFF111111),
)

// Pure black backgrounds save battery on OLED screens.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color.Black,
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFE0E0E0),
    background = Color.Black,
    onBackground = Color(0xFFF5F5F5),
)

private val SentrekTypography = Typography().run {
    copy(
        // Large, bold numbers for glanceable data tiles.
        displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
        labelLarge = labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontSize = 18.sp),
    )
}

@Composable
fun SentrekTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    gloves: Boolean = false,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTouch provides Touch(gloves)) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = SentrekTypography,
            content = content,
        )
    }
}
