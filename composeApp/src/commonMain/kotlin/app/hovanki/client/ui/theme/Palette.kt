package app.hovanki.client.ui.theme

import androidx.compose.ui.graphics.Color
import app.hovanki.shared.protocol.Role

/**
 * The «Dopamine» palette (docs/design.md, «Цвета»). Light only: the game is played outdoors in daylight. Every color
 * has a meaning: lime — the main action and «allowed», violet — hiders, orange — seekers (and «the seekers will see
 * you»), pink — «not allowed here» (forbidden buildings, the part of the zone about to go, the moving ring).
 */
object Palette {
    /** Text, outlines, hard shadows, HUD capsules over the map. */
    val Ink = Color(0xFF0E0E12)

    /** Secondary text. */
    val Ink2 = Color(0xFF3A3A46)

    /** Hints and inactive icons; the lightest gray allowed for text. */
    val Ink3 = Color(0xFF6B6B78)

    /** Screen background. */
    val Cream = Color(0xFFFFFBEF)

    /** Cards, fields, lists. */
    val Paper = Color(0xFFFFFFFF)

    /** Dividers inside cards. */
    val Line = Color(0xFFECE8DA)

    /** Disabled buttons, quiet tiles. */
    val Sand = Color(0xFFF3EFE2)

    /** The main action; always under ink text or with an ink outline: on white it can't be read by itself. */
    val Lime = Color(0xFFC6FF1A)

    /** Secondary text on lime. */
    val LimeInk = Color(0xFF2A3300)

    val Violet = Color(0xFF6B4BFF)
    val VioletLight = Color(0xFF8B6CFF)
    val Orange = Color(0xFFFF5A1F)

    /** Orange text on a light background (orange itself is too light for text). */
    val OrangeInk = Color(0xFFB83A0A)
    val Pink = Color(0xFFFF4FB8)

    /** Pink text on a light background; also the color of errors in forms. */
    val PinkInk = Color(0xFFB00060)

    /** A player without a signal. */
    val Stale = Color(0xFF4A4A55)

    /** An SOS (docs/adr/0019-pause-and-sos.md): somebody needs help. Nothing else in the game is this red. */
    val Sos = Color(0xFFE0182D)
}

/** The role's color: hiders violet, seekers orange. */
val Role.color: Color
    get() = when (this) {
        Role.HIDER -> Palette.Violet
        Role.SEEKER -> Palette.Orange
    }

/** Text on [color]: white on violet, ink on orange. */
val Role.onColor: Color
    get() = when (this) {
        Role.HIDER -> Color.White
        Role.SEEKER -> Palette.Ink
    }
