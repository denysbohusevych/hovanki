package app.hovanki.shared.rules

import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.totp.hexToBytes
import app.hovanki.shared.totp.hmacSha1
import app.hovanki.shared.totp.toHex
import kotlin.math.exp

/**
 * The token a phone advertises over Bluetooth (docs/adr/0012-nearby-radar.md, section 2.2): the first 4 bytes of
 * HMAC-SHA1 of the player's radar secret and the current five-minute slot, as 8 hex characters. It changes every slot,
 * so a passer-by with a scanner can't follow a player between games; the server knows every secret and tells whose it
 * is. Computed on the phone by the server's clock, like the catch codes. A seeker's token also goes on the air as an
 * iBeacon frame (the token's 4 bytes are its major and minor), which an iPhone in a pocket hears through CoreLocation.
 */
object RadarToken {
    const val SLOT_MILLIS = 5 * 60_000L
    const val LENGTH = 8
    private const val BYTES = LENGTH / 2

    fun at(secretHex: String, nowMillis: Long): String = forSlot(secretHex, nowMillis.floorDiv(SLOT_MILLIS))

    /** The tokens the server takes at [nowMillis]: this slot's, the previous and the next (slot edges, clocks). */
    fun candidates(secretHex: String, nowMillis: Long): List<String> {
        val slot = nowMillis.floorDiv(SLOT_MILLIS)
        return (-1..1).map { forSlot(secretHex, slot + it) }
    }

    fun forSlot(secretHex: String, slot: Long): String {
        val message = ByteArray(8) { i -> (slot ushr (56 - 8 * i)).toByte() }
        return hmacSha1(secretHex.hexToBytes(), message).copyOf(BYTES).toHex()
    }

    fun isWellFormed(token: String): Boolean = token.length == LENGTH && token.all { it in '0'..'9' || it in 'a'..'f' }

    /** The token as an iBeacon's major and minor (two 16-bit numbers), and back. */
    fun toMajorMinor(token: String): Pair<Int, Int> = token.substring(0, 4).toInt(16) to token.substring(4).toInt(16)

    fun fromMajorMinor(major: Int, minor: Int): String =
        major.toString(16).padStart(4, '0') + minor.toString(16).padStart(4, '0')
}

/**
 * The radar's bands (docs/adr/0012-nearby-radar.md, section 2.3): thresholds in dBm on the signal smoothed over the
 * last readings, with hysteresis so a band doesn't flicker at its edge. The numbers are the plan's guesses until the
 * spike on real phones; both the server and the app use these.
 */
object ProximityRules {
    const val WARM_ENTER_DBM = -85.0
    const val WARM_EXIT_DBM = -90.0
    const val HOT_ENTER_DBM = -70.0
    const val HOT_EXIT_DBM = -76.0
    const val BURNING_ENTER_DBM = -60.0
    const val BURNING_EXIT_DBM = -66.0

    /** Without a reading for this long the signal is gone. */
    const val SIGNAL_TTL_MILLIS = 10_000L

    /**
     * How fast the smoothed level follows the readings, by time rather than by reading: phones report from one reading
     * a second (an iPhone ranging a beacon) to ten (Android scanning), and a band should mean the same on both. Rising
     * is quick, so a seeker coming close is felt within a second or two; falling is slower, so a turned body or a
     * passing car doesn't drop the band at once (the hysteresis of [bandFor] helps too). Exponential smoothing with
     * these time constants.
     */
    const val RISE_MILLIS = 500L
    const val FALL_MILLIS = 2_000L

    /** One reading never moves the level more than this share of the way: a lone spike off a wall stays a spike. */
    const val MAX_STEP = 0.6

    /** Readings closer in time count as this far apart; a late one (older than the last one taken) too. */
    const val MIN_READING_GAP_MILLIS = 100L

