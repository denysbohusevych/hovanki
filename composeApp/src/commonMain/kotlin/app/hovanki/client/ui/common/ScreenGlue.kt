package app.hovanki.client.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ClipEntry

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

/** Plain text for [androidx.compose.ui.platform.Clipboard]: the join code to paste into a messenger. */
expect fun plainTextClipEntry(text: String): ClipEntry

/** [epochMillis] as a date and time in the phone's language and time zone ("27 Sep 2026, 18:40"). */
expect fun formatDateTime(epochMillis: Long): String

/** [epochMillis] as a date in the phone's language and time zone ("27 Sep 2026"). */
expect fun formatDate(epochMillis: Long): String
