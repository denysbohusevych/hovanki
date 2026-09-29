package app.hovanki.shared.rules

import app.hovanki.shared.protocol.RadarBand
import app.hovanki.shared.totp.hexToBytes
import app.hovanki.shared.totp.hmacSha1
import app.hovanki.shared.totp.toHex

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

    /** How much a new reading weighs against the smoothed level. */
    const val SMOOTHING = 0.4

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
 * The pulse from the pocket (docs/adr/0012-nearby-radar.md, «Пульс»): how often the phone beats for a band, the
 * hider's when a seeker comes near, the seeker's sonar when a hider is. The closer, the faster; nothing for no signal.
 * Guesses until the spike.
 */
object HeartbeatRules {
    const val WARM_PERIOD_MILLIS = 2_000L
    const val HOT_PERIOD_MILLIS = 1_000L
    const val BURNING_PERIOD_MILLIS = 400L

    /** The beat's period for [band]; null: quiet. */
    fun periodMillis(band: RadarBand): Long? = when (band) {
        RadarBand.NONE -> null
        RadarBand.WARM -> WARM_PERIOD_MILLIS
        RadarBand.HOT -> HOT_PERIOD_MILLIS
        RadarBand.BURNING -> BURNING_PERIOD_MILLIS
    }
}

/**
 * The signal between two phones, smoothed: fed with every reading either of them reports, asked for the band at any
 * moment. [dwellMillis]: how long the pair has to stay «burning» before it counts for a claim up close: one spike off
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

    fun add(rssi: Int, atMillis: Long) {
        val last = lastAtMillis
        // Out of order or a reading older than the signal's life: it says nothing new.
        if (last != null && atMillis < last) return
        val gone = last == null || atMillis - last > ProximityRules.SIGNAL_TTL_MILLIS
        val level = levelDbm
        levelDbm = if (gone || level == null) {
            rssi.toDouble()
        } else {
            level + (rssi - level) * ProximityRules.SMOOTHING
        }
        lastAtMillis = atMillis
        band = ProximityRules.bandFor(checkNotNull(levelDbm), if (gone) RadarBand.NONE else band)
        if (band == RadarBand.BURNING) {
            val since = burningSinceMillis?.takeUnless { gone } ?: atMillis
            burningSinceMillis = since
            if (atMillis - since >= dwellMillis) lastBurningAtMillis = atMillis
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