    /**
     * What the body takes off the signal when a phone is in a pocket, per phone of the pair: added back to the
     * readings so the bands mean distance whether the phone is in the hand or not (a guess until the spike, and until
     * the readings by model say better).
     */
    const val POCKET_OFFSET_DB = 12.0

    /**
     * The pocket stealth ([app.hovanki.shared.protocol.GameFeatures.pocketStealth]): what a hider's phone in the
     * pocket reads colder to the seekers on top of the evening out, about a band.
     */
    const val STEALTH_DB = 8.0

    /**
     * The share of the way from the smoothed level to a new reading that came [elapsedMillis] after the last one:
     * quick up ([rising]), slower down, never more than [MAX_STEP].
     */
    fun smoothingStep(elapsedMillis: Long, rising: Boolean): Double {
        val elapsed = elapsedMillis.coerceAtLeast(MIN_READING_GAP_MILLIS).toDouble()
        val timeConstant = if (rising) RISE_MILLIS else FALL_MILLIS
        return (1 - exp(-elapsed / timeConstant)).coerceAtMost(MAX_STEP)
    }

    /** The band for a smoothed [levelDbm], coming from [previous]: up at the entry thresholds, down at the exits. */
    fun bandFor(levelDbm: Double, previous: RadarBand): RadarBand {
        val up = when {
            levelDbm >= BURNING_ENTER_DBM -> RadarBand.BURNING
            levelDbm >= HOT_ENTER_DBM -> RadarBand.HOT
            levelDbm >= WARM_ENTER_DBM -> RadarBand.WARM
            else -> RadarBand.NONE
        }
        if (up >= previous) return up
        // Dropping: the highest band at or below the previous one whose exit threshold still holds.
        val held = RadarBand.entries.filter { it <= previous && it != RadarBand.NONE && levelDbm >= exitDbm(it) }
        return held.maxOrNull() ?: up
    }

    private fun exitDbm(band: RadarBand): Double = when (band) {
        RadarBand.BURNING -> BURNING_EXIT_DBM
        RadarBand.HOT -> HOT_EXIT_DBM
        RadarBand.WARM -> WARM_EXIT_DBM
        RadarBand.NONE -> Double.NEGATIVE_INFINITY
    }
}

/**
 * The pulse from the pocket (docs/adr/0012-nearby-radar.md, «Пульс»): a heartbeat, the hider's when a seeker comes
 * near, the seeker's sonar when a hider is. Every beat is a soft «lub» and a strong «dub», then quiet; the closer, the
 * faster and the stronger, but never a buzz that doesn't stop (at most about an eighth of the time). Nothing for no
 * signal. Android and iOS play the same [Heartbeat]. Guesses until the spike on real phones.
 */
object HeartbeatRules {
    const val WARM_PERIOD_MILLIS = 1_800L
    const val HOT_PERIOD_MILLIS = 1_200L
    const val BURNING_PERIOD_MILLIS = 850L

    const val SOFT_MILLIS = 35L
    const val GAP_MILLIS = 120L
    const val STRONG_MILLIS = 70L

    /** A motor of one strength: the soft beat is a shorter one instead. */
    const val SOFT_MILLIS_WITHOUT_AMPLITUDE = 20L

    /** The soft beat against the strong one, and never weaker than [MIN_SOFT_AMPLITUDE], or it isn't felt. */
    const val SOFT_SHARE = 0.45
    const val MIN_SOFT_AMPLITUDE = 0.3

    /** The beat for [band]; null: quiet. */
    fun beat(band: RadarBand): Heartbeat? = when (band) {
        RadarBand.NONE -> null
        RadarBand.WARM -> heartbeat(WARM_PERIOD_MILLIS, strongAmplitude = 0.6)
        RadarBand.HOT -> heartbeat(HOT_PERIOD_MILLIS, strongAmplitude = 0.8)
        RadarBand.BURNING -> heartbeat(BURNING_PERIOD_MILLIS, strongAmplitude = 1.0)
    }

