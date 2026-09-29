package app.hovanki.server.game

import app.hovanki.shared.debug.DebugRadarPair
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarSmoother
import app.hovanki.shared.rules.RadarToken
import kotlin.math.roundToInt

/**
 * What the phones of a game hear of each other over Bluetooth (docs/adr/0012-nearby-radar.md, section 2.3): one
 * smoothed signal per direction (who heard whom), and a pair is as close as its louder direction says. Two phones
 * rarely hear each other alike (a MacBook heard an iPhone 10 dB louder than the other way round, 20 dB at the closest
 * moment): mixed into one signal, the quiet side's readings held the loud side's «burning» down to «hot». Pure:
 * [Game] owns it and passes the time in. Positions never come here.
 */
internal class Radar {
    private val directions = HashMap<Direction, RadarSmoother>()

    /** Token → player for the current five-minute slot and its neighbours; rebuilt when the slot changes. */
    private var tokenIndex: Map<String, PlayerId> = emptyMap()
    private var tokenIndexSlot = Long.MIN_VALUE

    /**
     * [observer] heard [token] at [rssi]: counts for the pair when the token is a player's of this game, corrected by
     * [adjustDb] (the pocket's damping evened out, the stealth taken off). [dwellMillis]: how long a pair has to stay
     * «burning» to count for a claim. Returns whose token it was, or null for anybody else's. [onBandChange] hears of
     * the heard player when the pair's band moved with this reading.
     */
    fun record(
        observer: PlayerId,
        token: String,
        rssi: Int,
        atMillis: Long,
        secrets: () -> Map<PlayerId, String>,
        dwellMillis: Long = 0L,
        adjustDb: (observer: PlayerId, heard: PlayerId) -> Double = { _, _ -> 0.0 },
        onBandChange: (heard: PlayerId) -> Unit = {},
    ): PlayerId? {
        if (!RadarToken.isWellFormed(token)) return null
        val slot = atMillis.floorDiv(RadarToken.SLOT_MILLIS)
        if (slot != tokenIndexSlot) {
            tokenIndex = buildMap {
                for ((playerId, secret) in secrets()) {
                    for (candidate in RadarToken.candidates(secret, atMillis)) put(candidate, playerId)
                }
            }
            tokenIndexSlot = slot
        }
        val heard = tokenIndex[token] ?: return null
        if (heard == observer) return null
        val level = (rssi + adjustDb(observer, heard)).roundToInt()
        val before = bandBetween(observer, heard, atMillis)
        directions.getOrPut(Direction(observer, heard)) { RadarSmoother(dwellMillis) }.add(level, atMillis)
        if (bandBetween(observer, heard, atMillis) != before) onBandChange(heard)
        return heard
    }

    /** The louder of the two directions: either phone hearing the other close is enough. */
    fun bandBetween(a: PlayerId, b: PlayerId, nowMillis: Long): RadarBand =
        both(a, b).maxOfOrNull { it.bandAt(nowMillis) } ?: RadarBand.NONE

    /** When the pair was last heard at all, either way; null: never. */
    fun lastHeardMillis(a: PlayerId, b: PlayerId): Long? = both(a, b).mapNotNull { it.lastAtMillis }.maxOrNull()

    /** The pair was «burning» steadily (the dwell) within [windowMillis] before [nowMillis], either way. */
    fun wasBurningWithin(a: PlayerId, b: PlayerId, nowMillis: Long, windowMillis: Long): Boolean =
        both(a, b).any { it.wasBurningWithin(nowMillis, windowMillis) }

    /**
     * The pairs ever heard, for the e2e observer: the band as [bandBetween] says, the level of the louder direction
     * still heard (of any, when neither is).
     */
    fun debugPairs(nowMillis: Long): List<DebugRadarPair> = directions.keys
        .map { if (it.observer.value <= it.heard.value) it.observer to it.heard else it.heard to it.observer }
        .distinct()
        .map { (a, b) ->
            val signals = both(a, b)
            val alive = signals.filter { it.bandAt(nowMillis) != RadarBand.NONE }.ifEmpty { signals }
            DebugRadarPair(a, b, bandBetween(a, b, nowMillis), alive.mapNotNull { it.levelDbm }.maxOrNull())
        }

    private fun both(a: PlayerId, b: PlayerId): List<RadarSmoother> =
        listOfNotNull(directions[Direction(a, b)], directions[Direction(b, a)])

    /** [observer]'s phone heard [heard]'s. */
    private data class Direction(val observer: PlayerId, val heard: PlayerId)
}
