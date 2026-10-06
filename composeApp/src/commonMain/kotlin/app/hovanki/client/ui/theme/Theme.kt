package app.hovanki.client.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Material's roles for the widgets that stay Material (fields, dialogs, switches). `primary` is ink, not green: Material
 * draws `primary` as text and thin lines on light surfaces (text buttons, focused fields, spinners), where green can't
 * be read. Green comes in through the containers and the app's own components (ui/common/Pop.kt).
 */
private val colors = lightColorScheme(
    primary = Palette.Ink,
    onPrimary = Color.White,
    primaryContainer = Palette.Green,
    onPrimaryContainer = Palette.Ink,
    inversePrimary = Palette.Green,
    secondary = Palette.Pink,
    onSecondary = Color.White,
    secondaryContainer = Palette.Green,
    onSecondaryContainer = Palette.Ink,
    tertiary = Palette.OrangeInk,
    onTertiary = Color.White,
    tertiaryContainer = Palette.Orange,
    onTertiaryContainer = Palette.Ink,
    background = Palette.Fog,
    onBackground = Palette.Ink,
    surface = Palette.Fog,
    onSurface = Palette.Ink,
    surfaceVariant = Palette.Sand,
    onSurfaceVariant = Palette.Ink2,
    surfaceTint = Palette.Paper,
    inverseSurface = Palette.Ink,
    inverseOnSurface = Color.White,
    error = Palette.PinkInk,
    onError = Color.White,
    errorContainer = Palette.Pink,
    onErrorContainer = Color.White,
    outline = Palette.Ink,
    outlineVariant = Palette.Line,
    scrim = Color.Black,
    surfaceBright = Palette.Paper,
    surfaceDim = Palette.Sand,
    surfaceContainerLowest = Palette.Paper,
    surfaceContainerLow = Palette.Paper,
    surfaceContainer = Palette.Paper,
    surfaceContainerHigh = Palette.Paper,
    surfaceContainerHighest = Palette.Paper,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The «Dopamine» style (docs/adr/0006-visual-style.md): light only, whatever the system theme — the game is played
 * outdoors, where a dark screen can't be read.
 */
@Composable
fun HovankiTheme(content: @Composable () -> Unit) {
    val fonts = hovankiFonts()
    val typography = remember(fonts) { hovankiTypography(fonts) }
    val textStyles = remember(fonts) { hovankiTextStyles(fonts) }
    CompositionLocalProvider(LocalHovankiFonts provides fonts, LocalHovankiTextStyles provides textStyles) {
        MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
    }
}
