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

    /**
     * The same signals with another correction, in the shadow (docs/adr/0018-field-test-build.md §3.3): only a field
     * game's [record] feeds them, and nothing of the game ever reads them.
     */
    private val shadowDirections = HashMap<Direction, RadarSmoother>()

    /** A pair's band moved with a reading, or its shadow's ([Radar.record]'s `onFieldBand`). */
    class BandChange(
        val observer: PlayerId,
        val heard: PlayerId,
        val before: RadarBand,
        val after: RadarBand,
        val shadowBefore: RadarBand,
        val shadowAfter: RadarBand,
    )

    /** Token → player for the current five-minute slot and its neighbours; rebuilt when the slot changes. */
    private var tokenIndex: Map<String, PlayerId> = emptyMap()
    private var tokenIndexSlot = Long.MIN_VALUE

    /**
     * [observer] heard [token] at [rssi]: counts for the pair when the token is a player's of this game, corrected by
     * [adjustDb] (the pocket's damping evened out, the stealth taken off). [dwellMillis]: how long a pair has to stay
     * «burning» to count for a claim. Returns whose token it was, or null for anybody else's. [onBandChange] hears of
     * the heard player when the pair's band moved with this reading. With [shadowAdjustDb] (a field game), the reading
     * feeds the shadow's signal too, corrected by it instead, and [onFieldBand] hears of every move of either band;
     * the shadow never changes what the game does.
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
        shadowAdjustDb: ((observer: PlayerId, heard: PlayerId) -> Double)? = null,
        onFieldBand: (BandChange) -> Unit = {},
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
        val after = bandBetween(observer, heard, atMillis)
        if (after != before) onBandChange(heard)
        if (shadowAdjustDb != null) {
            val shadowLevel = (rssi + shadowAdjustDb(observer, heard)).roundToInt()
            val shadowBefore = shadowBandBetween(observer, heard, atMillis)
            shadowDirections.getOrPut(Direction(observer, heard)) { RadarSmoother(dwellMillis) }
                .add(shadowLevel, atMillis)
            val shadowAfter = shadowBandBetween(observer, heard, atMillis)
            if (after != before || shadowAfter != shadowBefore) {
                onFieldBand(BandChange(observer, heard, before, after, shadowBefore, shadowAfter))
            }
        }
        return heard
    }

    /** The shadow's band of the pair, the louder direction, as [bandBetween] is the game's. */
    private fun shadowBandBetween(a: PlayerId, b: PlayerId, nowMillis: Long): RadarBand =
        listOfNotNull(shadowDirections[Direction(a, b)], shadowDirections[Direction(b, a)])
            .maxOfOrNull { it.bandAt(nowMillis) } ?: RadarBand.NONE

    /** How long the pair has been «burning» without a break at [nowMillis], either way; 0: it isn't. */
    fun burningMillis(a: PlayerId, b: PlayerId, nowMillis: Long): Long = both(a, b)
        .filter { it.bandAt(nowMillis) == RadarBand.BURNING }
        .mapNotNull { signal -> signal.burningSinceMillis?.let { nowMillis - it } }
        .maxOrNull()?.coerceAtLeast(0) ?: 0L

    /** When the pair was last steadily «burning» (the dwell), either way; null: never. */
    fun lastBurningMillis(a: PlayerId, b: PlayerId): Long? =
        both(a, b).mapNotNull { it.lastBurningAtMillis }.maxOrNull()

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