    /** The beat's period for [band]; null: quiet. */
    fun periodMillis(band: RadarBand): Long? = beat(band)?.periodMillis

    private fun heartbeat(periodMillis: Long, strongAmplitude: Double) = Heartbeat(
        periodMillis = periodMillis,
        softMillis = SOFT_MILLIS,
        gapMillis = GAP_MILLIS,
        strongMillis = STRONG_MILLIS,
        softAmplitude = maxOf(MIN_SOFT_AMPLITUDE, strongAmplitude * SOFT_SHARE),
        strongAmplitude = strongAmplitude,
    )
}

/**
 * One heartbeat: a soft beat of [softMillis] at [softAmplitude], a pause of [gapMillis], a strong beat of
 * [strongMillis] at [strongAmplitude] (amplitudes 0..1), then quiet until [periodMillis] is over, and again.
 */
data class Heartbeat(
    val periodMillis: Long,
    val softMillis: Long,
    val gapMillis: Long,
    val strongMillis: Long,
    val softAmplitude: Double,
    val strongAmplitude: Double,
) {
    val restMillis: Long get() = periodMillis - softMillis - gapMillis - strongMillis

    /** The share of the time the motor runs. */
    val dutyCycle: Double get() = (softMillis + strongMillis).toDouble() / periodMillis
}

/**
 * The signal one phone hears of another, smoothed: fed with its readings, asked for the band at any moment. The server
 * keeps one per direction and takes the louder of a pair's two (two phones rarely hear each other alike). [dwellMillis]: how long the pair has to stay «burning» before it counts for a claim up close: one spike off
 * a wall is not a meeting. Not thread-safe: the owner synchronizes access.
 */
class RadarSmoother(private val dwellMillis: Long = 0L) {
    var levelDbm: Double? = null
        private set
    var lastAtMillis: Long? = null
        private set
    private var band = RadarBand.NONE

    /** Since when the pair has been «burning» without a break; null: it isn't. */
    var burningSinceMillis: Long? = null
        private set

    /** When the pair was last «burning» for [dwellMillis] or longer, for the claim rule; null: never. */
    var lastBurningAtMillis: Long? = null
        private set

    /**
     * A reading at [atMillis]. A late one (older than the last one taken) still counts, as if it came now; only one
     * older than the signal's life says nothing.
     */
    fun add(rssi: Int, atMillis: Long) {
        val last = lastAtMillis
        if (last != null && atMillis < last - ProximityRules.SIGNAL_TTL_MILLIS) return
        val at = if (last == null) atMillis else maxOf(atMillis, last)
        val gone = last == null || at - last > ProximityRules.SIGNAL_TTL_MILLIS
        val level = levelDbm
        levelDbm = if (last == null || gone || level == null) {
            rssi.toDouble()
        } else {
            level + (rssi - level) * ProximityRules.smoothingStep(atMillis - last, rising = rssi > level)
        }
        lastAtMillis = at
        band = ProximityRules.bandFor(checkNotNull(levelDbm), if (gone) RadarBand.NONE else band)
        if (band == RadarBand.BURNING) {
            val since = burningSinceMillis?.takeUnless { gone } ?: at
            burningSinceMillis = since
            if (at - since >= dwellMillis) lastBurningAtMillis = at
        } else {
            burningSinceMillis = null
        }
    }

    /** The band at [nowMillis]: [RadarBand.NONE] once the signal is older than its life. */
    fun bandAt(nowMillis: Long): RadarBand {
        val last = lastAtMillis ?: return RadarBand.NONE
        return if (nowMillis - last > ProximityRules.SIGNAL_TTL_MILLIS) RadarBand.NONE else band
    }

    /** The pair was «burning» (steadily, see [dwellMillis]) within [windowMillis] before [nowMillis]. */
    fun wasBurningWithin(nowMillis: Long, windowMillis: Long): Boolean =
        lastBurningAtMillis?.let { nowMillis - it <= windowMillis } == true
}
