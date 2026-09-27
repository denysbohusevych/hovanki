package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable

/**
 * Keeps the screen on and at full brightness while in composition, and restores it afterwards: the hider's catch code
 * has to be read out or scanned, often in the sun.
 */
@Composable
expect fun KeepScreenBright()

/**
 * The system asks for less motion (Android: animations turned off; iOS: Reduce Motion). Pulses, blinking, pings and
 * flashes stay off then; colors, vibration and the zone itself still change (docs/design.md, «Батарея и «уменьшить
 * движение»»).
 */
@Composable
expect fun rememberReduceMotion(): Boolean
