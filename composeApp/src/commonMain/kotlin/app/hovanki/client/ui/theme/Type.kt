package app.hovanki.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.manrope_bold
import app.hovanki.client.resources.manrope_extrabold
import app.hovanki.client.resources.manrope_medium
import app.hovanki.client.resources.manrope_semibold
import app.hovanki.client.resources.unbounded_bold
import app.hovanki.client.resources.unbounded_extrabold
import org.jetbrains.compose.resources.Font

/**
 * The two typefaces (docs/design.md, «Шрифты»): Unbounded for headlines, timers, codes and big numbers, Manrope for
 * everything else. Both OFL, subset to Latin and Cyrillic (licenses in composeResources/files/licenses).
 */
@Immutable
class HovankiFonts(val display: FontFamily, val text: FontFamily)

/** Styles Material's typography has no slot for. */
@Immutable
class HovankiTextStyles(
    /** The phase timer in the game's HUD. */
    val timer: TextStyle,
    /** Digits of a catch code, the lobby code. */
    val code: TextStyle,
    /** Small caps labels in the HUD and on badges. */
    val caps: TextStyle,
)

val LocalHovankiFonts = staticCompositionLocalOf { HovankiFonts(FontFamily.Default, FontFamily.Default) }
val LocalHovankiTextStyles = staticCompositionLocalOf {
    HovankiTextStyles(timer = TextStyle.Default, code = TextStyle.Default, caps = TextStyle.Default)
}

/** The app's own design values next to [androidx.compose.material3.MaterialTheme]. */
object Hovanki {
    val fonts: HovankiFonts
        @Composable get() = LocalHovankiFonts.current

    val text: HovankiTextStyles
        @Composable get() = LocalHovankiTextStyles.current
}

@Composable
internal fun hovankiFonts(): HovankiFonts = HovankiFonts(
    display = FontFamily(
        Font(Res.font.unbounded_bold, FontWeight.Bold),
        Font(Res.font.unbounded_extrabold, FontWeight.ExtraBold),
    ),
    text = FontFamily(
        Font(Res.font.manrope_medium, FontWeight.Medium),
        Font(Res.font.manrope_semibold, FontWeight.SemiBold),
        Font(Res.font.manrope_bold, FontWeight.Bold),
        Font(Res.font.manrope_extrabold, FontWeight.ExtraBold),
    ),
)

internal fun hovankiTypography(fonts: HovankiFonts): Typography {
    fun display(size: Int, line: Int, weight: FontWeight = FontWeight.ExtraBold) =
        TextStyle(fontFamily = fonts.display, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

    fun text(size: Int, line: Int, weight: FontWeight) =
        TextStyle(fontFamily = fonts.text, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

    return Typography(
        displayLarge = display(44, 48),
        displayMedium = display(36, 40),
        displaySmall = display(30, 34),
        headlineLarge = display(28, 32),
        headlineMedium = display(24, 28),
        headlineSmall = display(22, 26),
        titleLarge = display(19, 24, FontWeight.Bold),
        titleMedium = text(16, 22, FontWeight.ExtraBold),
        titleSmall = text(14, 20, FontWeight.ExtraBold),
        bodyLarge = text(16, 22, FontWeight.SemiBold),
        bodyMedium = text(14, 20, FontWeight.SemiBold),
        bodySmall = text(13, 18, FontWeight.Medium),
        labelLarge = text(15, 20, FontWeight.ExtraBold),
        labelMedium = text(12, 16, FontWeight.Bold),
        labelSmall = text(11, 14, FontWeight.Bold),
    )
}

internal fun hovankiTextStyles(fonts: HovankiFonts) = HovankiTextStyles(
    timer = TextStyle(
        fontFamily = fonts.display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 30.sp,
    ),
    code = TextStyle(
        fontFamily = fonts.display,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
    ),
    caps = TextStyle(
        fontFamily = fonts.text,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.08.em,
    ),
)
