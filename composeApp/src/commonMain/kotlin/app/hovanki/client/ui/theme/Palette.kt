package app.hovanki.client.ui.theme

import androidx.compose.ui.graphics.Color
import app.hovanki.shared.protocol.Role

/**
 * The «Dopamine» palette (docs/design.md, «Цвета»), the designer's four colors: green, pink, ink and two whites. Light
 * only: the game is played outdoors in daylight. Every color has a meaning: green — the main action and «allowed»,
 * pink — hiders, and «not allowed here» on the map (forbidden buildings, the part of the zone about to go, the moving
 * ring), ink — the frame and seekers. Orange is left for one alarm only: «the seekers will see you».
 */
object Palette {
    /** Text, outlines, hard shadows, HUD capsules over the map; seekers. */
    val Ink = Color(0xFF111111)

    /** Secondary text. */
    val Ink2 = Color(0xFF3A3A42)

    /** Hints and inactive icons; the lightest gray allowed for text. */
    val Ink3 = Color(0xFF5B5B66)

    /**
     * Screen background, and the second white: whatever is white on white takes this one instead (an avatar in a white
     * row, a tile on a white card).
     */
    val Fog = Color(0xFFF5F5F7)

    /** Cards, fields, lists, buttons. */
    val Paper = Color(0xFFFFFFFF)

    /** Dividers inside cards. */
    val Line = Color(0xFFE2E2E7)

    /** Disabled buttons, quiet tiles. */
    val Sand = Color(0xFFEBEBEF)

    /** The main action; always under ink text or with an ink outline: on white it can't be read by itself. */
    val Green = Color(0xFF12F622)

    /** Secondary text on green. */
    val GreenInk = Color(0xFF0B4D12)

    /** A light green row: «you» in a list. */
    val GreenLight = Color(0xFFE4FEE6)

    /** Hiders; on the map also «not allowed here». White text on it. */
    val Pink = Color(0xFFFF1694)

    /** A hider's own dot, the glow. */
    val PinkLight = Color(0xFFFF7AC0)

    /** Pink text on a light background (pink itself is too light for small text); also the color of form errors. */
    val PinkInk = Color(0xFFC20069)

    /** The one alarm that isn't the designer's: out of the zone, «the seekers will see you». Ink text on it. */
    val Orange = Color(0xFFFF5A1F)

    /** Orange text on a light background. */
    val OrangeInk = Color(0xFFB83A0A)

    /** A player without a signal. */
    val Stale = Color(0xFF4A4A55)

    /** An SOS (docs/adr/0019-pause-and-sos.md): somebody needs help. Nothing else in the game is this red. */
    val Sos = Color(0xFFE0182D)

    /** Hiders: pink. */
    val Hider get() = Pink

    /** Seekers: ink. */
    val Seeker get() = Ink
}

/** The role's color: hiders pink, seekers ink. */
val Role.color: Color
    get() = when (this) {
        Role.HIDER -> Palette.Hider
        Role.SEEKER -> Palette.Seeker
    }

/** Text on [color]: white on pink, green on ink. */
val Role.onColor: Color
    get() = when (this) {
        Role.HIDER -> Color.White
        Role.SEEKER -> Palette.Green
    }
