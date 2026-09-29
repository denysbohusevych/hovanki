package app.hovanki.server.game

import app.hovanki.shared.debug.DebugRadarPair
import app.hovanki.shared.protocol.PlayerId
import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.rules.RadarSmoother
import app.hovanki.shared.rules.RadarToken
import kotlin.math.roundToInt

/**
 * What the phones of a game hear of each other over Bluetooth (docs/adr/0012-nearby-radar.md, section 2.3): one
 * smoothed signal per pair, fed by whichever of the two phones heard the other. Pure: [Game] owns it and passes the
 * time in. Positions never come here.
 */
internal class Radar {
    private val pairs = HashMap<PairKey, RadarSmoother>()

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
        val pair = pairs.getOrPut(PairKey.of(observer, heard)) { RadarSmoother(dwellMillis) }
        val before = pair.bandAt(atMillis)
        pair.add(level, atMillis)
        if (pair.bandAt(atMillis) != before) onBandChange(heard)
        return heard
    }

    fun bandBetween(a: PlayerId, b: PlayerId, nowMillis: Long): RadarBand =
        pairs[PairKey.of(a, b)]?.bandAt(nowMillis) ?: RadarBand.NONE

    /** When the pair was last heard at all; null: never. */
    fun lastHeardMillis(a: PlayerId, b: PlayerId): Long? = pairs[PairKey.of(a, b)]?.lastAtMillis

    /** The pair was «burning» steadily (the dwell) within [windowMillis] before [nowMillis]. */
    fun wasBurningWithin(a: PlayerId, b: PlayerId, nowMillis: Long, windowMillis: Long): Boolean =
        pairs[PairKey.of(a, b)]?.wasBurningWithin(nowMillis, windowMillis) == true

    /** The pairs ever heard, for the e2e observer. */
    fun debugPairs(nowMillis: Long): List<DebugRadarPair> =
        pairs.map { (key, smoother) -> DebugRadarPair(key.a, key.b, smoother.bandAt(nowMillis), smoother.levelDbm) }

    /** Two players, in a fixed order. */
    private data class PairKey(val a: PlayerId, val b: PlayerId) {
        companion object {
            fun of(x: PlayerId, y: PlayerId): PairKey = if (x.value <= y.value) PairKey(x, y) else PairKey(y, x)
        }
    }
}
