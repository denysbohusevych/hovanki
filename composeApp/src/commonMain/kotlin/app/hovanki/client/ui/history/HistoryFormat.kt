package app.hovanki.client.ui.history

import androidx.compose.runtime.Composable
import app.hovanki.client.resources.Res
import app.hovanki.client.resources.decimal_separator
import app.hovanki.client.resources.unit_km
import app.hovanki.client.resources.unit_kmh
import app.hovanki.client.resources.unit_m
import app.hovanki.client.resources.unit_min
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

// Numbers of the history in the player's language: "850 м", "1,2 км", "4,8 км/ч", "25 мин".

/** Under a kilometer in whole meters, then kilometers with one decimal. */
@Composable
fun distanceText(meters: Double): String {
    val separator = stringResource(Res.string.decimal_separator)
    return if (meters < 1_000) {
        stringResource(Res.string.unit_m, meters.roundToLong().toString())
    } else {
        stringResource(Res.string.unit_km, oneDecimal(meters / 1_000, separator))
    }
}

@Composable
fun speedText(metersPerSecond: Double): String =
    stringResource(Res.string.unit_kmh, oneDecimal(metersPerSecond * 3.6, stringResource(Res.string.decimal_separator)))

/** Whole minutes, at least one. */
@Composable
fun minutesText(seconds: Long): String = stringResource(Res.string.unit_min, ((seconds + 30) / 60).coerceAtLeast(1))

/** [value] rounded to one decimal, with [separator] before it: "1,2". */
fun oneDecimal(value: Double, separator: String): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}$separator${tenths % 10}"
}
