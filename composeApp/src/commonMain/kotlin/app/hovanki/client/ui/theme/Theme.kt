package app.hovanki.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Park green: the game is played outdoors, ideally in parks.
private val lightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F0B4),
    onPrimaryContainer = Color(0xFF002204),
    secondary = Color(0xFF52634F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F10),
    tertiary = Color(0xFF1565C0),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD3E3FF),
    onTertiaryContainer = Color(0xFF001B3E),
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF9DD49A),
    onPrimary = Color(0xFF00390A),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFB9F0B4),
    secondary = Color(0xFFB9CCB4),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B38),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFA4C8FF),
    onTertiary = Color(0xFF003063),
    tertiaryContainer = Color(0xFF0D47A1),
    onTertiaryContainer = Color(0xFFD3E3FF),
)

@Composable
fun HovankiTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColors else lightColors,
        content = content,
    )
}
